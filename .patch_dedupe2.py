# -*- coding: utf-8 -*-
import io

p = "app/src/main/java/com/skyanchor/bookkeeping/sync/SyncCoordinator.java"
s = io.open(p, encoding="utf-8").read()

reps = []

# 1) ack 分支处理 mergedInto
reps.append(("""                if (result.accepted) {
                    if (result.conflicted) {
                        conflicts[0]++;
                    }
                    ackSnapshot(snapshot, result);
                } else if ("CONFLICT_SERVER_WON".equals(result.errorCode)""",
"""                if (result.accepted && result.mergedInto != null
                        && !result.mergedInto.equals(result.syncId)) {
                    // 重名合并：本地行身份重映射到服务器已有实体（开发计划完成备注 14）
                    applyMergedInto(result);
                } else if (result.accepted) {
                    if (result.conflicted) {
                        conflicts[0]++;
                    }
                    ackSnapshot(snapshot, result);
                } else if ("CONFLICT_SERVER_WON".equals(result.errorCode)"""))

# 2) 每轮同步前去重（挂在 repairSyncIds 调用后）
reps.append(("""        running = true;
        pendingAgain = false;
        postStatus(Status.SYNCING);
        int conflicts = 0;
        try {
            repairSyncIds();
            conflicts = pushPending(api);""",
"""        running = true;
        pendingAgain = false;
        postStatus(Status.SYNCING);
        int conflicts = 0;
        try {
            repairSyncIds();
            mergeDuplicateRows();
            conflicts = pushPending(api);"""))

reps.append(("""            try {
                repairSyncIds();
                enqueueEverythingForBootstrap();""",
"""            try {
                repairSyncIds();
                mergeDuplicateRows();
                enqueueEverythingForBootstrap();"""))

# 3) applyMergedInto + mergeDuplicateRows 实现（挂在 repairSyncIds 之前）
reps.append(("""    // ===== 首次同步（基线第 8、25 章） =====""",
"""    // ===== 重名合并（多设备各自初始化同名默认分类/账户的收敛，完成备注 14） =====

    /**
     * Push 应答 mergedInto：服务器按业务键把本条并入已有实体。
     * 本地没有目标实体 → 改写本地 syncId 并采纳服务器版本；
     * 本地已有目标实体（两行都在）→ 引用改指向后物理删除本行
     * （mergedInto 只出现在创建路径，本行从未上云，物理删除安全）。
     */
    private void applyMergedInto(@NonNull ApiDtos.PushResultItem result) {
        String incoming = result.syncId;
        String target = result.mergedInto;
        long now = System.currentTimeMillis();
        switch (result.entityType) {
            case SyncEntityTypes.CATEGORY: {
                CategoryEntity local = database.categoryDao().getBySyncId(incoming);
                if (local == null) {
                    database.syncChangeQueueDao().clearFor(result.entityType, incoming);
                    return;
                }
                CategoryEntity twin = database.categoryDao().getBySyncId(target);
                if (twin == null) {
                    local.syncId = target;
                    local.version = result.version;
                    local.serverReceivedAt = result.serverReceivedAt;
                    database.categoryDao().update(local);
                } else {
                    database.transactionDao().repointCategory(local.id, twin.id, now);
                    database.recurringTransactionDao().repointCategory(local.id, twin.id, now);
                    moveBudgetsOffCategory(local.id, twin.id, now);
                    database.categoryDao().deleteById(local.id);
                }
                database.syncChangeQueueDao().clearFor(result.entityType, incoming);
                break;
            }
            case SyncEntityTypes.ACCOUNT: {
                AccountEntity local = database.accountDao().getBySyncId(incoming);
                if (local == null) {
                    database.syncChangeQueueDao().clearFor(result.entityType, incoming);
                    return;
                }
                AccountEntity twin = database.accountDao().getBySyncId(target);
                if (twin == null) {
                    local.syncId = target;
                    local.version = result.version;
                    local.serverReceivedAt = result.serverReceivedAt;
                    database.accountDao().update(local);
                } else {
                    database.transactionDao().repointAccount(local.id, twin.id, now);
                    database.transactionDao().repointTransferAccount(local.id, twin.id, now);
                    database.recurringTransactionDao().repointAccount(local.id, twin.id, now);
                    database.accountDao().deleteById(local.id);
                }
                database.syncChangeQueueDao().clearFor(result.entityType, incoming);
                break;
            }
            case SyncEntityTypes.BUDGET: {
                BudgetEntity local = findBudgetBySyncId(incoming);
                if (local == null) {
                    database.syncChangeQueueDao().clearFor(result.entityType, incoming);
                    return;
                }
                BudgetEntity twin = findBudgetBySyncId(target);
                if (twin == null) {
                    local.syncId = target;
                    local.version = result.version;
                    local.serverReceivedAt = result.serverReceivedAt;
                    database.budgetDao().upsert(local);
                } else {
                    // 预算没有被引用的行，物理删除重复行即可
                    database.budgetDao().deleteById(local.id);
                }
                database.syncChangeQueueDao().clearFor(result.entityType, incoming);
                break;
            }
            default:
                database.syncChangeQueueDao().clearFor(result.entityType, incoming);
                break;
        }
    }

    /** 把引用 loser 分类的预算改指向 keeper；撞唯一键时保留既有行、退役移动行。 */
    private void moveBudgetsOffCategory(long loserId, long keeperId, long now) {
        for (BudgetEntity budget : database.budgetDao().getActiveByCategoryId(loserId)) {
            BudgetEntity existing = database.budgetDao().getActive(budget.year, budget.month,
                    (int) keeperId);
            if (existing != null && existing.id != budget.id) {
                retireBudget(budget, now);
            } else {
                budget.categoryId = (int) keeperId;
                budget.updatedAt = now;
                database.budgetDao().upsert(budget);
            }
        }
    }

    /** 预算行退役：从未上云的物理删除，其余软删并入队 DELETE 传播。 */
    private void retireBudget(@NonNull BudgetEntity budget, long now) {
        if (budget.version == 0) {
            database.budgetDao().deleteById(budget.id);
            database.syncChangeQueueDao().clearFor(SyncEntityTypes.BUDGET, budget.syncId);
        } else {
            budget.isDeleted = true;
            budget.updatedAt = now;
            database.budgetDao().upsert(budget);
            enqueueDelete(SyncEntityTypes.BUDGET, budget.syncId);
        }
    }

    /** 去重的保留者选择：组内 syncId 最小者——所有设备对同一组必然选中同一行。 */
    @Nullable
    private static <T extends Object> T pickKeeper(@NonNull List<T> group,
                                                   @NonNull java.util.function.Function<T, String> syncId) {
        T keeper = null;
        for (T candidate : group) {
            if (keeper == null || syncId.apply(candidate)
                    .compareTo(syncId.apply(keeper)) < 0) {
                keeper = candidate;
            }
        }
        return keeper;
    }

    /**
     * 本地重名去重：分类按（类型，名称）、账户按名称、预算按（年，月，分类）分组，
     * 每组保留 syncId 最小者，其余行引用改指向后退役（软删并入队 DELETE 传播）。
     * 已被历史污染的多设备数据会在各自下一轮同步自动收敛到每名一条。
     */
    private void mergeDuplicateRows() {
        repository.runInIoTransaction(() -> {
            long now = System.currentTimeMillis();
            mergeDuplicateAccounts(now);
            mergeDuplicateCategories(now);
            mergeDuplicateBudgets(now);
        });
    }

    private void mergeDuplicateAccounts(long now) {
        Map<String, List<AccountEntity>> groups = new LinkedHashMap<>();
        for (AccountEntity entity : database.accountDao().getAllIncludingDeleted()) {
            if (entity.isDeleted || entity.syncId == null || entity.syncId.isEmpty()) {
                continue;
            }
            String key = entity.name == null ? "" : entity.name.trim();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        }
        for (List<AccountEntity> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            AccountEntity keeper = pickKeeper(group, a -> a.syncId);
            for (AccountEntity loser : group) {
                if (loser == keeper) {
                    continue;
                }
                database.transactionDao().repointAccount(loser.id, keeper.id, now);
                database.transactionDao().repointTransferAccount(loser.id, keeper.id, now);
                database.recurringTransactionDao().repointAccount(loser.id, keeper.id, now);
                retireAccount(loser, now);
            }
        }
    }

    private void retireAccount(@NonNull AccountEntity loser, long now) {
        if (loser.version == 0) {
            database.accountDao().deleteById(loser.id);
            database.syncChangeQueueDao().clearFor(SyncEntityTypes.ACCOUNT, loser.syncId);
        } else {
            loser.isDeleted = true;
            loser.updatedAt = now;
            database.accountDao().update(loser);
            enqueueDelete(SyncEntityTypes.ACCOUNT, loser.syncId);
        }
    }

    private void mergeDuplicateCategories(long now) {
        Map<String, List<CategoryEntity>> groups = new LinkedHashMap<>();
        for (CategoryEntity entity : database.categoryDao().getAllIncludingDeleted()) {
            if (entity.isDeleted || entity.syncId == null || entity.syncId.isEmpty()) {
                continue;
            }
            String key = entity.type + "|" + (entity.name == null ? "" : entity.name.trim());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        }
        for (List<CategoryEntity> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            CategoryEntity keeper = pickKeeper(group, c -> c.syncId);
            for (CategoryEntity loser : group) {
                if (loser == keeper) {
                    continue;
                }
                database.transactionDao().repointCategory(loser.id, keeper.id, now);
                database.recurringTransactionDao().repointCategory(loser.id, keeper.id, now);
                moveBudgetsOffCategory(loser.id, keeper.id, now);
                retireCategory(loser, now);
            }
        }
    }

    private void retireCategory(@NonNull CategoryEntity loser, long now) {
        if (loser.version == 0) {
            database.categoryDao().deleteById(loser.id);
            database.syncChangeQueueDao().clearFor(SyncEntityTypes.CATEGORY, loser.syncId);
        } else {
            loser.isDeleted = true;
            database.categoryDao().update(loser);
            enqueueDelete(SyncEntityTypes.CATEGORY, loser.syncId);
        }
    }

    private void mergeDuplicateBudgets(long now) {
        Map<String, List<BudgetEntity>> groups = new LinkedHashMap<>();
        for (BudgetEntity entity : database.budgetDao().getAllIncludingDeleted()) {
            if (entity.isDeleted || entity.syncId == null || entity.syncId.isEmpty()) {
                continue;
            }
            String key = entity.year + "|" + entity.month + "|" + entity.categoryId;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        }
        for (List<BudgetEntity> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            BudgetEntity keeper = pickKeeper(group, b -> b.syncId);
            for (BudgetEntity loser : group) {
                if (loser == keeper) {
                    continue;
                }
                retireBudget(loser, now);
            }
        }
    }

    private void enqueueDelete(@NonNull String entityType, @NonNull String syncId) {
        if (!syncId.isEmpty()) {
            enqueuer.enqueue(entityType, syncId, SyncEntityTypes.OP_DELETE, 0);
        }
    }

    // ===== 首次同步（基线第 8、25 章） ====="""))

for old, new in reps:
    assert old in s, old[:70]
    s = s.replace(old, new, 1)

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("coordinator ok")
