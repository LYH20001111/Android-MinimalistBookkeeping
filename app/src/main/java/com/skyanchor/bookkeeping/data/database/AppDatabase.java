package com.skyanchor.bookkeeping.data.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.entity.UserSettingsEntity;

import java.util.List;

/**
 * 本地数据库。
 *
 * <p>V1 保留 4 张核心表：transactions、category、budget、user_settings。
 * V2 升级到 version 3，新增 account、recurring_transaction 两张表，并对 transactions、budget
 * 做 schema 变更，全部集中在单个 {@link #MIGRATION_2_3}（避免多次升版）。
 *
 * <p>禁止使用 destructiveMigration，否则用户已有账单数据将丢失。
 */
@Database(
        entities = {
                TransactionEntity.class,
                CategoryEntity.class,
                BudgetEntity.class,
                UserSettingsEntity.class,
                AccountEntity.class,
                RecurringTransactionEntity.class
        },
        version = 3,
        exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {

    private static final String DB_NAME = "bookkeeping.db";

    private static volatile AppDatabase instance;

    public abstract TransactionDao transactionDao();

    public abstract CategoryDao categoryDao();

    public abstract BudgetDao budgetDao();

    public abstract UserSettingsDao userSettingsDao();

    public abstract AccountDao accountDao();

    public abstract RecurringTransactionDao recurringTransactionDao();

    /**
     * V1.1 基线第 36 章：将 transactions 表的外键从 CASCADE 改为 RESTRICT，
     * 与业务层「已使用分类禁止删除」的语义保持一致。
     *
     * <p>Room 不支持直接修改外键，必须重建表。迁移步骤：
     * 关闭外键检查 → 创建新表 → 复制数据 → 删除旧表 → 重命名 → 重建索引 → 开启外键检查。
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("PRAGMA foreign_keys=OFF");
            db.execSQL("CREATE TABLE IF NOT EXISTS transactions_new ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "type INTEGER NOT NULL, "
                    + "amount INTEGER NOT NULL, "
                    + "category_id INTEGER NOT NULL, "
                    + "date INTEGER NOT NULL, "
                    + "time TEXT NOT NULL, "
                    + "note TEXT, "
                    + "created_at INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL, "
                    + "FOREIGN KEY(category_id) REFERENCES category(id) ON DELETE RESTRICT)");
            db.execSQL("INSERT INTO transactions_new SELECT * FROM transactions");
            db.execSQL("DROP TABLE transactions");
            db.execSQL("ALTER TABLE transactions_new RENAME TO transactions");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_category_id "
                    + "ON transactions(category_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_date "
                    + "ON transactions(date)");
            db.execSQL("PRAGMA foreign_keys=ON");
        }
    };

    /**
     * V2 一次性升级 2 → 3，集中全部 schema 变更：
     * <ol>
     *   <li>建 account 表并播种 6 个默认账户；</li>
     *   <li>重建 transactions 表：category_id 改可空、新增 account_id / transfer_account_id
     *       （可空 FK → account RESTRICT）并加索引；历史账单 account_id 保持 NULL；</li>
     *   <li>重建 budget 表：新增 category_id NOT NULL DEFAULT 0（总预算哨兵），
     *       唯一索引改 (year, month, category_id)；</li>
     *   <li>建 recurring_transaction 表（schema 本轮建好，逻辑 Phase 8 用）。</li>
     * </ol>
     *
     * <p>全程 {@code PRAGMA foreign_keys=OFF/ON} 包裹，沿用 {@link #MIGRATION_1_2} 的成熟范式。
     * 每步的列定义、可空性、默认值、索引与外键都必须与 Room 由实体推导出的 v3 schema 完全一致，
     * 否则开库时 Room 的 schema 校验会抛异常。
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("PRAGMA foreign_keys=OFF");

            // 1) account 表 + 播种默认账户
            db.execSQL("CREATE TABLE IF NOT EXISTS account ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "name TEXT NOT NULL, "
                    + "type INTEGER NOT NULL, "
                    + "initial_balance INTEGER NOT NULL, "
                    + "balance INTEGER NOT NULL, "
                    + "is_credit INTEGER NOT NULL, "
                    + "sort_order INTEGER NOT NULL, "
                    + "is_archived INTEGER NOT NULL, "
                    + "created_at INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL)");
            seedAccounts(db);

            // 2) 重建 transactions：category_id 可空 + account_id / transfer_account_id 可空 FK
            db.execSQL("CREATE TABLE IF NOT EXISTS transactions_new ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "type INTEGER NOT NULL, "
                    + "amount INTEGER NOT NULL, "
                    + "category_id INTEGER, "
                    + "account_id INTEGER, "
                    + "transfer_account_id INTEGER, "
                    + "date INTEGER NOT NULL, "
                    + "time TEXT NOT NULL, "
                    + "note TEXT, "
                    + "created_at INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL, "
                    + "FOREIGN KEY(category_id) REFERENCES category(id) ON DELETE RESTRICT, "
                    + "FOREIGN KEY(account_id) REFERENCES account(id) ON DELETE RESTRICT, "
                    + "FOREIGN KEY(transfer_account_id) REFERENCES account(id) ON DELETE RESTRICT)");
            // 显式列映射：历史账单 account_id / transfer_account_id 保持 NULL（早于账户体系）。
            db.execSQL("INSERT INTO transactions_new "
                    + "(id, type, amount, category_id, date, time, note, created_at, updated_at) "
                    + "SELECT id, type, amount, category_id, date, time, note, created_at, updated_at "
                    + "FROM transactions");
            db.execSQL("DROP TABLE transactions");
            db.execSQL("ALTER TABLE transactions_new RENAME TO transactions");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_category_id "
                    + "ON transactions(category_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_date "
                    + "ON transactions(date)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_account_id "
                    + "ON transactions(account_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_transfer_account_id "
                    + "ON transactions(transfer_account_id)");

            // 3) 重建 budget：category_id NOT NULL DEFAULT 0 + 新唯一索引
            db.execSQL("CREATE TABLE IF NOT EXISTS budget_new ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "year INTEGER NOT NULL, "
                    + "month INTEGER NOT NULL, "
                    + "category_id INTEGER NOT NULL DEFAULT 0, "
                    + "amount INTEGER NOT NULL, "
                    + "created_at INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL)");
            // 存量预算都是总预算，category_id 统一填哨兵 0。
            db.execSQL("INSERT INTO budget_new (id, year, month, category_id, amount, created_at, updated_at) "
                    + "SELECT id, year, month, 0, amount, created_at, updated_at FROM budget");
            db.execSQL("DROP TABLE budget");
            db.execSQL("ALTER TABLE budget_new RENAME TO budget");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budget_year_month_category_id "
                    + "ON budget(year, month, category_id)");

            // 4) recurring_transaction 表
            db.execSQL("CREATE TABLE IF NOT EXISTS recurring_transaction ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "name TEXT NOT NULL, "
                    + "type INTEGER NOT NULL, "
                    + "amount INTEGER NOT NULL, "
                    + "category_id INTEGER, "
                    + "account_id INTEGER, "
                    + "frequency INTEGER NOT NULL, "
                    + "repeat_interval INTEGER NOT NULL, "
                    + "start_date INTEGER NOT NULL, "
                    + "end_date INTEGER NOT NULL, "
                    + "next_run_date INTEGER NOT NULL, "
                    + "is_enabled INTEGER NOT NULL, "
                    + "note TEXT, "
                    + "created_at INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_transaction_next_run_date "
                    + "ON recurring_transaction(next_run_date)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_transaction_is_enabled "
                    + "ON recurring_transaction(is_enabled)");

            db.execSQL("PRAGMA foreign_keys=ON");
        }
    };

    public static AppDatabase getInstance(@NonNull Context context) {
        AppDatabase local = instance;
        if (local == null) {
            synchronized (AppDatabase.class) {
                local = instance;
                if (local == null) {
                    local = Room.databaseBuilder(
                                    context.getApplicationContext(), AppDatabase.class, DB_NAME)
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                            .addCallback(SEED_CALLBACK)
                            .build();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * 建库时同步写入系统默认分类、默认账户与本地设置单例。
     *
     * <p>这里直接使用 {@link SupportSQLiteDatabase} 而不是 DAO，保证默认数据在任何查询返回之前
     * 就已落库，也避免在开库回调里再次获取数据库造成死锁。
     */
    private static final RoomDatabase.Callback SEED_CALLBACK = new RoomDatabase.Callback() {
        @Override
        public void onCreate(@NonNull SupportSQLiteDatabase db) {
            super.onCreate(db);
            for (CategoryEntity category : DefaultData.defaultCategories()) {
                ContentValues values = new ContentValues();
                values.put("name", category.name);
                values.put("icon", category.icon);
                values.put("type", category.type);
                values.put("sort_order", category.sortOrder);
                values.put("is_default", category.isDefault ? 1 : 0);
                db.insert("category", SQLiteDatabase.CONFLICT_IGNORE, values);
            }

            seedAccounts(db);

            long now = System.currentTimeMillis();
            ContentValues settings = new ContentValues();
            settings.put("id", UserSettingsEntity.SINGLETON_ID);
            settings.put("theme", UserSettingsEntity.THEME_LIGHT);
            settings.put("first_launch", 1);
            settings.put("created_at", now);
            settings.put("updated_at", now);
            db.insert("user_settings", SQLiteDatabase.CONFLICT_REPLACE, settings);
        }
    };

    /**
     * 播种 6 个默认账户。迁移（老用户）与建库（新用户）共用，初始余额 0、balance 缓存 0。
     */
    private static void seedAccounts(@NonNull SupportSQLiteDatabase db) {
        long now = System.currentTimeMillis();
        List<AccountEntity> accounts = DefaultData.defaultAccounts();
        for (AccountEntity account : accounts) {
            ContentValues values = new ContentValues();
            values.put("name", account.name);
            values.put("type", account.type);
            values.put("initial_balance", account.initialBalance);
            values.put("balance", account.balance);
            values.put("is_credit", account.isCredit ? 1 : 0);
            values.put("sort_order", account.sortOrder);
            values.put("is_archived", account.isArchived ? 1 : 0);
            values.put("created_at", now);
            values.put("updated_at", now);
            db.insert("account", SQLiteDatabase.CONFLICT_IGNORE, values);
        }
    }
}
