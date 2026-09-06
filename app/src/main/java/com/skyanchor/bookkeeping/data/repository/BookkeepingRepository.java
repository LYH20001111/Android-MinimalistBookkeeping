package com.skyanchor.bookkeeping.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.skyanchor.bookkeeping.data.database.AppDatabase;
import com.skyanchor.bookkeeping.data.database.DefaultData;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.LedgerEntity;
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.data.entity.SyncEntityTypes;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionExport;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.data.entity.UserSettingsEntity;
import com.skyanchor.bookkeeping.data.model.AccountBalance;
import com.skyanchor.bookkeeping.data.model.DailySummary;
import com.skyanchor.bookkeeping.data.model.DayCount;
import com.skyanchor.bookkeeping.data.model.DeleteAccountResult;
import com.skyanchor.bookkeeping.data.model.DeleteCategoryResult;
import com.skyanchor.bookkeeping.data.model.SearchFilter;
import com.skyanchor.bookkeeping.domain.account.AccountBalanceValidator;
import com.skyanchor.bookkeeping.domain.account.CalculateAccountBalanceUseCase;
import com.skyanchor.bookkeeping.domain.recurring.GenerateRecurringTransactionsUseCase;
import com.skyanchor.bookkeeping.sync.SyncEnqueuer;
import com.skyanchor.bookkeeping.sync.SyncPayloadMapper;
import com.skyanchor.bookkeeping.util.Callback;
import com.skyanchor.bookkeeping.util.DateUtil;
import com.skyanchor.bookkeeping.util.ThemeStore;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 数据仓库。UI 只与仓库交互，不直接持有 DAO（V1 基线第 13 章）。
 *
 * <p>读全部以 LiveData 暴露；写全部落到单线程 IO 执行器，完成后在主线程回调。
 * 由于所有页面共享同一份 LiveData 数据源，新增/编辑/删除后记录页、图表页、预算会自动刷新。
 */
public class BookkeepingRepository {

    private final AppDatabase database;
    private final Context appContext;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 账户余额重算用例：写入交易 / 修改账户初始余额时在同一事务内对齐缓存。 */
    private final CalculateAccountBalanceUseCase balanceUseCase;

    /** 余额缓存一致性校验：启动时兜底纠正缓存与重算的偏差（V2 Phase 9）。 */
    private final AccountBalanceValidator balanceValidator;

    /**
     * V3：同步入队器（可选依赖，组合根在构建 SyncCoordinator 前注入）。
     * 所有业务写路径在**同一 DB 事务内**标记待同步变更（基线第 23 章）。
     */
    @Nullable
    private SyncEnqueuer syncEnqueuer;

    public BookkeepingRepository(@NonNull Context context, @NonNull AppDatabase database) {
        this.appContext = context.getApplicationContext();
        this.database = database;
        this.balanceUseCase = new CalculateAccountBalanceUseCase(
                database.accountDao(), database.transactionDao());
        this.balanceValidator = new AccountBalanceValidator(
                database.accountDao(), balanceUseCase);
    }

    /** 供组合根 / 一致性校验获取余额重算用例。 */
    @NonNull
    public CalculateAccountBalanceUseCase getBalanceUseCase() {
        return balanceUseCase;
    }

    /** V3：暴露单线程 IO 执行器（同步层共用同一线程，保证数据库访问串行）。 */
    @NonNull
    public java.util.concurrent.ExecutorService getIoExecutor() {
        return io;
    }

    /** V3：注入同步入队器（组合根一次性调用）。 */
    public void setSyncEnqueuer(@NonNull SyncEnqueuer enqueuer) {
        this.syncEnqueuer = enqueuer;
    }

    /**
     * V3：在 IO 执行器上运行一个数据库事务（同步引擎专用）。
     * 单线程执行器保证与业务写路径串行——同步 ack 判断与业务写不会交错。
     */
    public void runInIoTransaction(@NonNull Runnable task) {
        io.execute(() -> database.runInTransaction(task));
    }

    /** V3：同步引擎在 IO 线程直接调用的一致性校验（不回调 UI）。 */
    public void validateAccountBalancesInternal() {
        balanceValidator.validateAndFixAll();
    }

    /**
     * V3：事务内标记待同步变更。syncEnqueuer 未注入时为 no-op，
     * 本地优先语义完全不受影响。
     */
    private void enqueueSync(@NonNull String entityType, @Nullable String syncId,
                             boolean deleted) {
        if (syncEnqueuer != null && syncId != null && !syncId.isEmpty()) {
            syncEnqueuer.enqueue(entityType, syncId,
                    deleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT, 0);
            // 触发 3 秒防抖自动同步（基线 9.3）。通知经主线程 → SyncScheduler 防抖 →
            // requestSync → IO 队列，天然排在当前写事务提交之后，无重入风险。
            syncEnqueuer.notifyPendingChanges();
        }
    }

    /** V3：更新路径回填同步元数据——UI 可能传「半实体」，@Update 会整行覆写。 */
    private static void carryTransactionMetadata(@NonNull TransactionEntity target,
                                                 @Nullable TransactionEntity existing) {
        if (existing != null) {
            target.syncId = existing.syncId;
            target.version = existing.version;
            target.serverReceivedAt = existing.serverReceivedAt;
            target.isDeleted = existing.isDeleted;
            target.ledgerId = existing.ledgerId;
        }
    }

    /** V3：同上（账户）。 */
    private static void carryAccountMetadata(@NonNull AccountEntity target,
                                             @Nullable AccountEntity existing) {
        if (existing != null) {
            target.syncId = existing.syncId;
            target.version = existing.version;
            target.serverReceivedAt = existing.serverReceivedAt;
            target.isDeleted = existing.isDeleted;
            target.ledgerId = existing.ledgerId;
        }
    }

    /** V3：同上（周期账单）。 */
    private static void carryRecurringMetadata(@NonNull RecurringTransactionEntity target,
                                               @Nullable RecurringTransactionEntity existing) {
        if (existing != null) {
            target.syncId = existing.syncId;
            target.version = existing.version;
            target.serverReceivedAt = existing.serverReceivedAt;
            target.isDeleted = existing.isDeleted;
            target.ledgerId = existing.ledgerId;
        }
    }

    // ------------------------------------------------------------------
    // V3.2：当前账本与账本管理（基线第 6 章）
    // ------------------------------------------------------------------

    /** 当前账本本地行 id（仅在 IO 线程调用）；无记录时兜底默认账本。 */
    public long currentLedgerId() {
        Long id = database.ledgerDao().getCurrentId();
        return id != null ? id : 1L;
    }

    /** 当前账本实体（仅在 IO 线程调用）。 */
    @Nullable
    public LedgerEntity currentLedger() {
        return database.ledgerDao().getCurrent();
    }

    /** 观察全部有效账本（当前账本排最前），供账本切换器与账本管理页。 */
    @NonNull
    public LiveData<List<LedgerEntity>> observeLedgers() {
        return database.ledgerDao().observeActive();
    }

    /** 观察当前账本，供首页账本名展示。 */
    @NonNull
    public LiveData<LedgerEntity> observeCurrentLedger() {
        return database.ledgerDao().observeCurrent();
    }

    /** 观察账本回收站（软删账本）。 */
    @NonNull
    public LiveData<List<LedgerEntity>> observeLedgerRecycleBin() {
        return database.ledgerDao().observeRecycleBin();
    }

    /**
     * 新建账本：本地落库（UUID 身份）+ 播种默认分类/账户 + 入队 LEDGER 与种子数据。
     * 服务端建账后会下发 canonical 种子，与本机种子经 mergedInto 去重（基线第 27 章）。
     * 创建成功后自动切换为新账本。
     */
    public void createLedger(@NonNull String name, @NonNull String description,
                             @NonNull String currency, @Nullable Callback<Long> callback) {
        io.execute(() -> {
            long id = database.runInTransaction(() -> {
                long now = System.currentTimeMillis();
                LedgerEntity ledger = new LedgerEntity();
                ledger.syncId = UUID.randomUUID().toString();
                ledger.name = name.trim();
                ledger.description = description == null ? "" : description.trim();
                ledger.currency = currency == null || currency.trim().isEmpty()
                        ? "CNY" : currency.trim();
                ledger.role = LedgerEntity.ROLE_OWNER;
                ledger.isDefault = false;
                ledger.createdAt = now;
                ledger.clientUpdatedAt = now;
                long ledgerId = database.ledgerDao().insert(ledger);
                seedDefaultsInto(ledgerId, now);
                enqueueSync(SyncEntityTypes.LEDGER, ledger.syncId, false);
                database.ledgerDao().setCurrent(ledgerId);
                return ledgerId;
            });
            if (syncEnqueuer != null) {
                syncEnqueuer.notifyPendingChanges();
            }
            post(callback, id);
        });
    }

    /** 向指定账本播种默认分类/账户（新建账本与清空重置共用，基线第 27 章）。 */
    private void seedDefaultsInto(long ledgerId, long now) {
        List<CategoryEntity> categories = DefaultData.defaultCategories();
        for (CategoryEntity category : categories) {
            category.id = 0;
            category.ledgerId = ledgerId;
            SyncPayloadMapper.ensureSyncId(category);
            category.version = 0;
            category.serverReceivedAt = 0;
            category.isDeleted = false;
            database.categoryDao().insert(category);
            enqueueSync(SyncEntityTypes.CATEGORY, category.syncId, false);
        }
        for (AccountEntity account : DefaultData.defaultAccounts()) {
            account.id = 0;
            account.ledgerId = ledgerId;
            account.createdAt = now;
            account.updatedAt = now;
            SyncPayloadMapper.ensureSyncId(account);
            account.version = 0;
            account.serverReceivedAt = 0;
            account.isDeleted = false;
            database.accountDao().insert(account);
            enqueueSync(SyncEntityTypes.ACCOUNT, account.syncId, false);
        }
    }

    /** 切换账本：翻转 is_current 标志；业务 LiveData 自动重载，无残留（基线第 6.3 章）。 */
    public void switchLedger(long ledgerId, @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            database.runInTransaction(() -> database.ledgerDao().setCurrent(ledgerId));
            post(callback, Boolean.TRUE);
        });
    }

    /** 重命名账本（账本设置，OWNER/ADMIN；服务端会再校验角色）。 */
    public void renameLedger(long ledgerId, @NonNull String newName,
                             @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            database.runInTransaction(() -> {
                LedgerEntity ledger = database.ledgerDao().getById(ledgerId);
                if (ledger != null) {
                    ledger.name = newName.trim();
                    ledger.clientUpdatedAt = System.currentTimeMillis();
                    database.ledgerDao().update(ledger);
                    enqueueSync(SyncEntityTypes.LEDGER, ledger.syncId, ledger.isDeleted);
                }
            });
            post(callback, Boolean.TRUE);
        });
    }

    /**
     * 删除账本（软删墓碑，仅 OWNER；服务端再校验）。删除后自动切到剩余的第一个账本。
     * 影响所有成员（基线第 12.2 章），确认弹窗由 UI 层负责。
     */
    public void deleteLedger(long ledgerId, @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            database.runInTransaction(() -> {
                long now = System.currentTimeMillis();
                LedgerEntity ledger = database.ledgerDao().getById(ledgerId);
                if (ledger != null && !ledger.isDeleted) {
                    ledger.isDeleted = true;
                    ledger.deletedAt = now;
                    ledger.clientUpdatedAt = now;
                    database.ledgerDao().update(ledger);
                    enqueueSync(SyncEntityTypes.LEDGER, ledger.syncId, true);
                }
                // 若删的是当前账本，切到剩余账本（默认账本优先）
                Long current = database.ledgerDao().getCurrentId();
                LedgerEntity currentLedger = current == null
                        ? null : database.ledgerDao().getById(current);
                if (currentLedger == null || currentLedger.isDeleted
                        || LedgerEntity.ROLE_REMOVED.equals(currentLedger.role)) {
                    List<LedgerEntity> remaining = database.ledgerDao().getActive();
                    if (!remaining.isEmpty()) {
                        database.ledgerDao().setCurrent(remaining.get(0).id);
                    }
                }
            });
            post(callback, Boolean.TRUE);
        });
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 业务日期不晚于 endDay 的全部账单，倒序。 */
    public LiveData<List<TransactionItem>> observeTransactionsUpTo(long endDay) {
        return database.transactionDao().observeUpTo(endDay);
    }

    /** [startDay, endDay] 区间内的账单，倒序。 */
    public LiveData<List<TransactionItem>> observeTransactionsBetween(long startDay, long endDay) {
        return database.transactionDao().observeBetween(startDay, endDay);
    }

    /**
     * 按筛选条件搜索账单（V2 新增，开发计划 Phase 4）。
     *
     * <p>把不可变的 {@link SearchFilter} 拆成 DAO 的可选参数；返回的 LiveData 会随底层
     * 交易表变化自动刷新，故搜索页新增 / 编辑 / 删除后结果与合计会同步更新。
     */
    public LiveData<List<TransactionItem>> searchTransactions(@NonNull SearchFilter filter) {
        return database.transactionDao().search(
                filter.keyword, filter.startDay, filter.endDay,
                filter.includeExpense, filter.includeIncome, filter.includeTransfer,
                filter.categoryId, filter.accountId, filter.minAmount, filter.maxAmount);
    }

    public LiveData<List<CategoryEntity>> observeCategories(int type) {
        return database.categoryDao().observeByType(type);
    }

    public LiveData<List<CategoryEntity>> observeAllCategories() {
        return database.categoryDao().observeAll();
    }

    public LiveData<BudgetEntity> observeBudget(int year, int month) {
        return database.budgetDao().observe(year, month);
    }

    /** 观察某月全部分类预算（category_id &gt;= 1），不含总预算（V2 Phase 6）。 */
    public LiveData<List<BudgetEntity>> observeCategoryBudgets(int year, int month) {
        return database.budgetDao().observeCategoryBudgets(year, month);
    }

    public LiveData<Long> observeSum(int type, long startDay, long endDay) {
        return database.transactionDao().observeSum(type, startDay, endDay);
    }

    public LiveData<UserSettingsEntity> observeSettings() {
        return database.userSettingsDao().observe();
    }

    public LiveData<Integer> observeTransactionCount() {
        return database.transactionDao().observeCount();
    }

    /**
     * 观察 [startDay, endDay] 区间内每天的收支摘要，供日历选择器显示每日流水。
     * V1.1 新增：聚合查询，不加载全量账单明细（V1.1 基线第 22 章）。
     */
    public LiveData<List<DailySummary>> observeDailySummaries(long startDay, long endDay) {
        return database.transactionDao().observeDailySummaries(startDay, endDay);
    }

    /**
     * 观察按自然周聚合的账单笔数（V2 Risk C：有界查询，一行 = 一个周）。
     */
    public LiveData<List<DayCount>> observeWeekCounts() {
        return database.transactionDao().observeWeekCounts();
    }

    /**
     * 观察按自然月聚合的账单笔数（V2 Risk C：一行 = 一个月）。
     */
    public LiveData<List<DayCount>> observeMonthCounts() {
        return database.transactionDao().observeMonthCounts();
    }

    /**
     * 观察按自然年聚合的账单笔数（V2 Risk C：一行 = 一年）。
     */
    public LiveData<List<DayCount>> observeYearCounts() {
        return database.transactionDao().observeYearCounts();
    }

    public LiveData<Integer> observeCategoryCount() {
        return database.categoryDao().observeCount();
    }

    public LiveData<Integer> observeBudgetCount() {
        return database.budgetDao().observeCount();
    }

    // ------------------------------------------------------------------
    // 读：账户（V2 新增）
    // ------------------------------------------------------------------

    /** 全部账户（含已归档），按 sort_order 升序，供账户管理页。 */
    public LiveData<List<AccountEntity>> observeAccounts() {
        return database.accountDao().observeAll();
    }

    /** 未归档账户，供记账 / 转账账户选择器。 */
    public LiveData<List<AccountEntity>> observeActiveAccounts() {
        return database.accountDao().observeActive();
    }

    /** 全部账户余额（联表重算），供图表页「账户资金」卡片。 */
    public LiveData<List<AccountBalance>> observeAccountBalances() {
        return database.accountDao().observeAccountBalances();
    }

    /** 未归档账户余额（联表重算），用于账户总余额等只统计活跃账户的场景。 */
    public LiveData<List<AccountBalance>> observeActiveAccountBalances() {
        return database.accountDao().observeActiveAccountBalances();
    }

    /** 观察单个账户，供账户流水详情页（V2 Phase 9）。 */
    public LiveData<AccountEntity> observeAccount(long id) {
        return database.accountDao().observeById(id);
    }

    /** 观察某账户的全部流水（含转出 / 转入），供账户流水详情页（V2 Phase 9）。 */
    public LiveData<List<TransactionItem>> observeAccountTransactions(long accountId) {
        return database.transactionDao().observeForAccount(accountId);
    }

    public LiveData<Integer> observeAccountCount() {
        return database.accountDao().observeCount();
    }

    public LiveData<Integer> observeRecurringCount() {
        return database.recurringTransactionDao().observeCount();
    }

    /** 读取单个账户用于编辑，不存在时回调 null。 */
    public void loadAccount(long id, @Nullable Callback<AccountEntity> callback) {
        io.execute(() -> post(callback, database.accountDao().getById(id)));
    }

    /** 首个未归档账户 id，记账默认落账账户；无账户时回调 null。 */
    public void firstActiveAccountId(@Nullable Callback<Long> callback) {
        io.execute(() -> post(callback, database.accountDao().firstActiveAccountId()));
    }

    // ------------------------------------------------------------------
    // V2.1：历史账单账户归属（基线第 11–12 章）
    // ------------------------------------------------------------------

    /** 未归属历史账单（V1 迁移数据，account_id IS NULL）数量，供账户管理页的归属提示。 */
    public LiveData<Integer> observeUnassignedCount() {
        return database.transactionDao().observeUnassignedCount();
    }

    /**
     * 把全部未归属历史账单批量归属到指定账户（基线第 12 章：不自动猜测、必须用户确认）。
     *
     * <p>单事务内完成：批量 UPDATE → 重算全部账户的余额缓存。归属后这些账单计入
     * 目标账户余额，总收入 / 总支出统计不变（它们本来就参与全局统计）。
     * 回调返回归属的账单笔数。
     */
    public void assignUnassignedTransactions(long accountId,
                                             @Nullable Callback<Integer> callback) {
        io.execute(() -> {
            int assigned = database.runInTransaction(() -> {
                long now = System.currentTimeMillis();
                // 先取待归属集合（含 syncId），归属后逐笔入队
                List<TransactionEntity> unassigned = database.transactionDao()
                        .getUnassignedEntities();
                int count = database.transactionDao().assignUnassigned(accountId, now);
                for (TransactionEntity transaction : unassigned) {
                    enqueueSync(SyncEntityTypes.TRANSACTION, transaction.syncId, false);
                }
                Set<Long> allAccountIds = new LinkedHashSet<>();
                for (AccountEntity account : database.accountDao().getAll()) {
                    allAccountIds.add(account.id);
                }
                recalcAccounts(allAccountIds, now);
                return count;
            });
            post(callback, assigned);
        });
    }

    // ------------------------------------------------------------------
    // 写：交易
    // ------------------------------------------------------------------

    /**
     * 触发建库并写入系统默认分类。App 启动时在 IO 线程调用。
     */
    public void warmUp() {
        io.execute(() -> database.categoryDao().countByType(CategoryEntity.TYPE_EXPENSE));
    }

    /**
     * id 为 0 时插入，否则更新。回调返回记录 id。
     *
     * <p>更新时先回读库里的 {@code createdAt}，因为 {@code @Update} 会整行覆盖，
     * 而编辑表单并不展示创建时间，直接写回会把该字段清零。
     *
     * <p>V2：插入 / 更新与受影响账户的余额重算包在同一 DB 事务内，保证
     * “交易写入 + 账户缓存更新”原子生效；转账同时重算转出 / 转入两账户。
     */
    public void saveTransaction(@NonNull TransactionEntity entity, @Nullable Callback<Long> callback) {
        io.execute(() -> {
            long id = database.runInTransaction(() -> {
                long now = System.currentTimeMillis();
                Set<Long> affected = new LinkedHashSet<>();
                if (entity.id == 0L) {
                    entity.createdAt = now;
                    entity.updatedAt = now;
                    // V3：入库即持有跨设备身份，version=0 表示从未与服务器同步
                    SyncPayloadMapper.ensureSyncId(entity);
                    entity.version = 0;
                    entity.serverReceivedAt = 0;
                    entity.isDeleted = false;
                    // V3.2：新交易归属当前账本（基线第 6 章）
                    entity.ledgerId = currentLedgerId();
                    entity.id = database.transactionDao().insert(entity);
                } else {
                    TransactionEntity existing = database.transactionDao().getEntityById(entity.id);
                    entity.createdAt = existing != null ? existing.createdAt : now;
                    entity.updatedAt = now;
                    // V3：回填同步元数据（UI 半实体不覆写身份 / 版本）
                    carryTransactionMetadata(entity, existing);
                    // 旧账户也需重算（编辑时可能改了账户 / 类型 / 金额）。
                    if (existing != null) {
                        collectAccount(affected, existing.accountId);
                        collectAccount(affected, existing.transferAccountId);
                    }
                    database.transactionDao().update(entity);
                }
                collectAccount(affected, entity.accountId);
                collectAccount(affected, entity.transferAccountId);
                recalcAccounts(affected, now);
                enqueueSync(SyncEntityTypes.TRANSACTION, entity.syncId, entity.isDeleted);
                return entity.id;
            });
            post(callback, id);
        });
    }

    /** 读取单笔账单用于编辑，不存在时回调 null。 */
    public void loadTransaction(long id, @Nullable Callback<TransactionItem> callback) {
        io.execute(() -> post(callback, database.transactionDao().getById(id)));
    }

    /**
     * 删除账单。V3 改为 Soft Delete（基线第 17 章）：置 is_deleted 后更新，
     * 让「删除」本身成为可同步事件；余额聚合已排除软删行，缓存照常重算。
     */
    public void deleteTransaction(long id, @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            database.runInTransaction(() -> {
                long now = System.currentTimeMillis();
                TransactionEntity existing = database.transactionDao().getEntityById(id);
                Set<Long> affected = new LinkedHashSet<>();
                if (existing != null) {
                    collectAccount(affected, existing.accountId);
                    collectAccount(affected, existing.transferAccountId);
                    existing.isDeleted = true;
                    existing.deletedAt = now; // V3.1 回收站展示时间，随载荷传播
                    existing.updatedAt = now;
                    database.transactionDao().update(existing);
                    enqueueSync(SyncEntityTypes.TRANSACTION, existing.syncId, true);
                }
                recalcAccounts(affected, now);
            });
            post(callback, Boolean.TRUE);
        });
    }

    /** 收集需重算的账户 id，跳过 null 与 0（历史账单 / 未选账户）。 */
    private static void collectAccount(@NonNull Set<Long> target, @Nullable Long accountId) {
        if (accountId != null && accountId != 0L) {
            target.add(accountId);
        }
    }

    /** 从交易重算并重写受影响账户的余额缓存列（调用方需处于 DB 事务内）。 */
    private void recalcAccounts(@NonNull Collection<Long> accountIds, long now) {
        for (Long accountId : accountIds) {
            long balance = balanceUseCase.calculate(accountId);
            database.accountDao().updateBalance(accountId, balance, now);
        }
    }

    // ------------------------------------------------------------------
    // CSV 导入 / 导出（V2 新增，开发计划 Phase 5）
    //
    // 导出 / 导入是一次性批处理，需要「同步读全量 + 单事务批量写」。为保证与其他写操作
    // 串行、不与单线程 IO 执行器竞争数据库，domain 用例一律通过 {@link #runOnIo} 复用同一条
    // IO 线程，在其中调用下面的同步读 / 批量写方法。
    // ------------------------------------------------------------------

    /**
     * 在仓库的单线程 IO 执行器上运行一次性任务（导出 / 导入用）。
     *
     * <p>复用同一条 IO 线程，确保导入批量写与其他交易写不会并发访问数据库。
     */
    public void runOnIo(@NonNull Runnable task) {
        io.execute(task);
    }

    /** 全量导出行（含分类 / 账户名与创建 / 更新时间戳），按日期升序。仅在 IO 线程调用。 */
    @NonNull
    public List<TransactionExport> readExportRows() {
        return database.transactionDao().exportAll();
    }

    /** 全量分类（含支出与收入），供导入时按「类型 + 名称」解析分类 id。仅在 IO 线程调用。 */
    @NonNull
    public List<CategoryEntity> readAllCategories() {
        return database.categoryDao().getAll();
    }

    /** 全量账户（含已归档），供导入时按名称解析账户 id。仅在 IO 线程调用。 */
    @NonNull
    public List<AccountEntity> readAllAccounts() {
        return database.accountDao().getAll();
    }

    /** 全量交易实体，供导入时构建「疑似重复」指纹集合。仅在 IO 线程调用。 */
    @NonNull
    public List<TransactionEntity> readAllTransactionEntities() {
        return database.transactionDao().getAllEntities();
    }

    // ------------------------------------------------------------------
    // 本地备份 / 恢复（V2 新增，开发计划 Phase 7）
    //
    // 与 CSV 导入导出同范式：备份 / 恢复是一次性批处理，同步读全量 / 单事务整体写，
    // domain 用例经 {@link #runOnIo} 复用同一条 IO 线程调用下面的同步方法。
    // ------------------------------------------------------------------

    /** 全量预算（含总预算哨兵与分类预算），供备份序列化。仅在 IO 线程调用。 */
    @NonNull
    public List<BudgetEntity> readAllBudgets() {
        return database.budgetDao().getAll();
    }

    /** 全量周期账单，供备份序列化。仅在 IO 线程调用。 */
    @NonNull
    public List<RecurringTransactionEntity> readAllRecurring() {
        return database.recurringTransactionDao().getAll();
    }

    /** 本地设置单例；从未写过设置时为 null。仅在 IO 线程调用。 */
    @Nullable
    public UserSettingsEntity readSettings() {
        return database.userSettingsDao().get();
    }

    /**
     * 覆盖恢复：在单个 DB 事务内清空各表 → 按备份数据重插（保留原 id）→
     * 重算全部账户的余额缓存列。统计与列表经 LiveData 自动刷新。
     *
     * <p>插入顺序满足外键约束（账户 / 分类先于交易）；任一步失败（如备份数据跨表
     * 引用失效触发外键校验）整体回滚并抛出运行时异常，当前数据不受影响。
     * 余额缓存列是派生数据，恢复后一律从交易重算，不信任备份里的缓存值。
     * 仅在 IO 线程调用（{@link #runOnIo} 内）。
     */
    public void replaceAllData(@NonNull List<AccountEntity> accounts,
                               @NonNull List<CategoryEntity> categories,
                               @NonNull List<TransactionEntity> transactions,
                               @NonNull List<BudgetEntity> budgets,
                               @NonNull List<RecurringTransactionEntity> recurring,
                               @Nullable UserSettingsEntity settings) {
        database.runInTransaction(() -> {
            // V3.2：本地备份恢复 = 覆盖「当前账本」的数据集，其他账本不受影响
            long ledgerId = currentLedgerId();
            // 先删交易再删账户 / 分类以满足外键约束，与 clearAllData 同序
            database.transactionDao().clearCurrentLedger();
            database.recurringTransactionDao().clearCurrentLedger();
            database.budgetDao().clearCurrentLedger();
            database.accountDao().clearCurrentLedger();
            database.categoryDao().clearCurrentLedger();
            database.userSettingsDao().deleteAll();

            // V3：恢复行保留备份中的 syncId（身份连续，云端 LWW 收敛）；
            // 旧备份缺 syncId 时补发；version/serverReceivedAt 归零后全量重推
            for (AccountEntity account : accounts) {
                SyncPayloadMapper.ensureSyncId(account);
                account.version = 0;
                account.serverReceivedAt = 0;
                account.isDeleted = false;
                account.ledgerId = ledgerId;
                database.accountDao().insert(account);
                enqueueSync(SyncEntityTypes.ACCOUNT, account.syncId, false);
            }
            for (CategoryEntity category : categories) {
                SyncPayloadMapper.ensureSyncId(category);
                category.version = 0;
                category.serverReceivedAt = 0;
                category.isDeleted = false;
                category.ledgerId = ledgerId;
                database.categoryDao().insert(category);
                enqueueSync(SyncEntityTypes.CATEGORY, category.syncId, false);
            }
            for (TransactionEntity transaction : transactions) {
                SyncPayloadMapper.ensureSyncId(transaction);
                transaction.version = 0;
                transaction.serverReceivedAt = 0;
                transaction.isDeleted = false;
                transaction.ledgerId = ledgerId;
                database.transactionDao().insert(transaction);
                enqueueSync(SyncEntityTypes.TRANSACTION, transaction.syncId, false);
            }
            for (BudgetEntity budget : budgets) {
                SyncPayloadMapper.ensureSyncId(budget);
                budget.version = 0;
                budget.serverReceivedAt = 0;
                budget.isDeleted = false;
                budget.ledgerId = ledgerId;
                database.budgetDao().upsert(budget);
                enqueueSync(SyncEntityTypes.BUDGET, budget.syncId, false);
            }
            for (RecurringTransactionEntity item : recurring) {
                SyncPayloadMapper.ensureSyncId(item);
                item.version = 0;
                item.serverReceivedAt = 0;
                item.isDeleted = false;
                item.ledgerId = ledgerId;
                database.recurringTransactionDao().insert(item);
                enqueueSync(SyncEntityTypes.RECURRING, item.syncId, false);
            }
            if (settings != null) {
                database.userSettingsDao().upsert(settings);
            }

            // 余额缓存从交易重算，保证缓存与「唯一真值来源」一致
            long now = System.currentTimeMillis();
            for (AccountEntity account : accounts) {
                database.accountDao().updateBalance(account.id,
                        balanceUseCase.calculate(account.id), now);
            }
        });
        if (syncEnqueuer != null) {
            syncEnqueuer.notifyPendingChanges();
        }
    }

    /**
     * 批量导入：在单个 DB 事务内插入全部有效行，并重算所有受影响账户的余额缓存。
     *
     * <p>导入行的 {@code id} 一律清零走自增，避免与库中既有 id 冲突；{@code createdAt / updatedAt}
     * 若 CSV 未提供（为 0）则回落到当前时间。要么全部成功、要么整体回滚，返回实际插入行数。
     * 仅在 IO 线程调用（{@link #runOnIo} 内）。
     */
    public int insertImportedTransactions(@NonNull List<TransactionEntity> entities) {
        if (entities.isEmpty()) {
            return 0;
        }
        return database.runInTransaction(() -> {
            long now = System.currentTimeMillis();
            long ledgerId = currentLedgerId();
            Set<Long> affected = new LinkedHashSet<>();
            for (TransactionEntity entity : entities) {
                entity.id = 0L;
                // V3.2：导入明确归属当前账本（基线第 26 章，禁止跨账本不明归属）
                entity.ledgerId = ledgerId;
                if (entity.createdAt == 0L) {
                    entity.createdAt = now;
                }
                if (entity.updatedAt == 0L) {
                    entity.updatedAt = now;
                }
                database.transactionDao().insert(entity);
                collectAccount(affected, entity.accountId);
                collectAccount(affected, entity.transferAccountId);
            }
            recalcAccounts(affected, now);
            return entities.size();
        });
    }

    // ------------------------------------------------------------------
    // 写：账户（V2 新增）
    // ------------------------------------------------------------------

    /**
     * 保存账户。id 为 0 时插入（sortOrder 接在末尾，balance 缓存 = 初始余额），
     * 否则更新；更新初始余额后在同一事务内重算 balance 缓存。
     */
    public void saveAccount(@NonNull AccountEntity entity, @Nullable Callback<Long> callback) {
        io.execute(() -> {
            long now = System.currentTimeMillis();
            long id = database.runInTransaction(() -> {
                if (entity.id == 0L) {
                    entity.sortOrder = database.accountDao().maxSortOrder() + 1;
                    entity.createdAt = now;
                    entity.updatedAt = now;
                    // 新账户还没有任何交易，余额缓存即初始余额。
                    entity.balance = entity.initialBalance;
                    SyncPayloadMapper.ensureSyncId(entity);
                    entity.version = 0;
                    entity.serverReceivedAt = 0;
                    entity.isDeleted = false;
                    entity.ledgerId = currentLedgerId();
                    long newId = database.accountDao().insert(entity);
                    enqueueSync(SyncEntityTypes.ACCOUNT, entity.syncId, false);
                    return newId;
                }
                AccountEntity existing = database.accountDao().getById(entity.id);
                entity.createdAt = existing != null ? existing.createdAt : now;
                entity.updatedAt = now;
                entity.isArchived = existing != null ? existing.isArchived : entity.isArchived;
                carryAccountMetadata(entity, existing);
                database.accountDao().update(entity);
                enqueueSync(SyncEntityTypes.ACCOUNT, entity.syncId, entity.isDeleted);
                // 初始余额可能改了，从交易重算后对齐缓存。
                long balance = balanceUseCase.calculate(entity.id);
                database.accountDao().updateBalance(entity.id, balance, now);
                return entity.id;
            });
            post(callback, id);
        });
    }

    /** 归档 / 取消归档账户（不物理删除），被账单引用的账户只能归档。V3 入队同步。 */
    public void setAccountArchived(long id, boolean archived, @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            database.runInTransaction(() -> {
                AccountEntity entity = database.accountDao().getById(id);
                if (entity != null) {
                    entity.isArchived = archived;
                    entity.updatedAt = System.currentTimeMillis();
                    database.accountDao().update(entity);
                    enqueueSync(SyncEntityTypes.ACCOUNT, entity.syncId, entity.isDeleted);
                }
            });
            post(callback, Boolean.TRUE);
        });
    }

    /**
     * 删除账户。V2：已被账单（含转出 / 转入）引用的账户禁止删除，守卫放在仓库层。
     * V3：未引用账户的删除也改为 Soft Delete（基线第 17 章），删除可跨设备传播。
     */
    public void deleteAccount(long id, @Nullable Callback<DeleteAccountResult> callback) {
        io.execute(() -> {
            int used = database.accountDao().countTransactionsByAccount(id);
            if (used > 0) {
                post(callback, DeleteAccountResult.blocked(used));
                return;
            }
            database.runInTransaction(() -> {
                AccountEntity entity = database.accountDao().getById(id);
                if (entity != null) {
                    long now = System.currentTimeMillis();
                    entity.isDeleted = true;
                    entity.deletedAt = now;
                    entity.updatedAt = now;
                    database.accountDao().update(entity);
                    enqueueSync(SyncEntityTypes.ACCOUNT, entity.syncId, true);
                }
            });
            post(callback, DeleteAccountResult.ok());
        });
    }

    // ------------------------------------------------------------------
    // 周期账单（V2 新增，开发计划 Phase 8）
    // ------------------------------------------------------------------

    /** 全部周期账单规则（启用在前、到期日升序），供管理页。 */
    public LiveData<List<RecurringTransactionEntity>> observeRecurring() {
        return database.recurringTransactionDao().observeAll();
    }

    /** 到期未确认的规则（{@code next_run_date <= today 且 is_enabled}）。 */
    public LiveData<List<RecurringTransactionEntity>> observeDueRecurring(long today) {
        return database.recurringTransactionDao().observeDue(today);
    }

    /** 到期未确认的规则数，供首页「有 N 笔周期账单待记账」提示。 */
    public LiveData<Integer> observeDueRecurringCount(long today) {
        return database.recurringTransactionDao().observeDueCount(today);
    }

    /** 读取单条规则用于编辑，不存在时回调 null。 */
    public void loadRecurring(long id, @Nullable Callback<RecurringTransactionEntity> callback) {
        io.execute(() -> post(callback, database.recurringTransactionDao().getById(id)));
    }

    /**
     * 保存周期账单规则。id 为 0 时插入，{@code nextRunDate} 初始化为开始日期；
     * 更新时仅当调度字段（开始日期 / 频率 / 间隔）变化才重置 {@code nextRunDate}，
     * 否则原样保留——它是「已生成到哪一期」的幂等标记，乱动会导致重复生成。
     *
     * <p>V2.1：锚点日统一取「开始日期的 day-of-month」（用户最初选择的日期），
     * 新建与改期都重算；月 / 年推进时每次从锚点重推，不再继承被夹取的日期。
     */
    public void saveRecurring(@NonNull RecurringTransactionEntity entity,
                              @Nullable Callback<Long> callback) {
        io.execute(() -> {
            long now = System.currentTimeMillis();
            long id = database.runInTransaction(() -> {
                entity.anchorDayOfMonth = DateUtil.dayOfMonthOf(entity.startDate);
                if (entity.id == 0L) {
                    entity.createdAt = now;
                    entity.updatedAt = now;
                    entity.nextRunDate = entity.startDate;
                    SyncPayloadMapper.ensureSyncId(entity);
                    entity.version = 0;
                    entity.serverReceivedAt = 0;
                    entity.isDeleted = false;
                    entity.ledgerId = currentLedgerId();
                    long newId = database.recurringTransactionDao().insert(entity);
                    enqueueSync(SyncEntityTypes.RECURRING, entity.syncId, false);
                    return newId;
                }
                RecurringTransactionEntity existing =
                        database.recurringTransactionDao().getById(entity.id);
                entity.createdAt = existing != null ? existing.createdAt : now;
                carryRecurringMetadata(entity, existing);
                boolean scheduleChanged = existing == null
                        || existing.startDate != entity.startDate
                        || existing.frequency != entity.frequency
                        || existing.interval != entity.interval;
                if (scheduleChanged) {
                    entity.nextRunDate = entity.startDate;
                } else if (existing != null) {
                    entity.nextRunDate = existing.nextRunDate;
                }
                database.recurringTransactionDao().update(entity);
                enqueueSync(SyncEntityTypes.RECURRING, entity.syncId, entity.isDeleted);
                return entity.id;
            });
            post(callback, id);
        });
    }

    /** 删除规则。V3 改为 Soft Delete；已按它生成的历史账单不受影响。 */
    public void deleteRecurring(long id, @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            database.runInTransaction(() -> {
                RecurringTransactionEntity entity =
                        database.recurringTransactionDao().getById(id);
                if (entity != null) {
                    long now = System.currentTimeMillis();
                    entity.isDeleted = true;
                    entity.deletedAt = now;
                    entity.isEnabled = false;
                    entity.updatedAt = now;
                    database.recurringTransactionDao().update(entity);
                    enqueueSync(SyncEntityTypes.RECURRING, entity.syncId, true);
                }
            });
            post(callback, Boolean.TRUE);
        });
    }

    /** 停用 / 重新启用规则；停用后不再进入到期列表。 */
    public void setRecurringEnabled(long id, boolean enabled,
                                    @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            database.runInTransaction(() -> {
                RecurringTransactionEntity existing =
                        database.recurringTransactionDao().getById(id);
                if (existing != null) {
                    existing.isEnabled = enabled;
                    existing.updatedAt = System.currentTimeMillis();
                    database.recurringTransactionDao().update(existing);
                    enqueueSync(SyncEntityTypes.RECURRING, existing.syncId, existing.isDeleted);
                }
            });
            post(callback, Boolean.TRUE);
        });
    }

    /**
     * 一键确认到期规则（同步版本，仅在 IO 线程调用）。
     *
     * <p>在单个 DB 事务内：对每条到期规则枚举全部到期 occurrence（含补生成），
     * 逐期插入交易（date = 期日、time = 确认时刻），幂等推进 {@code nextRunDate}；
     * 已越过结束日期的规则随之停用；最后重算受影响账户的余额缓存。
     * 返回实际生成的交易笔数。
     */
    public int confirmDueRecurringSync(long today) {
        return database.runInTransaction(() -> {
            List<RecurringTransactionEntity> due =
                    database.recurringTransactionDao().getDue(today);
            long now = System.currentTimeMillis();
            Set<Long> affected = new LinkedHashSet<>();
            int created = 0;
            for (RecurringTransactionEntity rule : due) {
                created += generateForRule(rule, today, now, affected);
            }
            recalcAccounts(affected, now);
            return created;
        });
    }

    /** 一键确认到期规则，主线程回调生成的交易笔数。 */
    public void confirmDueRecurring(long today, @Nullable Callback<Integer> callback) {
        io.execute(() -> post(callback, confirmDueRecurringSync(today)));
    }

    /** 为单条规则生成全部到期交易并推进 / 停用规则（调用方需处于 DB 事务内）。 */
    private int generateForRule(@NonNull RecurringTransactionEntity rule, long today, long now,
                                @NonNull Set<Long> affectedAccounts) {
        List<Long> dueDates = GenerateRecurringTransactionsUseCase.collectDueDates(
                rule.anchorDayOfMonth, rule.nextRunDate, today, rule.endDate,
                rule.frequency, rule.interval);
        for (long date : dueDates) {
            TransactionEntity transaction = new TransactionEntity();
            transaction.type = rule.type;
            transaction.amount = rule.amount;
            transaction.categoryId = rule.categoryId;
            transaction.accountId = rule.accountId;
            transaction.transferAccountId = null;
            transaction.date = date;
            transaction.time = DateUtil.formatHourMinuteOf(now);
            transaction.note = rule.note;
            transaction.createdAt = now;
            transaction.updatedAt = now;
            transaction.ledgerId = rule.ledgerId;
            SyncPayloadMapper.ensureSyncId(transaction);
            transaction.version = 0;
            transaction.serverReceivedAt = 0;
            transaction.isDeleted = false;
            database.transactionDao().insert(transaction);
            enqueueSync(SyncEntityTypes.TRANSACTION, transaction.syncId, false);
            collectAccount(affectedAccounts, transaction.accountId);
        }
        if (!dueDates.isEmpty()) {
            long last = dueDates.get(dueDates.size() - 1);
            rule.nextRunDate = GenerateRecurringTransactionsUseCase.nextAfter(
                    rule.anchorDayOfMonth, last, rule.frequency, rule.interval);
        }
        if (GenerateRecurringTransactionsUseCase.isBeyondEndDate(rule.nextRunDate, rule.endDate)) {
            // 规则已到结束日期：停用以免永远停留在「待确认」
            rule.isEnabled = false;
        }
        rule.updatedAt = now;
        database.recurringTransactionDao().update(rule);
        enqueueSync(SyncEntityTypes.RECURRING, rule.syncId, rule.isDeleted);
        return dueDates.size();
    }

    /**
     * 上下移动账户排序（V2 Phase 9 P2，复用 {@link #moveCategory} 的范式）。
     * 先把 sortOrder 规整为 1..n，再按位置交换，历史重复序号也能得到确定结果。
     *
     * @param direction -1 上移，1 下移
     */
    public void moveAccount(long id, int direction, @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            AccountEntity target = database.accountDao().getById(id);
            if (target == null) {
                post(callback, Boolean.FALSE);
                return;
            }
            List<AccountEntity> all = database.accountDao().getAll();
            int index = indexOfAccount(all, id);
            int swapIndex = index + direction;
            if (index < 0 || swapIndex < 0 || swapIndex >= all.size()) {
                post(callback, Boolean.FALSE);
                return;
            }
            Collections.swap(all, index, swapIndex);
            renumberAccounts(all);
            database.runInTransaction(() -> {
                database.accountDao().updateAll(all);
                for (AccountEntity entity : all) {
                    enqueueSync(SyncEntityTypes.ACCOUNT, entity.syncId, entity.isDeleted);
                }
            });
            post(callback, Boolean.TRUE);
        });
    }

    private static int indexOfAccount(List<AccountEntity> list, long id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == id) {
                return i;
            }
        }
        return -1;
    }

    private static void renumberAccounts(List<AccountEntity> list) {
        for (int i = 0; i < list.size(); i++) {
            list.get(i).sortOrder = i + 1;
        }
    }

    /**
     * 余额缓存一致性校验（V2 Phase 9）：全部账户「缓存 vs 重算」，
     * 不一致时以重算纠正。主线程回调被纠正的账户数（0 = 全部一致）。
     */
    public void validateAccountBalances(@Nullable Callback<Integer> callback) {
        io.execute(() -> post(callback, balanceValidator.validateAndFixAll()));
    }

    // ------------------------------------------------------------------
    // 写：分类
    // ------------------------------------------------------------------

    public void saveCategory(@NonNull CategoryEntity entity, @Nullable Callback<Long> callback) {
        io.execute(() -> {
            long id = database.runInTransaction(() -> {
                if (entity.id == 0L) {
                    entity.sortOrder = database.categoryDao().maxSortOrder(entity.type) + 1;
                    SyncPayloadMapper.ensureSyncId(entity);
                    entity.version = 0;
                    entity.serverReceivedAt = 0;
                    entity.isDeleted = false;
                    entity.ledgerId = currentLedgerId();
                    long newId = database.categoryDao().insert(entity);
                    enqueueSync(SyncEntityTypes.CATEGORY, entity.syncId, false);
                    return newId;
                }
                CategoryEntity existing = database.categoryDao().getById(entity.id);
                if (existing != null) {
                    // 回填同步元数据（UI 半实体不覆写身份 / 版本 / 账本归属）
                    entity.syncId = existing.syncId;
                    entity.version = existing.version;
                    entity.serverReceivedAt = existing.serverReceivedAt;
                    entity.isDeleted = existing.isDeleted;
                    entity.ledgerId = existing.ledgerId;
                }
                database.categoryDao().update(entity);
                enqueueSync(SyncEntityTypes.CATEGORY, entity.syncId, entity.isDeleted);
                return entity.id;
            });
            post(callback, id);
        });
    }

    /**
     * 删除分类。V1 基线第 6 章：已被账单使用的分类禁止直接删除，避免统计数据断裂。
     * 守卫放在仓库层，不依赖界面层自觉。
     *
     * <p>V2：分类被删除时在同一事务内连带清理其所有月份的分类预算
     * （budget 表无外键，避免残留指向已删除分类的陈旧预算）。
     */
    /**
     * 删除分类。V1 基线第 6 章：已被账单使用的分类禁止直接删除。
     * V3：未引用分类的删除改为 Soft Delete，其分类预算一并软删
     * 并作为可同步事件传播（开发计划备注 9）。
     */
    public void deleteCategory(long id, @Nullable Callback<DeleteCategoryResult> callback) {
        io.execute(() -> {
            int used = database.transactionDao().countByCategory(id);
            if (used > 0) {
                post(callback, DeleteCategoryResult.blocked(used));
                return;
            }
            database.runInTransaction(() -> {
                long now = System.currentTimeMillis();
                CategoryEntity entity = database.categoryDao().getById(id);
                if (entity != null) {
                    entity.isDeleted = true;
                    entity.deletedAt = now;
                    database.categoryDao().update(entity);
                    enqueueSync(SyncEntityTypes.CATEGORY, entity.syncId, true);
                }
                for (BudgetEntity budget : database.budgetDao().getActiveByCategoryId(id)) {
                    budget.isDeleted = true;
                    budget.deletedAt = now;
                    budget.updatedAt = now;
                    database.budgetDao().upsert(budget);
                    enqueueSync(SyncEntityTypes.BUDGET, budget.syncId, true);
                }
            });
            post(callback, DeleteCategoryResult.ok());
        });
    }

    // ------------------------------------------------------------------
    // 回收站（V3.1 基线第 17-21 章）
    //
    // 恢复不是本地临时操作：is_deleted 反转 + deleted_at 清空后按 UPSERT 重新入队，
    // 作为一条新的同步写操作传播（基线第 20 章），其他设备最终一致。
    // 保留策略：永久保留，不提供自动清理与彻底删除（V3.1 决策 3）。
    // ------------------------------------------------------------------

    /** 回收站：软删交易（删除时间新→旧）。 */
    @NonNull
    public LiveData<List<TransactionEntity>> observeRecycleBinTransactions() {
        return database.transactionDao().observeRecycleBin();
    }

    /** 回收站：软删分类。 */
    @NonNull
    public LiveData<List<CategoryEntity>> observeRecycleBinCategories() {
        return database.categoryDao().observeRecycleBin();
    }

    /** 回收站：软删账户。 */
    @NonNull
    public LiveData<List<AccountEntity>> observeRecycleBinAccounts() {
        return database.accountDao().observeRecycleBin();
    }

    /** 回收站：软删周期账单。 */
    @NonNull
    public LiveData<List<RecurringTransactionEntity>> observeRecycleBinRecurring() {
        return database.recurringTransactionDao().observeRecycleBin();
    }

    /** 恢复软删交易：反转软删位、清空删除时间并入队 UPSERT，重算受影响账户余额。 */
    public void restoreTransaction(long id, @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            Boolean result = database.runInTransaction(() -> {
                long now = System.currentTimeMillis();
                TransactionEntity existing = database.transactionDao().getEntityById(id);
                if (existing == null || !existing.isDeleted) {
                    return Boolean.FALSE;
                }
                Set<Long> affected = new LinkedHashSet<>();
                collectAccount(affected, existing.accountId);
                collectAccount(affected, existing.transferAccountId);
                existing.isDeleted = false;
                existing.deletedAt = null;
                existing.updatedAt = now;
                database.transactionDao().update(existing);
                enqueueSync(SyncEntityTypes.TRANSACTION, existing.syncId, false);
                recalcAccounts(affected, now);
                return Boolean.TRUE;
            });
            post(callback, result);
        });
    }

    /** 恢复软删分类：分类删除前被禁止有账单引用，直接反转即可安全恢复。 */
    public void restoreCategory(long id, @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            Boolean result = database.runInTransaction(() -> {
                long now = System.currentTimeMillis();
                CategoryEntity entity = database.categoryDao().getById(id);
                if (entity == null || !entity.isDeleted) {
                    return Boolean.FALSE;
                }
                entity.isDeleted = false;
                entity.deletedAt = null;
                database.categoryDao().update(entity);
                enqueueSync(SyncEntityTypes.CATEGORY, entity.syncId, false);
                return Boolean.TRUE;
            });
            post(callback, result);
        });
    }

    /** 恢复软删账户：余额缓存不受影响（其下交易在删除期间未被改动）。 */
    public void restoreAccount(long id, @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            Boolean result = database.runInTransaction(() -> {
                long now = System.currentTimeMillis();
                AccountEntity entity = database.accountDao().getById(id);
                if (entity == null || !entity.isDeleted) {
                    return Boolean.FALSE;
                }
                entity.isDeleted = false;
                entity.deletedAt = null;
                entity.updatedAt = now;
                database.accountDao().update(entity);
                enqueueSync(SyncEntityTypes.ACCOUNT, entity.syncId, false);
                return Boolean.TRUE;
            });
            post(callback, result);
        });
    }

    /** 恢复软删周期账单：删除时顺带的停用（isEnabled=false）一并撤销。 */
    public void restoreRecurring(long id, @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            Boolean result = database.runInTransaction(() -> {
                long now = System.currentTimeMillis();
                RecurringTransactionEntity entity =
                        database.recurringTransactionDao().getById(id);
                if (entity == null || !entity.isDeleted) {
                    return Boolean.FALSE;
                }
                entity.isDeleted = false;
                entity.deletedAt = null;
                entity.isEnabled = true;
                entity.updatedAt = now;
                database.recurringTransactionDao().update(entity);
                enqueueSync(SyncEntityTypes.RECURRING, entity.syncId, false);
                return Boolean.TRUE;
            });
            post(callback, result);
        });
    }

    /**
     * 上下移动分类。先把该类型下的 sortOrder 规整为 1..n，再按位置交换，
     * 这样即使历史数据存在重复序号也能得到确定结果。
     *
     * @param direction -1 上移，1 下移
     */
    public void moveCategory(long id, int direction, @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            CategoryEntity target = database.categoryDao().getById(id);
            if (target == null) {
                post(callback, Boolean.FALSE);
                return;
            }
            List<CategoryEntity> all = database.categoryDao().getByType(target.type);
            int index = indexOf(all, id);
            int swapIndex = index + direction;
            if (index < 0 || swapIndex < 0 || swapIndex >= all.size()) {
                post(callback, Boolean.FALSE);
                return;
            }
            Collections.swap(all, index, swapIndex);
            renumber(all);
            database.runInTransaction(() -> {
                database.categoryDao().updateAll(all);
                for (CategoryEntity entity : all) {
                    enqueueSync(SyncEntityTypes.CATEGORY, entity.syncId, entity.isDeleted);
                }
            });
            post(callback, Boolean.TRUE);
        });
    }

    private static int indexOf(List<CategoryEntity> list, long id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == id) {
                return i;
            }
        }
        return -1;
    }

    private static void renumber(List<CategoryEntity> list) {
        for (int i = 0; i < list.size(); i++) {
            list.get(i).sortOrder = i + 1;
        }
    }

    // ------------------------------------------------------------------
    // 写：预算
    // ------------------------------------------------------------------

    /**
     * 保存某个月的总预算（V1 兼容入口，等价于 {@code categoryId = 0} 哨兵）。
     *
     * @param amountCents 预算金额（分）；小于等于 0 表示删除该月预算
     */
    public void saveBudget(int year, int month, long amountCents,
                           @Nullable Callback<Boolean> callback) {
        saveBudget(year, month, BudgetEntity.CATEGORY_TOTAL, amountCents, callback);
    }

    /**
     * 保存某个月的总预算或分类预算（V2 Phase 6）。
     *
     * <p>依赖 (year, month, category_id) 唯一索引保证每月每分类至多一条；
     * {@code categoryId = 0} 是总预算哨兵，{@code >= 1} 为分类预算。
     *
     * @param amountCents 预算金额（分）；小于等于 0 表示删除该条预算
     */
    public void saveBudget(int year, int month, int categoryId, long amountCents,
                           @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            BudgetEntity existing = database.budgetDao().get(year, month, categoryId);
            if (amountCents <= 0L) {
                if (existing != null && !existing.isDeleted) {
                    // V3：删除预算 = Soft Delete（可同步事件）
                    database.runInTransaction(() -> {
                        existing.isDeleted = true;
                        existing.updatedAt = System.currentTimeMillis();
                        database.budgetDao().upsert(existing);
                        enqueueSync(SyncEntityTypes.BUDGET, existing.syncId, true);
                    });
                }
                post(callback, Boolean.TRUE);
                return;
            }
            long now = System.currentTimeMillis();
            BudgetEntity entity = new BudgetEntity();
            entity.year = year;
            entity.month = month;
            entity.categoryId = categoryId;
            entity.amount = amountCents;
            entity.updatedAt = now;
            if (existing != null) {
                // 身份复用：软删行重建 = 解除删除（server 端 is_deleted 翻转、version+1）
                entity.id = existing.id;
                entity.createdAt = existing.createdAt;
                entity.syncId = existing.syncId;
                entity.version = existing.version;
                entity.serverReceivedAt = existing.serverReceivedAt;
                entity.isDeleted = false;
                entity.ledgerId = existing.ledgerId;
            } else {
                entity.createdAt = now;
                SyncPayloadMapper.ensureSyncId(entity);
                entity.version = 0;
                entity.serverReceivedAt = 0;
                entity.isDeleted = false;
                entity.ledgerId = currentLedgerId();
            }
            database.runInTransaction(() -> {
                database.budgetDao().upsert(entity);
                enqueueSync(SyncEntityTypes.BUDGET, entity.syncId, entity.isDeleted);
            });
            post(callback, Boolean.TRUE);
        });
    }

    public void loadBudget(int year, int month, @Nullable Callback<BudgetEntity> callback) {
        io.execute(() -> post(callback, database.budgetDao().get(year, month)));
    }

    // ------------------------------------------------------------------
    // 写：设置与数据管理
    // ------------------------------------------------------------------

    /** 保存外观主题，同时更新镜像缓存以便下次冷启动同步生效。 */
    public void setTheme(@NonNull String theme, @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            long now = System.currentTimeMillis();
            UserSettingsEntity existing = database.userSettingsDao().get();
            UserSettingsEntity entity = existing != null ? existing : new UserSettingsEntity();
            entity.id = UserSettingsEntity.SINGLETON_ID;
            entity.theme = theme;
            if (entity.createdAt == 0L) {
                entity.createdAt = now;
            }
            entity.updatedAt = now;
            database.userSettingsDao().upsert(entity);
            ThemeStore.put(appContext, theme);
            post(callback, Boolean.TRUE);
        });
    }

    /**
     * 清空数据（V1 基线第 9 章；V3.2 收紧为「清空当前账本」）。
     * 只清当前账本的交易 / 账户 / 分类 / 预算 / 周期账单并重播默认数据，
     * 其他账本与账本身份不受影响；同步队列一并清空——本地清空不作为删除事件传播
     * （云端数据保留、其他设备不受影响，见开发计划风险表 #2）。
     */
    public void clearAllData(@Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            database.runInTransaction(() -> {
                long ledgerId = currentLedgerId();
                database.transactionDao().clearCurrentLedger();
                database.recurringTransactionDao().clearCurrentLedger();
                database.budgetDao().clearCurrentLedger();
                database.accountDao().clearCurrentLedger();
                database.categoryDao().clearCurrentLedger();
                database.syncChangeQueueDao().clearAll();

                long now = System.currentTimeMillis();
                seedDefaultsInto(ledgerId, now);
            });
            post(callback, Boolean.TRUE);
        });
    }

    private <T> void post(@Nullable Callback<T> callback, T value) {
        if (callback == null) {
            return;
        }
        mainHandler.post(() -> callback.onResult(value));
    }
}
