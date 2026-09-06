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
import com.skyanchor.bookkeeping.data.entity.LedgerEntity;
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.data.entity.SyncChangeQueueEntity;
import com.skyanchor.bookkeeping.data.entity.SyncCursorEntity;
import com.skyanchor.bookkeeping.data.entity.SyncEventEntity;
import com.skyanchor.bookkeeping.data.entity.SyncStateEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.entity.UserSettingsEntity;

import java.util.List;
import java.util.UUID;

/**
 * 本地数据库。
 *
 * <p>V1 保留 4 张核心表：transactions、category、budget、user_settings。
 * V2 升级到 version 3，新增 account、recurring_transaction 两张表，并对 transactions、budget
 * 做 schema 变更，全部集中在单个 {@link #MIGRATION_2_3}（避免多次升版）。
 * V2.1 升级到 version 4，为 recurring_transaction 增加 {@code anchor_day_of_month}
 * （月 / 年周期的原始锚点日，消除月末日期漂移），见 {@link #MIGRATION_3_4}。
 * V3 升级到 version 5：5 张可同步业务表增加同步元数据（sync_id / version /
 * server_received_at / is_deleted），并新建 sync_change_queue、sync_cursor、sync_state
 * 三张同步支撑表，见 {@link #MIGRATION_4_5}。
 * V3.1 升级到 version 6：5 张业务表增加 deleted_at（回收站排序与展示）；
 * sync_state 增加诊断列；新建 sync_events 事件历史表，见 {@link #MIGRATION_5_6}。
 * V3.2 升级到 version 7：新建 ledger 表（业务根节点）并写入默认账本；5 张业务表增加
 * ledger_id（回填默认账本）；budget 唯一键升级为 (ledger_id, year, month, category_id)；
 * sync_cursor 升级为 (account_email, ledger_sync_id) 复合主键（账本级游标），见
 * {@link #MIGRATION_6_7}。
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
                RecurringTransactionEntity.class,
                LedgerEntity.class,
                SyncChangeQueueEntity.class,
                SyncCursorEntity.class,
                SyncStateEntity.class,
                SyncEventEntity.class
        },
        version = 7,
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

    public abstract LedgerDao ledgerDao();

    public abstract SyncChangeQueueDao syncChangeQueueDao();

    public abstract SyncCursorDao syncCursorDao();

    public abstract SyncStateDao syncStateDao();

    public abstract SyncEventDao syncEventDao();

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
            seedAccounts(db, 0);

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

    /**
     * V2.1 升级 3 → 4：recurring_transaction 增加原始锚点日列。
     *
     * <p>{@code anchor_day_of_month} 用于月 / 年周期「每次从原始锚点重推」：
     * 存量规则的锚点回填取「开始日期的 day-of-month」（用户最初选择的日期），
     * 而不是可能已被月末夹取过的 next_run_date——后者会把既有漂移固化成新锚点。
     * 毫秒值经 {@code strftime('%d', 毫秒/1000, 'unixepoch', 'localtime')} 取本地日。
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE recurring_transaction "
                    + "ADD COLUMN anchor_day_of_month INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE recurring_transaction "
                    + "SET anchor_day_of_month = CAST(strftime('%d', start_date / 1000, "
                    + "'unixepoch', 'localtime') AS INTEGER)");
        }
    };

    /**
     * V3 升级 4 → 5：同步基础设施。
     *
     * <p>1) 5 张可同步业务表各加 4 列同步元数据（基线第 14 章），并为存量行回填
     * UUID 身份（老数据也要能上云）；SQLite 无 UUID 函数，用 randomblob 拼装 v4 格式。
     * 2) 新建 sync_change_queue / sync_cursor / sync_state 三张同步支撑表
     * （基线第 23、26 章），建表语句与 Room 由实体推导的 schema 逐列一致。
     */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            String uuidExpr =
                    "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' "
                            + "|| substr(lower(hex(randomblob(2))),2) || '-' "
                            + "|| substr('89ab', abs(random()) % 4 + 1, 1) "
                            + "|| substr(lower(hex(randomblob(2))),2) || '-' "
                            + "|| lower(hex(randomblob(6)))";

            String[] businessTables = {
                    "transactions", "category", "account", "budget", "recurring_transaction"};
            for (String table : businessTables) {
                db.execSQL("ALTER TABLE " + table
                        + " ADD COLUMN sync_id TEXT NOT NULL DEFAULT ''");
                db.execSQL("ALTER TABLE " + table
                        + " ADD COLUMN version INTEGER NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE " + table
                        + " ADD COLUMN server_received_at INTEGER NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE " + table
                        + " ADD COLUMN is_deleted INTEGER NOT NULL DEFAULT 0");
                db.execSQL("UPDATE " + table + " SET sync_id = (" + uuidExpr + ") "
                        + "WHERE sync_id = ''");
                db.execSQL("CREATE INDEX IF NOT EXISTS index_" + table + "_sync_id "
                        + "ON " + table + "(sync_id)");
            }

            db.execSQL("CREATE TABLE IF NOT EXISTS sync_change_queue ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "entity_type TEXT NOT NULL, "
                    + "sync_id TEXT NOT NULL, "
                    + "operation TEXT NOT NULL, "
                    + "base_version INTEGER NOT NULL, "
                    + "created_at INTEGER NOT NULL, "
                    + "retry_count INTEGER NOT NULL, "
                    + "last_error TEXT, "
                    + "next_retry_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_change_queue_entity_type_sync_id "
                    + "ON sync_change_queue(entity_type, sync_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_change_queue_next_retry_at "
                    + "ON sync_change_queue(next_retry_at)");

            db.execSQL("CREATE TABLE IF NOT EXISTS sync_cursor ("
                    + "account_email TEXT NOT NULL, "
                    + "last_change_id INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL, "
                    + "PRIMARY KEY(account_email))");

            // status / last_error 在实体中无 @NonNull，Room 期望可空列（默认值由字段初始化保证）
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_state ("
                    + "id INTEGER NOT NULL, "
                    + "sync_enabled INTEGER NOT NULL, "
                    + "status TEXT, "
                    + "last_sync_at INTEGER NOT NULL, "
                    + "last_error TEXT, "
                    + "conflict_count INTEGER NOT NULL, "
                    + "PRIMARY KEY(id))");
        }
    };

    /**
     * V3.1 升级 5 → 6（基线第 18/23/25 章）：
     * <ol>
     *   <li>5 张业务表加 deleted_at（软删时间戳，回收站排序与展示、随载荷传播）；</li>
     *   <li>sync_state 加诊断列（last_push_at / last_pull_at / last_push_count /
     *       last_pull_count / last_duration_ms / recovery_epoch / bound_account_email /
     *       recovered_at）；</li>
     *   <li>新建 sync_events 同步事件历史表（保留最近 50 条）。</li>
     * </ol>
     * 存量数据全部取默认值：deleted_at 为 NULL（历史墓碑在回收站按 updated_at 展示）、
     * 诊断计数为 0、代际 0（下次同步从服务器读取真实代际）。
     */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            String[] businessTables = {
                    "transactions", "category", "account", "budget", "recurring_transaction"};
            for (String table : businessTables) {
                db.execSQL("ALTER TABLE " + table + " ADD COLUMN deleted_at INTEGER");
            }

            db.execSQL("ALTER TABLE sync_state ADD COLUMN last_push_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sync_state ADD COLUMN last_pull_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sync_state "
                    + "ADD COLUMN last_push_count INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sync_state "
                    + "ADD COLUMN last_pull_count INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sync_state "
                    + "ADD COLUMN last_duration_ms INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sync_state "
                    + "ADD COLUMN recovery_epoch INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sync_state ADD COLUMN bound_account_email TEXT");
            db.execSQL("ALTER TABLE sync_state ADD COLUMN recovered_at INTEGER NOT NULL DEFAULT 0");

            db.execSQL("CREATE TABLE IF NOT EXISTS sync_events ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "started_at INTEGER NOT NULL, "
                    + "finished_at INTEGER NOT NULL, "
                    + "result TEXT NOT NULL, "
                    + "push_count INTEGER NOT NULL, "
                    + "pull_count INTEGER NOT NULL, "
                    + "conflict_count INTEGER NOT NULL, "
                    + "duration_ms INTEGER NOT NULL, "
                    + "error_message TEXT)");
        }
    };

    /**
     * V3.2 升级 6 → 7（基线第 3、5.3 章）：Ledger 成为业务根节点。
     * <ol>
     *   <li>新建 ledger 表并写入默认账本「我的账本」（sync_id 用与 V4→5 同款 randomblob
     *       UUID，is_default=1、is_current=1）；</li>
     *   <li>5 张业务表加 ledger_id NOT NULL DEFAULT 1（存量数据全部归属默认账本）
     *       并加索引；</li>
     *   <li>budget 唯一键升级为 (ledger_id, year, month, category_id)——不同账本同月份
     *       的预算互不冲突；</li>
     *   <li>sync_cursor 重建为 (account_email, ledger_sync_id) 复合主键，存量游标迁到
     *       默认账本名下（ledger_sync_id 空串行由同步引擎在对账后重写为真实 syncId）。</li>
     * </ol>
     */
    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            String uuidExpr =
                    "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' "
                            + "|| substr(lower(hex(randomblob(2))),2) || '-' "
                            + "|| substr('89ab', abs(random()) % 4 + 1, 1) "
                            + "|| substr(lower(hex(randomblob(2))),2) || '-' "
                            + "|| lower(hex(randomblob(6)))";

            db.execSQL("CREATE TABLE IF NOT EXISTS ledger ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "sync_id TEXT NOT NULL, "
                    + "name TEXT NOT NULL, "
                    + "description TEXT NOT NULL, "
                    + "currency TEXT NOT NULL, "
                    + "role TEXT NOT NULL, "
                    + "owner_user_id INTEGER, "
                    + "is_default INTEGER NOT NULL, "
                    + "is_archived INTEGER NOT NULL, "
                    + "is_deleted INTEGER NOT NULL, "
                    + "deleted_at INTEGER, "
                    + "is_current INTEGER NOT NULL, "
                    + "version INTEGER NOT NULL, "
                    + "server_received_at INTEGER NOT NULL, "
                    + "created_at INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ledger_sync_id "
                    + "ON ledger(sync_id)");
            long now = System.currentTimeMillis();
            db.execSQL("INSERT INTO ledger (id, sync_id, name, description, currency, role, "
                    + "owner_user_id, is_default, is_archived, is_deleted, deleted_at, "
                    + "is_current, version, server_received_at, created_at, updated_at) "
                    + "VALUES (1, (" + uuidExpr + "), '我的账本', '', 'CNY', 'OWNER', "
                    + "NULL, 1, 0, 0, NULL, 1, 0, 0, " + now + ", " + now + ")");

            String[] businessTables = {
                    "transactions", "category", "account", "budget", "recurring_transaction"};
            for (String table : businessTables) {
                db.execSQL("ALTER TABLE " + table
                        + " ADD COLUMN ledger_id INTEGER NOT NULL DEFAULT 1");
                db.execSQL("CREATE INDEX IF NOT EXISTS index_" + table + "_ledger_id "
                        + "ON " + table + "(ledger_id)");
            }

            // budget 唯一键从 (year, month, category_id) 升级为 (ledger_id, ...)，
            // 仅索引变更：删旧建新即可，无需重建表。
            db.execSQL("DROP INDEX IF EXISTS index_budget_year_month_category_id");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS "
                    + "index_budget_ledger_id_year_month_category_id "
                    + "ON budget(ledger_id, year, month, category_id)");

            // 游标表升级为 (account_email, ledger_sync_id) 复合主键；存量游标挂到空串键，
            // 首次对账后由同步引擎改写为默认账本的真实 syncId。
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_cursor_new ("
                    + "account_email TEXT NOT NULL, "
                    + "ledger_sync_id TEXT NOT NULL, "
                    + "last_change_id INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL, "
                    + "PRIMARY KEY(account_email, ledger_sync_id))");
            db.execSQL("INSERT INTO sync_cursor_new (account_email, ledger_sync_id, "
                    + "last_change_id, updated_at) "
                    + "SELECT account_email, '', last_change_id, updated_at FROM sync_cursor");
            db.execSQL("DROP TABLE sync_cursor");
            db.execSQL("ALTER TABLE sync_cursor_new RENAME TO sync_cursor");
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
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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
            // V3.2：先建默认账本，默认分类/账户全部归属它（业务根节点，基线第 3.2 章）。
            long now = System.currentTimeMillis();
            ContentValues ledger = new ContentValues();
            ledger.put("id", 1L);
            ledger.put("sync_id", UUID.randomUUID().toString());
            ledger.put("name", "我的账本");
            ledger.put("description", "");
            ledger.put("currency", "CNY");
            ledger.put("role", "OWNER");
            ledger.put("is_default", 1);
            ledger.put("is_archived", 0);
            ledger.put("is_deleted", 0);
            ledger.put("is_current", 1);
            ledger.put("version", 0);
            ledger.put("server_received_at", 0);
            ledger.put("created_at", now);
            ledger.put("updated_at", now);
            db.insert("ledger", SQLiteDatabase.CONFLICT_REPLACE, ledger);
            long defaultLedgerId = 1L;

            for (CategoryEntity category : DefaultData.defaultCategories()) {
                ContentValues values = new ContentValues();
                values.put("name", category.name);
                values.put("icon", category.icon);
                values.put("type", category.type);
                values.put("sort_order", category.sortOrder);
                values.put("is_default", category.isDefault ? 1 : 0);
                values.put("ledger_id", defaultLedgerId);
                db.insert("category", SQLiteDatabase.CONFLICT_IGNORE, values);
            }

            seedAccounts(db, defaultLedgerId);

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
     * V3.2：ledgerId &gt; 0 时账户归属指定账本（建库 = 默认账本）；
     * 2→3 迁移发生在 ledger 列出现之前，传 0 表示不写该列。
     */
    private static void seedAccounts(@NonNull SupportSQLiteDatabase db, long ledgerId) {
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
            if (ledgerId > 0) {
                values.put("ledger_id", ledgerId);
            }
            db.insert("account", SQLiteDatabase.CONFLICT_IGNORE, values);
        }
    }
}
