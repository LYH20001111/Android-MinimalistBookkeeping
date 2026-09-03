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
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
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
import com.skyanchor.bookkeeping.util.Callback;
import com.skyanchor.bookkeeping.util.DateUtil;
import com.skyanchor.bookkeeping.util.ThemeStore;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    /** 未归档账户余额（联表重算），用于总资产等只统计活跃账户的场景。 */
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
                    entity.id = database.transactionDao().insert(entity);
                } else {
                    TransactionEntity existing = database.transactionDao().getEntityById(entity.id);
                    entity.createdAt = existing != null ? existing.createdAt : now;
                    entity.updatedAt = now;
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
     * 删除账单。V2：删除与受影响账户的余额重算包在同一 DB 事务内。
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
                }
                database.transactionDao().deleteById(id);
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
            // 先删交易再删账户 / 分类以满足外键约束，与 clearAllData 同序
            database.transactionDao().deleteAll();
            database.recurringTransactionDao().deleteAll();
            database.budgetDao().deleteAll();
            database.accountDao().deleteAll();
            database.categoryDao().deleteAll();
            database.userSettingsDao().deleteAll();

            for (AccountEntity account : accounts) {
                database.accountDao().insert(account);
            }
            for (CategoryEntity category : categories) {
                database.categoryDao().insert(category);
            }
            for (TransactionEntity transaction : transactions) {
                database.transactionDao().insert(transaction);
            }
            for (BudgetEntity budget : budgets) {
                database.budgetDao().upsert(budget);
            }
            for (RecurringTransactionEntity item : recurring) {
                database.recurringTransactionDao().insert(item);
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
            Set<Long> affected = new LinkedHashSet<>();
            for (TransactionEntity entity : entities) {
                entity.id = 0L;
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
                    return database.accountDao().insert(entity);
                }
                AccountEntity existing = database.accountDao().getById(entity.id);
                entity.createdAt = existing != null ? existing.createdAt : now;
                entity.updatedAt = now;
                entity.isArchived = existing != null ? existing.isArchived : entity.isArchived;
                database.accountDao().update(entity);
                // 初始余额可能改了，从交易重算后对齐缓存。
                long balance = balanceUseCase.calculate(entity.id);
                database.accountDao().updateBalance(entity.id, balance, now);
                return entity.id;
            });
            post(callback, id);
        });
    }

    /** 归档 / 取消归档账户（不物理删除），被账单引用的账户只能归档。 */
    public void setAccountArchived(long id, boolean archived, @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            database.accountDao().setArchived(id, archived, System.currentTimeMillis());
            post(callback, Boolean.TRUE);
        });
    }

    /**
     * 删除账户。V2：已被账单（含转出 / 转入）引用的账户禁止物理删除，
     * 守卫放在仓库层，与分类删除守卫同风格。
     */
    public void deleteAccount(long id, @Nullable Callback<DeleteAccountResult> callback) {
        io.execute(() -> {
            int used = database.accountDao().countTransactionsByAccount(id);
            if (used > 0) {
                post(callback, DeleteAccountResult.blocked(used));
                return;
            }
            database.accountDao().deleteById(id);
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
     */
    public void saveRecurring(@NonNull RecurringTransactionEntity entity,
                              @Nullable Callback<Long> callback) {
        io.execute(() -> {
            long now = System.currentTimeMillis();
            long id = database.runInTransaction(() -> {
                if (entity.id == 0L) {
                    entity.createdAt = now;
                    entity.updatedAt = now;
                    entity.nextRunDate = entity.startDate;
                    return database.recurringTransactionDao().insert(entity);
                }
                RecurringTransactionEntity existing =
                        database.recurringTransactionDao().getById(entity.id);
                entity.createdAt = existing != null ? existing.createdAt : now;
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
                return entity.id;
            });
            post(callback, id);
        });
    }

    /** 删除规则。已按它生成的历史账单不受影响。 */
    public void deleteRecurring(long id, @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            database.recurringTransactionDao().deleteById(id);
            post(callback, Boolean.TRUE);
        });
    }

    /** 停用 / 重新启用规则；停用后不再进入到期列表。 */
    public void setRecurringEnabled(long id, boolean enabled,
                                    @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            RecurringTransactionEntity existing = database.recurringTransactionDao().getById(id);
            if (existing != null) {
                existing.isEnabled = enabled;
                existing.updatedAt = System.currentTimeMillis();
                database.recurringTransactionDao().update(existing);
            }
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
                rule.nextRunDate, today, rule.endDate, rule.frequency, rule.interval);
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
            database.transactionDao().insert(transaction);
            collectAccount(affectedAccounts, transaction.accountId);
        }
        if (!dueDates.isEmpty()) {
            long last = dueDates.get(dueDates.size() - 1);
            rule.nextRunDate = GenerateRecurringTransactionsUseCase.nextAfter(
                    last, rule.frequency, rule.interval);
        }
        if (GenerateRecurringTransactionsUseCase.isBeyondEndDate(rule.nextRunDate, rule.endDate)) {
            // 规则已到结束日期：停用以免永远停留在「待确认」
            rule.isEnabled = false;
        }
        rule.updatedAt = now;
        database.recurringTransactionDao().update(rule);
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
            database.accountDao().updateAll(all);
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
            long id;
            if (entity.id == 0L) {
                entity.sortOrder = database.categoryDao().maxSortOrder(entity.type) + 1;
                id = database.categoryDao().insert(entity);
            } else {
                database.categoryDao().update(entity);
                id = entity.id;
            }
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
    public void deleteCategory(long id, @Nullable Callback<DeleteCategoryResult> callback) {
        io.execute(() -> {
            int used = database.transactionDao().countByCategory(id);
            if (used > 0) {
                post(callback, DeleteCategoryResult.blocked(used));
                return;
            }
            database.runInTransaction(() -> {
                database.categoryDao().deleteById(id);
                database.budgetDao().deleteByCategoryId(id);
            });
            post(callback, DeleteCategoryResult.ok());
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
            database.categoryDao().updateAll(all);
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
                if (existing != null) {
                    database.budgetDao().delete(year, month, categoryId);
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
                entity.id = existing.id;
                entity.createdAt = existing.createdAt;
            } else {
                entity.createdAt = now;
            }
            database.budgetDao().upsert(entity);
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
     * 清空所有本地数据（V1 基线第 9 章）。
     * 先删交易再删账户 / 分类以满足外键约束，随后重置为系统默认分类、默认账户与默认设置。
     * V2：一并清空周期账单与账户，并重建 6 个默认账户。
     */
    public void clearAllData(@Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            database.runInTransaction(() -> {
                database.transactionDao().deleteAll();
                database.recurringTransactionDao().deleteAll();
                database.budgetDao().deleteAll();
                database.accountDao().deleteAll();
                database.categoryDao().deleteAll();
                database.userSettingsDao().deleteAll();

                database.categoryDao().insertAll(DefaultData.defaultCategories());
                long now = System.currentTimeMillis();
                List<AccountEntity> accounts = DefaultData.defaultAccounts();
                for (AccountEntity account : accounts) {
                    account.createdAt = now;
                    account.updatedAt = now;
                }
                database.accountDao().insertAll(accounts);
                database.userSettingsDao().upsert(DefaultData.defaultSettings(now));
                ThemeStore.put(appContext, UserSettingsEntity.THEME_LIGHT);
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
