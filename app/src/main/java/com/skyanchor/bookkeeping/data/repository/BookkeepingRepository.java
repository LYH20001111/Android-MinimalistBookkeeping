package com.skyanchor.bookkeeping.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.skyanchor.bookkeeping.data.database.AppDatabase;
import com.skyanchor.bookkeeping.data.database.DefaultData;
import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.data.entity.UserSettingsEntity;
import com.skyanchor.bookkeeping.data.model.DeleteCategoryResult;
import com.skyanchor.bookkeeping.util.Callback;
import com.skyanchor.bookkeeping.util.ThemeStore;

import java.util.Collections;
import java.util.List;
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

    public BookkeepingRepository(@NonNull Context context, @NonNull AppDatabase database) {
        this.appContext = context.getApplicationContext();
        this.database = database;
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

    public LiveData<Integer> observeCategoryCount() {
        return database.categoryDao().observeCount();
    }

    public LiveData<Integer> observeBudgetCount() {
        return database.budgetDao().observeCount();
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
     */
    public void saveTransaction(@NonNull TransactionEntity entity, @Nullable Callback<Long> callback) {
        io.execute(() -> {
            long now = System.currentTimeMillis();
            long id;
            if (entity.id == 0L) {
                entity.createdAt = now;
                entity.updatedAt = now;
                id = database.transactionDao().insert(entity);
            } else {
                TransactionEntity existing = database.transactionDao().getEntityById(entity.id);
                entity.createdAt = existing != null ? existing.createdAt : now;
                entity.updatedAt = now;
                database.transactionDao().update(entity);
                id = entity.id;
            }
            post(callback, id);
        });
    }

    /** 读取单笔账单用于编辑，不存在时回调 null。 */
    public void loadTransaction(long id, @Nullable Callback<TransactionItem> callback) {
        io.execute(() -> post(callback, database.transactionDao().getById(id)));
    }

    public void deleteTransaction(long id, @Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            database.transactionDao().deleteById(id);
            post(callback, Boolean.TRUE);
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
     * 先删交易再删分类以满足外键约束，随后重置为系统默认分类与默认设置。
     */
    public void clearAllData(@Nullable Callback<Boolean> callback) {
        io.execute(() -> {
            database.transactionDao().deleteAll();
            database.budgetDao().deleteAll();
            database.categoryDao().deleteAll();
            database.userSettingsDao().deleteAll();

            database.categoryDao().insertAll(DefaultData.defaultCategories());
            long now = System.currentTimeMillis();
            database.userSettingsDao().upsert(DefaultData.defaultSettings(now));
            ThemeStore.put(appContext, UserSettingsEntity.THEME_LIGHT);
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
