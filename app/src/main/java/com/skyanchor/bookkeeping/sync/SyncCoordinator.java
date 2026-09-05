package com.skyanchor.bookkeeping.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.skyanchor.bookkeeping.data.database.AppDatabase;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.data.entity.SyncChangeQueueEntity;
import com.skyanchor.bookkeeping.data.entity.SyncCursorEntity;
import com.skyanchor.bookkeeping.data.entity.SyncEntityTypes;
import com.skyanchor.bookkeeping.data.entity.SyncStateEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.remote.ApiClient;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;
import com.skyanchor.bookkeeping.data.remote.ApiException;
import com.skyanchor.bookkeeping.data.remote.ApiService;
import com.skyanchor.bookkeeping.data.remote.TokenStore;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.Callback;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Response;

/**
 * 同步协调器（基线第 12、22 章）：同一设备同一时刻只允许一个同步流程；
 * 标准流程 = 先 Push 后 Pull；同步期间允许继续记账，旧快照不覆盖新本地修改。
 *
 * <p>线程模型：所有数据库操作经 {@link BookkeepingRepository#runOnIo}（单线程），
 * 与业务写路径天然串行——ack 判断与队列清理之间不可能插入新的业务写，
 * 这是「双重护栏」成立的前提。
 *
 * <p>失败语义：网络/服务器问题 → 状态置 WAITING_RETRY / SERVER_UNAVAILABLE，
 * 由 SyncScheduler 按指数退避调度；Refresh Token 失效 → AUTH_REQUIRED，
 * 本地功能与队列完全不受影响（基线第 24 章）。
 */
public class SyncCoordinator {

    /** 同步状态机（基线第 23.1 章）。 */
    public enum Status {
        IDLE, SYNCING, WAITING_NETWORK, WAITING_RETRY,
        AUTH_REQUIRED, SERVER_UNAVAILABLE, SUCCESS, ERROR
    }

    private static final int PUSH_BATCH = 200;
    private static final int PULL_LIMIT = 200;
    private static final int MAX_PULL_PAGES = 50;

    private final AppDatabase database;
    private final BookkeepingRepository repository;
    private final ApiClient apiClient;
    private final TokenStore tokenStore;
    private final SyncEnqueuer enqueuer;

    private final MutableLiveData<Status> status = new MutableLiveData<>(Status.IDLE);
    private volatile boolean running;
    private volatile boolean pendingAgain;
    private volatile int failedRounds;
    private volatile String lastError;

    public SyncCoordinator(@NonNull AppDatabase database,
                           @NonNull BookkeepingRepository repository,
                           @NonNull ApiClient apiClient,
                           @NonNull TokenStore tokenStore,
                           @NonNull SyncEnqueuer enqueuer) {
        this.database = database;
        this.repository = repository;
        this.apiClient = apiClient;
        this.tokenStore = tokenStore;
        this.enqueuer = enqueuer;
    }

    // ===== 对外状态 =====

    @NonNull
    public LiveData<Status> observeStatus() {
        return status;
    }

    @NonNull
    public LiveData<Integer> observePendingCount() {
        return database.syncChangeQueueDao().observePendingCount();
    }

    @NonNull
    public LiveData<SyncStateEntity> observeState() {
        return database.syncStateDao().observe();
    }

    @Nullable
    public Status currentStatus() {
        return status.getValue();
    }

    public boolean isSyncing() {
        return running;
    }

    public boolean isSyncEnabled() {
        SyncStateEntity state = database.syncStateDao().get();
        return state != null && state.syncEnabled;
    }

    public boolean hasBootstrapCompleted() {
        String email = tokenStore.getAccountEmail();
        if (email == null) {
            return false;
        }
        return database.syncCursorDao().find(email) != null;
    }

    // ===== 开关（基线第 7 章） =====

    /** 打开 / 关闭云端同步。关闭 = 停触发，不清数据、不退登录。 */
    public void setSyncEnabled(boolean enabled) {
        repository.runOnIo(() -> {
            SyncStateEntity state = requireState();
            state.syncEnabled = enabled;
            state.status = Status.IDLE.name();
            database.syncStateDao().upsert(state);
            postStatus(Status.IDLE);
            if (enabled && hasBootstrapCompleted()) {
                // 已完成首次同步的账号：打开即进入增量同步（未完成的走同步中心确认流程）
                notifyReady();
            }
        });
    }

    /** 开启同步前的初始化检查（基线 7.2）：是否已登录 / 邮箱已验证 / 服务器已配置。 */
    public boolean preflightReady() {
        return tokenStore.isLoggedIn() && tokenStore.isEmailVerified()
                && apiClient.api() != null;
    }

    // ===== 触发入口 =====

    /** 各触发源统一入口；运行中只置 pendingAgain，不并发创建任务（基线 12.1）。 */
    public void requestSync(boolean manual) {
        if (running) {
            pendingAgain = true;
            return;
        }
        if (manual) {
            failedRounds = 0; // 手动同步不受退避限制（基线 10.3）
        }
        repository.runOnIo(this::runSyncLoop);
    }

    /** 首次同步：确认后全量 Push + 全量 Pull（基线第 8、25 章）。 */
    public void confirmBootstrap(@NonNull Callback<Boolean> callback) {
        repository.runOnIo(() -> {
            try {
                repairSyncIds();
                mergeDuplicateRows();
                enqueueEverythingForBootstrap();
                runSyncLoop();
                postMainCallback(callback, hasBootstrapCompleted());
            } catch (Exception e) {
                postMainError(callback, e);
            }
        });
    }

    // ===== 同步主流程 =====

    private void runSyncLoop() {
        if (running) {
            pendingAgain = true;
            return;
        }
        if (!isSyncEnabled() || !tokenStore.isLoggedIn()) {
            postStatus(Status.IDLE);
            return;
        }
        ApiService api = apiClient.api();
        if (api == null) {
            postStatus(Status.SERVER_UNAVAILABLE);
            return;
        }
        running = true;
        pendingAgain = false;
        postStatus(Status.SYNCING);
        int conflicts = 0;
        try {
            repairSyncIds();
            mergeDuplicateRows();
            conflicts = pushPending(api);
            pullChanges(api);
            failedRounds = 0;
            lastError = null;
            finishRound(Status.SUCCESS, conflicts);
        } catch (ApiException e) {
            lastError = e.getMessage();
            if (e.isAuthRequired()) {
                // Refresh Token 已失效：本地功能照常，重新登录后恢复同步（基线 24.3）
                postStatus(Status.AUTH_REQUIRED);
                persistState(Status.AUTH_REQUIRED, e.getMessage(), conflicts);
            } else if (e.isNetworkLevel()) {
                failedRounds++;
                postStatus(Status.SERVER_UNAVAILABLE);
                persistState(Status.SERVER_UNAVAILABLE, e.getMessage(), conflicts);
                retryLater();
            } else {
                failedRounds++;
                postStatus(Status.WAITING_RETRY);
                persistState(Status.WAITING_RETRY, e.getMessage(), conflicts);
                retryLater();
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            failedRounds++;
            postStatus(Status.ERROR);
            persistState(Status.ERROR, e.getMessage(), conflicts);
            retryLater();
        } finally {
            running = false;
        }
        // 同步过程中产生了新变更：继续下一轮（基线 22.12）
        if (pendingAgain && isSyncEnabled()) {
            pendingAgain = false;
            repository.runOnIo(this::runSyncLoop);
        }
    }

    /** Push 全部到期队列项，返回冲突条数（基线第 22 章 3-7 步）。 */
    private int pushPending(ApiService api) throws ApiException, IOException {
        int conflictCount = 0;
        while (true) {
            List<SyncChangeQueueEntity> due = database.syncChangeQueueDao()
                    .takeDue(System.currentTimeMillis(), PUSH_BATCH);
            List<SyncChangeQueueEntity> batch = dedupe(due);
            if (batch.isEmpty()) {
                break;
            }
            Map<String, PushSnapshot> snapshots = new LinkedHashMap<>();
            List<ApiDtos.PushItem> items = new ArrayList<>();
            for (SyncChangeQueueEntity entry : batch) {
                ApiDtos.PushItem item = buildPushItem(entry, snapshots);
                if (item != null) {
                    items.add(item);
                }
            }
            if (items.isEmpty()) {
                break;
            }
            Response<ApiDtos.PushResponse> response = api.push(new ApiDtos.PushRequest(items)).execute();
            if (!response.isSuccessful() || response.body() == null) {
                throw ApiClient.toApiError(response);
            }
            conflictCount += applyPushResults(response.body(), snapshots);
            if (due.size() < PUSH_BATCH && batch.size() < PUSH_BATCH) {
                break;
            }
        }
        return conflictCount;
    }

    /** 队列按 (entityType, syncId) 去重，保留最早一条（其 created_at 代表变更起点）。 */
    @NonNull
    private static List<SyncChangeQueueEntity> dedupe(@NonNull List<SyncChangeQueueEntity> due) {
        Map<String, SyncChangeQueueEntity> unique = new LinkedHashMap<>();
        for (SyncChangeQueueEntity entry : due) {
            unique.putIfAbsent(entry.entityType + "|" + entry.syncId, entry);
        }
        List<SyncChangeQueueEntity> list = new ArrayList<>(unique.values());
        list.sort(Comparator.comparingInt(a -> SyncPayloadMapper.orderOf(a.entityType)));
        return list;
    }

    /**
     * 用实体当前状态构建推送项（快照在发送前重读，基线 12.2）。
     * 实体已被物理清除且从未同步 → 丢弃队列项；否则按软删态推 DELETE。
     */
    @Nullable
    private ApiDtos.PushItem buildPushItem(@NonNull SyncChangeQueueEntity entry,
                                           @NonNull Map<String, PushSnapshot> snapshots) {
        String syncId = entry.syncId;
        switch (entry.entityType) {
            case SyncEntityTypes.CATEGORY: {
                CategoryEntity entity = database.categoryDao().getBySyncId(syncId);
                if (entity == null) {
                    return dropOrDelete(entry, null, snapshots);
                }
                SyncPayloadMapper.ensureSyncId(entity);
                ApiDtos.SyncPayload payload = SyncPayloadMapper.toPayload(entity, database);
                snapshots.put(key(entry), new PushSnapshot(entry.entityType, syncId,
                        entity.version, payload, entity.isDeleted));
                return new ApiDtos.PushItem(entry.entityType, syncId,
                        entity.isDeleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT,
                        entity.version, payload);
            }
            case SyncEntityTypes.ACCOUNT: {
                AccountEntity entity = database.accountDao().getBySyncId(syncId);
                if (entity == null) {
                    return dropOrDelete(entry, null, snapshots);
                }
                ApiDtos.SyncPayload payload = SyncPayloadMapper.toPayload(entity, database);
                snapshots.put(key(entry), new PushSnapshot(entry.entityType, syncId,
                        entity.version, payload, entity.isDeleted));
                return new ApiDtos.PushItem(entry.entityType, syncId,
                        entity.isDeleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT,
                        entity.version, payload);
            }
            case SyncEntityTypes.TRANSACTION: {
                TransactionEntity entity = database.transactionDao().getBySyncId(syncId);
                if (entity == null) {
                    return dropOrDelete(entry, null, snapshots);
                }
                ApiDtos.SyncPayload payload = SyncPayloadMapper.toPayload(entity, database);
                snapshots.put(key(entry), new PushSnapshot(entry.entityType, syncId,
                        entity.version, payload, entity.isDeleted));
                return new ApiDtos.PushItem(entry.entityType, syncId,
                        entity.isDeleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT,
                        entity.version, payload);
            }
            case SyncEntityTypes.BUDGET: {
                // 队列中没有本地 id，按 syncId 全表定位
                BudgetEntity entity = findBudgetBySyncId(syncId);
                if (entity == null) {
                    return dropOrDelete(entry, null, snapshots);
                }
                ApiDtos.SyncPayload payload = SyncPayloadMapper.toPayload(entity, database);
                snapshots.put(key(entry), new PushSnapshot(entry.entityType, syncId,
                        entity.version, payload, entity.isDeleted));
                return new ApiDtos.PushItem(entry.entityType, syncId,
                        entity.isDeleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT,
                        entity.version, payload);
            }
            case SyncEntityTypes.RECURRING: {
                RecurringTransactionEntity entity =
                        database.recurringTransactionDao().getBySyncId(syncId);
                if (entity == null) {
                    return dropOrDelete(entry, null, snapshots);
                }
                ApiDtos.SyncPayload payload = SyncPayloadMapper.toPayload(entity, database);
                snapshots.put(key(entry), new PushSnapshot(entry.entityType, syncId,
                        entity.version, payload, entity.isDeleted));
                return new ApiDtos.PushItem(entry.entityType, syncId,
                        entity.isDeleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT,
                        entity.version, payload);
            }
            default:
                database.syncChangeQueueDao().clearFor(entry.entityType, syncId);
                return null;
        }
    }

    @Nullable
    private ApiDtos.PushItem dropOrDelete(@NonNull SyncChangeQueueEntity entry,
                                          @Nullable Void unused,
                                          @NonNull Map<String, PushSnapshot> snapshots) {
        if (entry.baseVersion <= 0) {
            // 从未同步过且本地已不在：无事可做
            database.syncChangeQueueDao().clearFor(entry.entityType, entry.syncId);
            return null;
        }
        // 本地已物理清除但服务器可能有：推 DELETE 幂等
        ApiDtos.SyncPayload payload = new ApiDtos.SyncPayload();
        payload.isDeleted = true;
        snapshots.put(key(entry), new PushSnapshot(entry.entityType, entry.syncId,
                entry.baseVersion, payload, true));
        return new ApiDtos.PushItem(entry.entityType, entry.syncId,
                SyncEntityTypes.OP_DELETE, entry.baseVersion, payload);
    }

    /**
     * 应用 Push 结果（ack 双重护栏，开发计划备注 6）：
     * 仅当本地版本仍等于推送时的 baseVersion 且内容未变时，才回写服务器版本并清队列；
     * 否则丢弃 ack（服务器已接受的内容是合法历史版本），本地最新状态留给下一轮。
     */
    private int applyPushResults(@NonNull ApiDtos.PushResponse response,
                                 @NonNull Map<String, PushSnapshot> snapshots) {
        int[] conflicts = {0};
        database.runInTransaction(() -> {
            for (ApiDtos.PushResultItem result : response.results) {
                PushSnapshot snapshot = snapshots.get(result.entityType + "|" + result.syncId);
                if (snapshot == null) {
                    continue;
                }
                if (result.accepted && result.mergedInto != null
                        && !result.mergedInto.equals(result.syncId)) {
                    // 重名合并：本地行身份重映射到服务器已有实体（开发计划完成备注 14）
                    applyMergedInto(result);
                } else if (result.accepted) {
                    if (result.conflicted) {
                        conflicts[0]++;
                    }
                    ackSnapshot(snapshot, result);
                } else if ("CONFLICT_SERVER_WON".equals(result.errorCode)
                        && result.payload != null) {
                    // 服务器版本胜出（罕见：同毫秒边界）：接受服务器最终状态
                    conflicts[0]++;
                    applyServerPayload(result.entityType, result.syncId, result.payload,
                            result.version, result.serverReceivedAt);
                    database.syncChangeQueueDao().clearFor(result.entityType, result.syncId);
                } else if (result.errorCode != null) {
                    // 逐项失败（如悬挂引用）：按退避留在队列，等引用方同步后再试
                    long nextRetry = System.currentTimeMillis()
                            + RetryPolicy.delayFor(1);
                    database.syncChangeQueueDao().markFailed(result.entityType, result.syncId,
                            1, result.errorCode, nextRetry);
                }
            }
        });
        return conflicts[0];
    }

    /** ack 护栏：版本一致 + 内容一致才落版本号（比较用载荷重新生成后做字段级相等）。 */
    private void ackSnapshot(@NonNull PushSnapshot snapshot,
                             @NonNull ApiDtos.PushResultItem result) {
        String syncId = snapshot.syncId;
        boolean unchanged;
        String op = snapshot.isDeleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT;
        switch (snapshot.entityType) {
            case SyncEntityTypes.CATEGORY: {
                CategoryEntity current = database.categoryDao().getBySyncId(syncId);
                unchanged = current != null && current.version == snapshot.baseVersion
                        && current.isDeleted == snapshot.isDeleted
                        && payloadEquals(SyncPayloadMapper.toPayload(current, database),
                        snapshot.payload);
                if (unchanged) {
                    current.version = result.version;
                    current.serverReceivedAt = result.serverReceivedAt;
                    database.categoryDao().update(current);
                }
                break;
            }
            case SyncEntityTypes.ACCOUNT: {
                AccountEntity current = database.accountDao().getBySyncId(syncId);
                unchanged = current != null && current.version == snapshot.baseVersion
                        && current.isDeleted == snapshot.isDeleted
                        && payloadEquals(SyncPayloadMapper.toPayload(current, database),
                        snapshot.payload);
                if (unchanged) {
                    current.version = result.version;
                    current.serverReceivedAt = result.serverReceivedAt;
                    database.accountDao().update(current);
                }
                break;
            }
            case SyncEntityTypes.TRANSACTION: {
                TransactionEntity current = database.transactionDao().getBySyncId(syncId);
                unchanged = current != null && current.version == snapshot.baseVersion
                        && current.isDeleted == snapshot.isDeleted
                        && payloadEquals(SyncPayloadMapper.toPayload(current, database),
                        snapshot.payload);
                if (unchanged) {
                    current.version = result.version;
                    current.serverReceivedAt = result.serverReceivedAt;
                    database.transactionDao().update(current);
                }
                break;
            }
            case SyncEntityTypes.BUDGET: {
                BudgetEntity current = findBudgetBySyncId(syncId);
                unchanged = current != null && current.version == snapshot.baseVersion
                        && current.isDeleted == snapshot.isDeleted
                        && payloadEquals(SyncPayloadMapper.toPayload(current, database),
                        snapshot.payload);
                if (unchanged) {
                    current.version = result.version;
                    current.serverReceivedAt = result.serverReceivedAt;
                    database.budgetDao().upsert(current);
                }
                break;
            }
            default: {
                RecurringTransactionEntity current =
                        database.recurringTransactionDao().getBySyncId(syncId);
                unchanged = current != null && current.version == snapshot.baseVersion
                        && current.isDeleted == snapshot.isDeleted
                        && payloadEquals(SyncPayloadMapper.toPayload(current, database),
                        snapshot.payload);
                if (unchanged) {
                    current.version = result.version;
                    current.serverReceivedAt = result.serverReceivedAt;
                    database.recurringTransactionDao().update(current);
                }
                break;
            }
        }
        if (unchanged) {
            database.syncChangeQueueDao().clearFor(snapshot.entityType, syncId);
        }
        // 未通过护栏：保留队列行，下一轮以最新状态重推（旧快照绝不覆盖本地，基线 12.2）
    }

    // ===== Pull（基线第 22 章 8-10 步） =====

    private void pullChanges(ApiService api) throws ApiException, IOException {
        String email = tokenStore.getAccountEmail();
        if (email == null) {
            return;
        }
        SyncCursorEntity cursorRow = database.syncCursorDao().find(email);
        long cursor = cursorRow != null ? cursorRow.lastChangeId : 0;
        long latestChangeId = cursor;
        List<ApiDtos.ChangeItem> deferred = new ArrayList<>();
        long minUnresolved = -1;
        int pages = 0;
        boolean hasMore = true;
        while (hasMore && pages < MAX_PULL_PAGES) {
            Response<ApiDtos.PullResponse> response =
                    api.pull(new ApiDtos.PullRequest(cursor, PULL_LIMIT)).execute();
            if (!response.isSuccessful() || response.body() == null) {
                throw ApiClient.toApiError(response);
            }
            ApiDtos.PullResponse pull = response.body();
            pages++;
            hasMore = pull.hasMore;
            for (ApiDtos.ChangeItem change : pull.changes) {
                latestChangeId = Math.max(latestChangeId, change.changeId);
                boolean applied = applyServerChange(change);
                if (!applied) {
                    deferred.add(change);
                    long id = change.changeId;
                    minUnresolved = minUnresolved < 0 ? id : Math.min(minUnresolved, id);
                }
            }
            cursor = pull.lastChangeId;
            if (pull.changes.isEmpty()) {
                break;
            }
        }
        // 二次尝试暂存项（引用方可能在本轮后续页面里已到位）
        List<ApiDtos.ChangeItem> stillUnresolved = new ArrayList<>();
        for (ApiDtos.ChangeItem change : deferred) {
            if (!applyServerChange(change)) {
                stillUnresolved.add(change);
            }
        }
        long persisted = stillUnresolved.isEmpty()
                ? latestChangeId
                : Math.min(latestChangeId, minUnresolved - 1);
        // 游标只前进不后退：悬挂引用会把有效水位压回，待引用方同步后自然推进
        SyncCursorEntity current = database.syncCursorDao().find(email);
        long finalCursor = Math.max(persisted, current != null ? current.lastChangeId : 0);
        SyncCursorEntity row = new SyncCursorEntity();
        row.accountEmail = email;
        row.lastChangeId = finalCursor;
        row.updatedAt = System.currentTimeMillis();
        database.syncCursorDao().upsert(row);
    }

    /**
     * 应用一条服务器变更。返回 false 表示引用未就绪，需暂存重试。
     * 仅当服务器 version &gt; 本地 version 时应用（本机 Push 回显自然跳过）。
     */
    private boolean applyServerChange(@NonNull ApiDtos.ChangeItem change) {
        boolean[] applied = {false};
        database.runInTransaction(() -> {
            applied[0] = applyServerPayload(change.entityType, change.syncId,
                    change.payload, change.version, change.serverReceivedAt);
        });
        return applied[0];
    }

    private boolean applyServerPayload(@NonNull String entityType, @NonNull String syncId,
                                       @Nullable ApiDtos.SyncPayload payload,
                                       long serverVersion, long serverReceivedAt) {
        if (payload == null) {
            return true; // DELETE 无载荷：对未知 syncId 无操作
        }
        boolean isDelete = payload.isDeleted != null && payload.isDeleted;
        switch (entityType) {
            case SyncEntityTypes.CATEGORY: {
                CategoryEntity local = database.categoryDao().getBySyncId(syncId);
                if (local == null) {
                    if (isDelete) {
                        return true; // 本地没有、云端已删：无需建墓碑
                    }
                    CategoryEntity created = new CategoryEntity();
                    created.syncId = syncId;
                    applyCategoryFields(created, payload);
                    created.version = serverVersion;
                    created.serverReceivedAt = serverReceivedAt;
                    database.categoryDao().insert(created);
                    return true;
                }
                if (serverVersion <= local.version) {
                    return true; // 回显 / 过时变更
                }
                applyCategoryFields(local, payload);
                local.version = serverVersion;
                local.serverReceivedAt = serverReceivedAt;
                database.categoryDao().update(local);
                return true;
            }
            case SyncEntityTypes.ACCOUNT: {
                AccountEntity local = database.accountDao().getBySyncId(syncId);
                if (local == null) {
                    if (isDelete) {
                        return true;
                    }
                    AccountEntity created = new AccountEntity();
                    created.syncId = syncId;
                    created.createdAt = System.currentTimeMillis();
                    applyAccountFields(created, payload);
                    created.version = serverVersion;
                    created.serverReceivedAt = serverReceivedAt;
                    database.accountDao().insert(created);
                    return true;
                }
                if (serverVersion <= local.version) {
                    return true;
                }
                applyAccountFields(local, payload);
                local.version = serverVersion;
                local.serverReceivedAt = serverReceivedAt;
                database.accountDao().update(local);
                return true;
            }
            case SyncEntityTypes.TRANSACTION: {
                TransactionEntity local = database.transactionDao().getBySyncId(syncId);
                Long categoryId = SyncPayloadMapper.localCategoryId(database, payload.categorySyncId);
                Long accountId = SyncPayloadMapper.localAccountId(database, payload.accountSyncId);
                Long transferAccountId = SyncPayloadMapper.localAccountId(database,
                        payload.transferAccountSyncId);
                if ((payload.categorySyncId != null && categoryId == null)
                        || (payload.accountSyncId != null && accountId == null)
                        || (payload.transferAccountSyncId != null && transferAccountId == null)) {
                    return false; // 引用未就绪，暂存
                }
                if (local == null) {
                    if (isDelete) {
                        return true;
                    }
                    TransactionEntity created = new TransactionEntity();
                    created.syncId = syncId;
                    applyTransactionFields(created, payload, categoryId, accountId,
                            transferAccountId);
                    created.version = serverVersion;
                    created.serverReceivedAt = serverReceivedAt;
                    database.transactionDao().insert(created);
                    return true;
                }
                if (serverVersion <= local.version) {
                    return true;
                }
                applyTransactionFields(local, payload, categoryId, accountId, transferAccountId);
                local.version = serverVersion;
                local.serverReceivedAt = serverReceivedAt;
                database.transactionDao().update(local);
                return true;
            }
            case SyncEntityTypes.BUDGET: {
                BudgetEntity local = findBudgetBySyncId(syncId);
                Long categoryId = payload.categorySyncId == null || payload.categorySyncId.isEmpty()
                        ? 0L : SyncPayloadMapper.localCategoryId(database, payload.categorySyncId);
                if (categoryId == null) {
                    return false; // 分类引用未就绪
                }
                if (local == null) {
                    if (isDelete) {
                        return true;
                    }
                    BudgetEntity created = new BudgetEntity();
                    created.syncId = syncId;
                    applyBudgetFields(created, payload, categoryId);
                    created.version = serverVersion;
                    created.serverReceivedAt = serverReceivedAt;
                    database.budgetDao().upsert(created);
                    return true;
                }
                if (serverVersion <= local.version) {
                    return true;
                }
                applyBudgetFields(local, payload, categoryId);
                local.version = serverVersion;
                local.serverReceivedAt = serverReceivedAt;
                database.budgetDao().upsert(local);
                return true;
            }
            case SyncEntityTypes.RECURRING: {
                RecurringTransactionEntity local =
                        database.recurringTransactionDao().getBySyncId(syncId);
                Long categoryId = SyncPayloadMapper.localCategoryId(database, payload.categorySyncId);
                Long accountId = SyncPayloadMapper.localAccountId(database, payload.accountSyncId);
                if ((payload.categorySyncId != null && categoryId == null)
                        || (payload.accountSyncId != null && accountId == null)) {
                    return false;
                }
                if (local == null) {
                    if (isDelete) {
                        return true;
                    }
                    RecurringTransactionEntity created = new RecurringTransactionEntity();
                    created.syncId = syncId;
                    created.createdAt = System.currentTimeMillis();
                    applyRecurringFields(created, payload, categoryId, accountId);
                    created.version = serverVersion;
                    created.serverReceivedAt = serverReceivedAt;
                    database.recurringTransactionDao().insert(created);
                    return true;
                }
                if (serverVersion <= local.version) {
                    return true;
                }
                applyRecurringFields(local, payload, categoryId, accountId);
                local.version = serverVersion;
                local.serverReceivedAt = serverReceivedAt;
                database.recurringTransactionDao().update(local);
                return true;
            }
            default:
                return true;
        }
    }

    // ===== 字段应用（Pull 侧） =====

    private void applyCategoryFields(CategoryEntity entity, ApiDtos.SyncPayload payload) {
        entity.name = payload.name != null ? payload.name : entity.name;
        entity.icon = payload.icon != null ? payload.icon : entity.icon;
        entity.type = payload.type != null ? payload.type : entity.type;
        entity.sortOrder = payload.sortOrder != null ? payload.sortOrder : entity.sortOrder;
        entity.isDefault = payload.isDefault != null ? payload.isDefault : entity.isDefault;
        entity.isDeleted = payload.isDeleted != null && payload.isDeleted;
    }

    private void applyAccountFields(AccountEntity entity, ApiDtos.SyncPayload payload) {
        entity.name = payload.name != null ? payload.name : entity.name;
        entity.type = payload.type != null ? payload.type : entity.type;
        entity.initialBalance = payload.initialBalance != null
                ? payload.initialBalance : entity.initialBalance;
        entity.isCredit = payload.isCredit != null ? payload.isCredit : entity.isCredit;
        entity.sortOrder = payload.sortOrder != null ? payload.sortOrder : entity.sortOrder;
        entity.isArchived = payload.isArchived != null ? payload.isArchived : entity.isArchived;
        entity.isDeleted = payload.isDeleted != null && payload.isDeleted;
        // balance 缓存由 Pull 结束后的统一重算对齐（真值在交易）
    }

    private void applyTransactionFields(TransactionEntity entity, ApiDtos.SyncPayload payload,
                                        Long categoryId, Long accountId, Long transferAccountId) {
        entity.type = payload.type != null ? payload.type : entity.type;
        entity.amount = payload.amount != null ? payload.amount : entity.amount;
        entity.categoryId = categoryId;
        entity.accountId = accountId;
        entity.transferAccountId = transferAccountId;
        entity.date = payload.date != null ? payload.date : entity.date;
        entity.time = payload.time != null ? payload.time : entity.time;
        entity.note = payload.note;
        entity.createdAt = payload.clientCreatedAt != null
                ? payload.clientCreatedAt : entity.createdAt;
        entity.updatedAt = payload.clientUpdatedAt != null
                ? payload.clientUpdatedAt : entity.updatedAt;
        entity.isDeleted = payload.isDeleted != null && payload.isDeleted;
    }

    private void applyBudgetFields(BudgetEntity entity, ApiDtos.SyncPayload payload,
                                   long categoryId) {
        entity.year = payload.year != null ? payload.year : entity.year;
        entity.month = payload.month != null ? payload.month : entity.month;
        entity.categoryId = (int) categoryId;
        entity.amount = payload.amount != null ? payload.amount : entity.amount;
        entity.updatedAt = payload.clientUpdatedAt != null
                ? payload.clientUpdatedAt : entity.updatedAt;
        entity.isDeleted = payload.isDeleted != null && payload.isDeleted;
    }

    private void applyRecurringFields(RecurringTransactionEntity entity,
                                      ApiDtos.SyncPayload payload,
                                      Long categoryId, Long accountId) {
        entity.name = payload.name != null ? payload.name : entity.name;
        entity.type = payload.type != null ? payload.type : entity.type;
        entity.amount = payload.amount != null ? payload.amount : entity.amount;
        entity.categoryId = categoryId;
        entity.accountId = accountId;
        entity.frequency = payload.frequency != null ? payload.frequency : entity.frequency;
        entity.interval = payload.repeatInterval != null
                ? payload.repeatInterval : entity.interval;
        entity.startDate = payload.startDate != null ? payload.startDate : entity.startDate;
        entity.endDate = payload.endDate != null ? payload.endDate : entity.endDate;
        entity.nextRunDate = payload.nextRunDate != null
                ? payload.nextRunDate : entity.nextRunDate;
        entity.anchorDayOfMonth = payload.anchorDayOfMonth != null
                ? payload.anchorDayOfMonth : entity.anchorDayOfMonth;
        entity.isEnabled = payload.isEnabled != null ? payload.isEnabled : entity.isEnabled;
        entity.note = payload.note;
        entity.updatedAt = payload.clientUpdatedAt != null
                ? payload.clientUpdatedAt : entity.updatedAt;
        entity.isDeleted = payload.isDeleted != null && payload.isDeleted;
    }

    // ===== 重名合并（多设备各自初始化同名默认分类/账户的收敛，完成备注 14） =====

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

    // ===== 首次同步（基线第 8、25 章） =====

    /** 为存量空 syncId 行补 UUID（种子数据 / 旧迁移数据都要能上云）。 */
    private void repairSyncIds() {
        database.runInTransaction(() -> {
            for (CategoryEntity entity : database.categoryDao().getAllIncludingDeleted()) {
                if (entity.syncId == null || entity.syncId.isEmpty()) {
                    SyncPayloadMapper.ensureSyncId(entity);
                    database.categoryDao().update(entity);
                }
            }
            for (AccountEntity entity : database.accountDao().getAllIncludingDeleted()) {
                if (entity.syncId == null || entity.syncId.isEmpty()) {
                    SyncPayloadMapper.ensureSyncId(entity);
                    database.accountDao().update(entity);
                }
            }
            for (TransactionEntity entity : database.transactionDao()
                    .getAllEntitiesIncludingDeleted()) {
                if (entity.syncId == null || entity.syncId.isEmpty()) {
                    SyncPayloadMapper.ensureSyncId(entity);
                    database.transactionDao().update(entity);
                }
            }
            for (BudgetEntity entity : database.budgetDao().getAllIncludingDeleted()) {
                if (entity.syncId == null || entity.syncId.isEmpty()) {
                    SyncPayloadMapper.ensureSyncId(entity);
                    database.budgetDao().upsert(entity);
                }
            }
            for (RecurringTransactionEntity entity : database.recurringTransactionDao()
                    .getAllIncludingDeleted()) {
                if (entity.syncId == null || entity.syncId.isEmpty()) {
                    SyncPayloadMapper.ensureSyncId(entity);
                    database.recurringTransactionDao().update(entity);
                }
            }
        });
    }

    /** bootstrap 确认后：全量入队（UPSERT / DELETE 按软删态），走常规 Push 收敛。 */
    private void enqueueEverythingForBootstrap() {
        database.runInTransaction(() -> {
            for (CategoryEntity entity : database.categoryDao().getAllIncludingDeleted()) {
                database.syncChangeQueueDao().upsert(queueRow(SyncEntityTypes.CATEGORY,
                        entity.syncId, entity.isDeleted));
            }
            for (AccountEntity entity : database.accountDao().getAllIncludingDeleted()) {
                database.syncChangeQueueDao().upsert(queueRow(SyncEntityTypes.ACCOUNT,
                        entity.syncId, entity.isDeleted));
            }
            for (TransactionEntity entity : database.transactionDao()
                    .getAllEntitiesIncludingDeleted()) {
                database.syncChangeQueueDao().upsert(queueRow(SyncEntityTypes.TRANSACTION,
                        entity.syncId, entity.isDeleted));
            }
            for (BudgetEntity entity : database.budgetDao().getAllIncludingDeleted()) {
                database.syncChangeQueueDao().upsert(queueRow(SyncEntityTypes.BUDGET,
                        entity.syncId, entity.isDeleted));
            }
            for (RecurringTransactionEntity entity : database.recurringTransactionDao()
                    .getAllIncludingDeleted()) {
                database.syncChangeQueueDao().upsert(queueRow(SyncEntityTypes.RECURRING,
                        entity.syncId, entity.isDeleted));
            }
        });
    }

    private static SyncChangeQueueEntity queueRow(String entityType, String syncId,
                                                  boolean deleted) {
        SyncChangeQueueEntity row = new SyncChangeQueueEntity();
        row.entityType = entityType;
        row.syncId = syncId;
        row.operation = deleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT;
        row.baseVersion = 0;
        row.createdAt = System.currentTimeMillis();
        row.nextRetryAt = 0;
        return row;
    }

    /** 本地数据统计（首次同步确认页用，基线 8.1）：账单/账户/分类/预算/周期。 */
    public void loadLocalCounts(@NonNull Callback<int[]> callback) {
        repository.runOnIo(() -> {
            int[] counts = {
                    database.transactionDao().countAll(),
                    database.accountDao().countAll(),
                    database.categoryDao().countAll(),
                    database.budgetDao().countAll(),
                    database.recurringTransactionDao().countAll()
            };
            postMainCallbackValue(callback, counts);
        });
    }

    // ===== 工具 =====

    private void retryLater() {
        SyncScheduler.scheduleRetry(RetryPolicy.delayFor(failedRounds));
    }

    private void finishRound(Status success, int conflicts) {
        postStatus(success);
        persistState(success, null, conflicts);
        // Pull 后统一重算账户余额缓存（继承 V2「缓存不是唯一真值」）
        repository.runOnIo(repository::validateAccountBalancesInternal);
    }

    private void persistState(Status statusName, @Nullable String error, int conflicts) {
        SyncStateEntity state = requireState();
        state.status = statusName.name();
        state.lastError = error;
        state.conflictCount = conflicts;
        if (statusName == Status.SUCCESS) {
            state.lastSyncAt = System.currentTimeMillis();
        }
        database.syncStateDao().upsert(state);
    }

    @NonNull
    private SyncStateEntity requireState() {
        SyncStateEntity state = database.syncStateDao().get();
        if (state == null) {
            state = new SyncStateEntity();
            state.id = SyncStateEntity.SINGLETON_ID;
        }
        return state;
    }

    private void notifyReady() {
        SyncScheduler.requestSyncNow();
    }

    private void postStatus(Status value) {
        status.postValue(value);
    }

    private void postMainCallback(@NonNull Callback<Boolean> callback, boolean value) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> callback.onResult(value));
    }

    private void postMainError(@NonNull Callback<?> callback, @NonNull Exception e) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> callback.onError(e));
    }

    private <T> void postMainCallbackValue(@NonNull Callback<T> callback, T value) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> callback.onResult(value));
    }

    @NonNull
    private static String key(@NonNull SyncChangeQueueEntity entry) {
        return entry.entityType + "|" + entry.syncId;
    }

    private static boolean payloadEquals(@Nullable ApiDtos.SyncPayload a,
                                         @Nullable ApiDtos.SyncPayload b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return java.util.Objects.equals(a.name, b.name)
                && java.util.Objects.equals(a.icon, b.icon)
                && java.util.Objects.equals(a.type, b.type)
                && java.util.Objects.equals(a.sortOrder, b.sortOrder)
                && java.util.Objects.equals(a.isDefault, b.isDefault)
                && java.util.Objects.equals(a.initialBalance, b.initialBalance)
                && java.util.Objects.equals(a.balance, b.balance)
                && java.util.Objects.equals(a.isCredit, b.isCredit)
                && java.util.Objects.equals(a.isArchived, b.isArchived)
                && java.util.Objects.equals(a.amount, b.amount)
                && java.util.Objects.equals(a.categorySyncId, b.categorySyncId)
                && java.util.Objects.equals(a.accountSyncId, b.accountSyncId)
                && java.util.Objects.equals(a.transferAccountSyncId, b.transferAccountSyncId)
                && java.util.Objects.equals(a.date, b.date)
                && java.util.Objects.equals(a.time, b.time)
                && java.util.Objects.equals(a.note, b.note)
                && java.util.Objects.equals(a.clientCreatedAt, b.clientCreatedAt)
                && java.util.Objects.equals(a.year, b.year)
                && java.util.Objects.equals(a.month, b.month)
                && java.util.Objects.equals(a.frequency, b.frequency)
                && java.util.Objects.equals(a.repeatInterval, b.repeatInterval)
                && java.util.Objects.equals(a.startDate, b.startDate)
                && java.util.Objects.equals(a.endDate, b.endDate)
                && java.util.Objects.equals(a.nextRunDate, b.nextRunDate)
                && java.util.Objects.equals(a.anchorDayOfMonth, b.anchorDayOfMonth)
                && java.util.Objects.equals(a.isEnabled, b.isEnabled)
                && java.util.Objects.equals(a.clientUpdatedAt, b.clientUpdatedAt)
                && java.util.Objects.equals(a.isDeleted, b.isDeleted);
    }

    private BudgetEntity findBudgetBySyncId(String syncId) {
        for (BudgetEntity entity : database.budgetDao().getAllIncludingDeleted()) {
            if (syncId.equals(entity.syncId)) {
                return entity;
            }
        }
        return null;
    }

    /** Push 快照：发送时刻的实体版本与内容（ack 护栏依据）。 */
    private static final class PushSnapshot {
        final String entityType;
        final String syncId;
        final long baseVersion;
        final ApiDtos.SyncPayload payload;
        final boolean isDeleted;

        PushSnapshot(String entityType, String syncId, long baseVersion,
                     ApiDtos.SyncPayload payload, boolean isDeleted) {
            this.entityType = entityType;
            this.syncId = syncId;
            this.baseVersion = baseVersion;
            this.payload = payload;
            this.isDeleted = isDeleted;
        }
    }
}
