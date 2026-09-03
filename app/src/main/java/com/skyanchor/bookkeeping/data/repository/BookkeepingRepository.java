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
import com.skyanchor.bookkeeping.domain.account.CalculateAccountBalanceUseCase;
import com.skyanchor.bookkeeping.util.Callback;
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

    public BookkeepingRepository(@NonNull Context context, @NonNull AppDatabase database) {
        this.appContext = context.getApplicationContext();
        this.database = database;
        this.balanceUseCase = new CalculateAccountBalanceUseCase(
                database.accountDao(), database.transactionDao());
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
     */
    public void deleteCategory(long id, @Nullable Callback<DeleteCategoryResult> callback) {
        io.execute(() -> {
            int used = database.transactionDao().countByCategory(id);
            if (used > 0) {
                post(callback, DeleteCategoryResult.blocked(used));
                return;
            }
            database.categoryDao().deleteById(id);
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
     * 保存某个月的总预算。
     *
     * @param amountCents 预算金额（分）；小于等于 0 表示删除该月预算
     */
    public void saveBudget(int year, int month, long amountCents,
                           @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            BudgetEntity existing = database.budgetDao().get(year, month);
            if (amountCents <= 0L) {
                if (existing != null) {
                    database.budgetDao().delete(year, month);
                }
                post(callback, Boolean.TRUE);
                return;
            }
            long now = System.currentTimeMillis();
            BudgetEntity entity = new BudgetEntity();
            entity.year = year;
            entity.month = month;
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
