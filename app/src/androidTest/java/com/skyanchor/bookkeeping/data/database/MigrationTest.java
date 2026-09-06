package com.skyanchor.bookkeeping.data.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteConstraintException;

import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.util.DateUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * 迁移测试（V2 开发计划 Phase 10，V2.1 Phase 3 扩展 3→4）。
 *
 * <p>v2 / v3 schema 当年未全部导出 JSON，因此这里用框架 SQLite 按对应版本实体结构手工搭建
 * 旧库（DDL 与对应 Migration 的产物一致），再交给 Room 打开：Room 会逐级执行
 * {@code MIGRATION_2_3}、{@code MIGRATION_3_4}，并在开库时把迁移结果与导出的最新 schema
 * （4.json）逐项比对，列、可空性、默认值、索引、外键任一不符即抛异常——这是最强的一层结构断言。
 *
 * <p>数据场景覆盖计划要求的五种：空库、含账单、含分类、含预算、大量账单；
 * 另有 V2.1 专项：存量周期账单的锚点日从 start_date 的日回填。
 * 断言默认账户播种、budget 哨兵 0、历史账单 account_id 为 NULL 且数据零丢失。
 * 需要真机 / 模拟器（connectedDebugAndroidTest）。
 */
@RunWith(AndroidJUnit4.class)
public class MigrationTest {

    private static final String DB_NAME = "migration-test.db";

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(DB_NAME);
    }

    @After
    public void tearDown() {
        context.deleteDatabase(DB_NAME);
    }

    // ------------------------------------------------------------------
    // 手工搭建 v2 库（V1.1.1 的 schema）
    // ------------------------------------------------------------------

    private SQLiteDatabase createV2Database() {
        SQLiteDatabase db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
        db.execSQL("CREATE TABLE IF NOT EXISTS category ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                + "name TEXT NOT NULL, "
                + "icon TEXT NOT NULL, "
                + "type INTEGER NOT NULL, "
                + "sort_order INTEGER NOT NULL, "
                + "is_default INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS budget ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                + "year INTEGER NOT NULL, "
                + "month INTEGER NOT NULL, "
                + "amount INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budget_year_month "
                + "ON budget(year, month)");
        db.execSQL("CREATE TABLE IF NOT EXISTS transactions ("
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
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_category_id "
                + "ON transactions(category_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_date ON transactions(date)");
        db.execSQL("CREATE TABLE IF NOT EXISTS user_settings ("
                + "id INTEGER NOT NULL, "
                + "theme TEXT NOT NULL, "
                + "first_launch INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL, "
                + "PRIMARY KEY(id))");
        db.setVersion(2);
        return db;
    }

    private AppDatabase openLatest() {
        return Room.databaseBuilder(context, AppDatabase.class, DB_NAME)
                .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
                        AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
                .allowMainThreadQueries()
                .build();
    }

    /** V3 的 schema（MIGRATION_4_5 的产物）：5 张业务表带同步元数据 + 三张同步支撑表。 */
    private SQLiteDatabase createV5Database() {
        SQLiteDatabase db = createV4Database();
        for (String table : new String[]{
                "transactions", "category", "account", "budget", "recurring_transaction"}) {
            db.execSQL("ALTER TABLE " + table
                    + " ADD COLUMN sync_id TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + table
                    + " ADD COLUMN version INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + table
                    + " ADD COLUMN server_received_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + table
                    + " ADD COLUMN is_deleted INTEGER NOT NULL DEFAULT 0");
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
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_cursor ("
                + "account_email TEXT NOT NULL, "
                + "last_change_id INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL, "
                + "PRIMARY KEY(account_email))");
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_state ("
                + "id INTEGER NOT NULL, "
                + "sync_enabled INTEGER NOT NULL, "
                + "status TEXT, "
                + "last_sync_at INTEGER NOT NULL, "
                + "last_error TEXT, "
                + "conflict_count INTEGER NOT NULL, "
                + "PRIMARY KEY(id))");
        db.setVersion(5);
        return db;
    }

    // ------------------------------------------------------------------
    // 手工搭建 v3 库（V2 的 schema，MIGRATION_2_3 的产物）
    // ------------------------------------------------------------------

    private SQLiteDatabase createV3Database() {
        SQLiteDatabase db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
        db.execSQL("CREATE TABLE IF NOT EXISTS category ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                + "name TEXT NOT NULL, "
                + "icon TEXT NOT NULL, "
                + "type INTEGER NOT NULL, "
                + "sort_order INTEGER NOT NULL, "
                + "is_default INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS budget ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                + "year INTEGER NOT NULL, "
                + "month INTEGER NOT NULL, "
                + "category_id INTEGER NOT NULL DEFAULT 0, "
                + "amount INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budget_year_month_category_id "
                + "ON budget(year, month, category_id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS transactions ("
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
        for (String index : new String[]{
                "CREATE INDEX IF NOT EXISTS index_transactions_category_id "
                        + "ON transactions(category_id)",
                "CREATE INDEX IF NOT EXISTS index_transactions_date ON transactions(date)",
                "CREATE INDEX IF NOT EXISTS index_transactions_account_id "
                        + "ON transactions(account_id)",
                "CREATE INDEX IF NOT EXISTS index_transactions_transfer_account_id "
                        + "ON transactions(transfer_account_id)"}) {
            db.execSQL(index);
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS user_settings ("
                + "id INTEGER NOT NULL, "
                + "theme TEXT NOT NULL, "
                + "first_launch INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL, "
                + "PRIMARY KEY(id))");
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
        db.setVersion(3);
        return db;
    }

    // ------------------------------------------------------------------
    // 手工搭建 v4 库（V2.1 的 schema，MIGRATION_3_4 的产物）
    // ------------------------------------------------------------------

    private SQLiteDatabase createV4Database() {
        SQLiteDatabase db = createV3Database();
        // 3→4 的产物：recurring_transaction 增加锚点日列并按 start_date 回填
        db.execSQL("ALTER TABLE recurring_transaction "
                + "ADD COLUMN anchor_day_of_month INTEGER NOT NULL DEFAULT 0");
        db.execSQL("UPDATE recurring_transaction "
                + "SET anchor_day_of_month = CAST(strftime('%d', start_date / 1000, "
                + "'unixepoch', 'localtime') AS INTEGER)");
        db.setVersion(4);
        return db;
    }

    private static RecurringTransactionEntity byName(
            @NonNull List<RecurringTransactionEntity> list, @NonNull String name) {
        for (RecurringTransactionEntity item : list) {
            if (name.equals(item.name)) {
                return item;
            }
        }
        throw new AssertionError("missing recurring rule: " + name);
    }

    private static ContentValues row(Object... pairs) {
        ContentValues values = new ContentValues();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] == null) {
                values.putNull((String) pairs[i]);
            } else if (pairs[i + 1] instanceof String) {
                values.put((String) pairs[i], (String) pairs[i + 1]);
            } else if (pairs[i + 1] instanceof Long) {
                values.put((String) pairs[i], (Long) pairs[i + 1]);
            } else {
                values.put((String) pairs[i], (Integer) pairs[i + 1]);
            }
        }
        return values;
    }

    private static void insert(SQLiteDatabase db, String table, ContentValues values) {
        db.insertOrThrow(table, null, values);
    }

    // ------------------------------------------------------------------
    // 场景一：空库迁移
    // ------------------------------------------------------------------

    @Test
    public void migrate_emptyV2_seedsDefaultAccountsAndKeepsEmptyTables() {
        createV2Database().close();
        AppDatabase db = openLatest();

        // 迁移过程播种 6 个默认账户，初始余额与缓存均为 0
        List<AccountEntity> accounts = db.accountDao().getAll();
        assertEquals(6, accounts.size());
        for (AccountEntity account : accounts) {
            assertEquals(0L, account.initialBalance);
            assertEquals(0L, account.balance);
        }

        // 其余表存在且为空；设置表无行（v2 未写设置）
        assertEquals(0, db.transactionDao().count());
        assertTrue(db.budgetDao().getAll().isEmpty());
        assertTrue(db.recurringTransactionDao().getAll().isEmpty());
        assertNull(db.userSettingsDao().get());
        db.close();
    }

    // ------------------------------------------------------------------
    // 场景二：含账单（含转账外键与 NULL 归属断言）
    // ------------------------------------------------------------------

    @Test
    public void migrate_v2WithTransactions_preservesRowsWithNullAccount() {
        SQLiteDatabase v2 = createV2Database();
        insert(v2, "category", row("name", "餐饮", "icon", "🍜", "type", 1,
                "sort_order", 1, "is_default", 1));
        insert(v2, "transactions", row("type", 1, "amount", 3500, "category_id", 1,
                "date", 1_700_000_400_000L, "time", "12:30", "note", "午餐",
                "created_at", 1L, "updated_at", 1L));
        insert(v2, "transactions", row("type", 2, "amount", 900000, "category_id", 1,
                "date", 1_700_000_500_000L, "time", "09:00", "note", null,
                "created_at", 2L, "updated_at", 2L));
        v2.close();

        AppDatabase db = openLatest();
        List<TransactionEntity> transactions = db.transactionDao().getAllEntities();
        assertEquals(2, transactions.size());

        // 历史账单归属字段全部为 NULL（早于账户体系），分类与金额原样保留
        for (TransactionEntity transaction : transactions) {
            assertNull(transaction.accountId);
            assertNull(transaction.transferAccountId);
            assertEquals(Long.valueOf(1L), transaction.categoryId);
        }
        TransactionEntity first = transactions.get(0);
        assertEquals(1, first.id);
        assertEquals(3500L, first.amount);
        assertEquals(1_700_000_400_000L, first.date);
        assertEquals("12:30", first.time);
        assertEquals("午餐", first.note);

        // 外键 RESTRICT：分类仍被账单引用，物理删除必须失败
        try {
            db.categoryDao().deleteById(1);
            fail("被账单引用的分类不应能删除（RESTRICT 外键未生效）");
        } catch (SQLiteConstraintException expected) {
            // 预期路径
        }
        db.close();
    }

    // ------------------------------------------------------------------
    // 场景三：含分类
    // ------------------------------------------------------------------

    @Test
    public void migrate_v2WithCategories_preservesAll() {
        SQLiteDatabase v2 = createV2Database();
        for (int i = 1; i <= 10; i++) {
            insert(v2, "category", row("name", "支出" + i, "icon", "💸", "type", 1,
                    "sort_order", i, "is_default", 1));
        }
        insert(v2, "category", row("name", "自定义", "icon", "⭐", "type", 1,
                "sort_order", 11, "is_default", 0));
        v2.close();

        AppDatabase db = openLatest();
        List<CategoryEntity> categories = db.categoryDao().getAll();
        assertEquals(11, categories.size());
        CategoryEntity custom = categories.get(categories.size() - 1);
        assertEquals("自定义", custom.name);
        assertEquals(CategoryEntity.TYPE_EXPENSE, custom.type);
        assertEquals(11, custom.sortOrder);
        db.close();
    }

    // ------------------------------------------------------------------
    // 场景四：含预算（哨兵 0 + 新唯一索引）
    // ------------------------------------------------------------------

    @Test
    public void migrate_v2WithBudget_fillsSentinelAndKeepsUniqueness() {
        SQLiteDatabase v2 = createV2Database();
        insert(v2, "budget", row("year", 2026, "month", 9, "amount", 200000,
                "created_at", 1L, "updated_at", 1L));
        v2.close();

        AppDatabase db = openLatest();

        // 存量预算全部是总预算：迁移后 category_id 统一为哨兵 0，金额不变
        BudgetEntity budget = db.budgetDao().get(2026, 9);
        assertNotNull(budget);
        assertEquals(200000L, budget.amount);
        assertEquals(BudgetEntity.CATEGORY_TOTAL, budget.categoryId);

        // (year, month, category_id) 唯一索引存在；重复插入总预算哨兵必须被约束拒绝
        Cursor indexCursor = db.getOpenHelper().getWritableDatabase().query(
                "SELECT name FROM sqlite_master WHERE type = 'index' "
                        + "AND name = 'index_budget_year_month_category_id'");
        assertEquals(1, indexCursor.getCount());
        indexCursor.close();

        try {
            db.getOpenHelper().getWritableDatabase().insert("budget",
                    SQLiteDatabase.CONFLICT_ABORT,
                    row("id", 99, "year", 2026, "month", 9, "category_id", 0,
                            "amount", 1, "created_at", 1L, "updated_at", 1L));
            fail("同月重复的总预算哨兵不应插入成功（唯一索引未生效）");
        } catch (SQLiteConstraintException expected) {
            // 预期路径
        }
        db.close();
    }

    // ------------------------------------------------------------------
    // 场景五：大量账单零丢失
    // ------------------------------------------------------------------

    @Test
    public void migrate_v2WithManyTransactions_zeroDataLoss() {
        SQLiteDatabase v2 = createV2Database();
        insert(v2, "category", row("name", "餐饮", "icon", "🍜", "type", 1,
                "sort_order", 1, "is_default", 1));
        int count = 1000;
        long expectedSum = 0;
        v2.beginTransaction();
        try {
            for (int i = 0; i < count; i++) {
                long amount = 100L + i;
                expectedSum += amount;
                insert(v2, "transactions", row("type", 1, "amount", (Long) amount,
                        "category_id", 1, "date", 1_700_000_000_000L + i * 86_400_000L,
                        "time", "12:00", "note", null, "created_at", 1L, "updated_at", 1L));
            }
            v2.setTransactionSuccessful();
        } finally {
            v2.endTransaction();
        }
        v2.close();

        AppDatabase db = openLatest();
        assertEquals(count, db.transactionDao().count());

        // 金额合计逐分核对：迁移不允许丢任何一行、改任何一个数
        Cursor sumCursor = db.getOpenHelper().getWritableDatabase().query(
                "SELECT COALESCE(SUM(amount), 0) FROM transactions");
        assertTrue(sumCursor.moveToFirst());
        assertEquals(expectedSum, sumCursor.getLong(0));
        sumCursor.close();
        db.close();
    }

    // ------------------------------------------------------------------
    // V2.1 专项：3→4 锚点日回填
    // ------------------------------------------------------------------

    @Test
    public void migrate_v3WithRecurring_backfillsAnchorFromStartDate() {
        SQLiteDatabase v3 = createV3Database();
        // 月规则：开始日 1 月 31 日；旧推进逻辑已把 next_run_date 漂移到 3 月 28 日
        insert(v3, "recurring_transaction", row("name", "房租", "type", 1, "amount", 300000L,
                "frequency", 3, "repeat_interval", 1,
                "start_date", DateUtil.dayMillisOf(2026, 1, 31),
                "end_date", 0L,
                "next_run_date", DateUtil.dayMillisOf(2026, 3, 28),
                "is_enabled", 1, "note", null, "created_at", 1L, "updated_at", 1L));
        // 年规则：开始日 2024-02-29（闰年日），next_run 已被夹到 2025-02-28
        insert(v3, "recurring_transaction", row("name", "会员", "type", 1, "amount", 20000L,
                "frequency", 4, "repeat_interval", 1,
                "start_date", DateUtil.dayMillisOf(2024, 2, 29),
                "end_date", 0L,
                "next_run_date", DateUtil.dayMillisOf(2025, 2, 28),
                "is_enabled", 1, "note", null, "created_at", 2L, "updated_at", 2L));
        v3.close();

        AppDatabase db = openLatest();
        List<RecurringTransactionEntity> recurring = db.recurringTransactionDao().getAll();
        assertEquals(2, recurring.size());
        // getAll 无稳定排序：按名称取行断言。锚点应取 start_date 的日（31 / 29）——
        // 用户最初选择的日期；不能取 next_run_date 的日（28），否则既有漂移会被固化成新锚点
        RecurringTransactionEntity monthly = byName(recurring, "房租");
        RecurringTransactionEntity yearly = byName(recurring, "会员");
        assertEquals(31, monthly.anchorDayOfMonth);
        assertEquals(29, yearly.anchorDayOfMonth);
        // 其余字段零丢失
        assertEquals(300000L, monthly.amount);
        assertTrue(monthly.isEnabled);
        db.close();
    }

    // ------------------------------------------------------------------
    // V3 专项：4→5 同步元数据回填与同步支撑表
    // ------------------------------------------------------------------

    @Test
    public void migrate_v4WithRows_backfillsSyncIdsAndCreatesSyncTables() {
        SQLiteDatabase v4 = createV4Database();
        insert(v4, "category", row("name", "餐饮", "icon", "🍜", "type", 1,
                "sort_order", 1, "is_default", 1));
        insert(v4, "account", row("name", "现金", "type", 1, "initial_balance", 10000L,
                "balance", 10000L, "is_credit", 0, "sort_order", 1, "is_archived", 0,
                "created_at", 1L, "updated_at", 1L));
        insert(v4, "transactions", row("type", 1, "amount", 3500, "category_id", 1,
                "account_id", 1, "date", 1_700_000_400_000L, "time", "12:30",
                "note", "午餐", "created_at", 1L, "updated_at", 1L));
        insert(v4, "budget", row("year", 2026, "month", 9, "category_id", 0,
                "amount", 200000, "created_at", 1L, "updated_at", 1L));
        insert(v4, "recurring_transaction", row("name", "房租", "type", 1, "amount", 300000L,
                "frequency", 3, "repeat_interval", 1,
                "start_date", DateUtil.dayMillisOf(2026, 1, 31), "end_date", 0L,
                "next_run_date", DateUtil.dayMillisOf(2026, 1, 31),
                "anchor_day_of_month", 31, "is_enabled", 1, "note", null,
                "created_at", 1L, "updated_at", 1L));
        v4.close();

        AppDatabase db = openLatest();

        // 1) 业务行零丢失
        assertEquals(1, db.transactionDao().count());
        assertEquals(1, db.accountDao().count());

        // 2) 全部行拿到非空 UUID 身份（v4 格式：8-4-4-4-12，共 36 字符）
        for (TransactionEntity transaction : db.transactionDao().getAllEntities()) {
            assertTrue("transaction syncId should be backfilled",
                    transaction.syncId != null && transaction.syncId.length() == 36);
            assertEquals(0L, transaction.version);
            assertEquals(0L, transaction.serverReceivedAt);
        }
        for (CategoryEntity category : db.categoryDao().getAll()) {
            assertTrue("category syncId should be backfilled",
                    category.syncId != null && category.syncId.length() == 36);
        }
        for (AccountEntity account : db.accountDao().getAll()) {
            assertTrue("account syncId should be backfilled",
                    account.syncId != null && account.syncId.length() == 36);
        }
        for (BudgetEntity budget : db.budgetDao().getAll()) {
            assertTrue("budget syncId should be backfilled",
                    budget.syncId != null && budget.syncId.length() == 36);
        }
        for (RecurringTransactionEntity recurring : db.recurringTransactionDao().getAll()) {
            assertTrue("recurring syncId should be backfilled",
                    recurring.syncId != null && recurring.syncId.length() == 36);
        }

        // 3) 同步支撑表就绪且可用
        db.syncChangeQueueDao().upsert(queueRow());
        assertEquals(1, db.syncChangeQueueDao().pendingCount());
        db.syncChangeQueueDao().clearAll();
        assertEquals(0, db.syncChangeQueueDao().pendingCount());
        com.skyanchor.bookkeeping.data.entity.SyncCursorEntity cursor =
                new com.skyanchor.bookkeeping.data.entity.SyncCursorEntity();
        cursor.accountEmail = "test@example.com";
        cursor.lastChangeId = 42;
        cursor.updatedAt = 1L;
        db.syncCursorDao().upsert(cursor);
        assertEquals(42L, db.syncCursorDao().find("test@example.com").lastChangeId);
        com.skyanchor.bookkeeping.data.entity.SyncStateEntity state =
                new com.skyanchor.bookkeeping.data.entity.SyncStateEntity();
        state.syncEnabled = true;
        db.syncStateDao().upsert(state);
        assertTrue(db.syncStateDao().get().syncEnabled);

        // 4) 同步元数据列默认值正确（未同步、未删除）
        Cursor meta = db.getOpenHelper().getWritableDatabase().query(
                "SELECT version, server_received_at, is_deleted FROM transactions WHERE id = 1");
        assertEquals(1, meta.getCount());
        assertTrue(meta.moveToFirst());
        assertEquals(0, meta.getLong(0));
        assertEquals(0, meta.getLong(1));
        assertEquals(0, meta.getLong(2));
        meta.close();
        db.close();
    }

    @Test
    public void migrate_v4_empty_stillCreatesSyncTables() {
        createV4Database().close();
        AppDatabase db = openLatest();
        assertEquals(0, db.syncChangeQueueDao().pendingCount());
        // 默认账户只在建库回调播种，迁移路径不播种：空 v4 库迁移后账户仍为空
        assertEquals(0, db.accountDao().count());
        db.close();
    }

    private static com.skyanchor.bookkeeping.data.entity.SyncChangeQueueEntity queueRow() {
        com.skyanchor.bookkeeping.data.entity.SyncChangeQueueEntity row =
                new com.skyanchor.bookkeeping.data.entity.SyncChangeQueueEntity();
        row.entityType = "TRANSACTION";
        row.syncId = "11111111-1111-1111-1111-111111111111";
        row.operation = "UPSERT";
        row.baseVersion = 0;
        row.createdAt = 1L;
        row.nextRetryAt = 0;
        return row;
    }

    // ------------------------------------------------------------------
    // 结构断言：外键与索引
    // ------------------------------------------------------------------

    @Test
    public void migrate_resultingSchemaHasExpectedForeignKeysAndIndexes() {
        createV2Database().close();
        AppDatabase db = openLatest();

        Cursor fk = db.getOpenHelper().getWritableDatabase().query(
                "PRAGMA foreign_key_list(transactions)");
        assertEquals(3, fk.getCount());
        fk.close();

        for (String index : new String[]{
                "index_transactions_category_id", "index_transactions_date",
                "index_transactions_account_id", "index_transactions_transfer_account_id",
                "index_recurring_transaction_next_run_date",
                "index_recurring_transaction_is_enabled",
                "index_transactions_sync_id", "index_category_sync_id",
                "index_account_sync_id", "index_budget_sync_id",
                "index_recurring_transaction_sync_id",
                "index_sync_change_queue_entity_type_sync_id",
                "index_sync_change_queue_next_retry_at"}) {
            Cursor cursor = db.getOpenHelper().getWritableDatabase().query(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND name = '"
                            + index + "'");
            assertEquals("缺少索引 " + index, 1, cursor.getCount());
            cursor.close();
        }
        db.close();
    }

    // ------------------------------------------------------------------
    // V3.1：5 → 6（deleted_at / 诊断列 / sync_events）
    // ------------------------------------------------------------------

    @Test
    public void migrate_v5_addsDeletedAtDiagnosticsAndEvents() {
        SQLiteDatabase v5 = createV5Database();
        // 一条已软删的交易（历史墓碑，无 deleted_at）
        Cursor accountRow = v5.rawQuery("SELECT id FROM account LIMIT 1", new String[0]);
        assertTrue(accountRow.moveToFirst());
        long accountId = accountRow.getLong(0);
        accountRow.close();
        insert(v5, "transactions", row(
                "type", 1, "amount", 3500L, "category_id", null,
                "account_id", accountId, "transfer_account_id", null,
                "date", 1_700_000_000_000L, "time", "12:30",
                "created_at", 1_700_000_000_000L, "updated_at", 1_700_000_000_000L));
        v5.execSQL("UPDATE transactions SET is_deleted = 1");
        v5.close();

        AppDatabase db = openLatest();
        // Room 开库时已按 6.json 完成整库 schema 校验；这里验证数据语义：
        // 1) 历史墓碑的 deleted_at 为 NULL（回收站按 updated_at 兜底展示）
        Cursor deletedAt = db.getOpenHelper().getWritableDatabase().query(
                "SELECT deleted_at FROM transactions WHERE is_deleted = 1");
        assertEquals(1, deletedAt.getCount());
        assertTrue(deletedAt.moveToFirst());
        assertTrue(deletedAt.isNull(0));
        deletedAt.close();
        // 2) sync_state 诊断列默认 0，可正常读写
        com.skyanchor.bookkeeping.data.entity.SyncStateEntity state =
                new com.skyanchor.bookkeeping.data.entity.SyncStateEntity();
        db.syncStateDao().upsert(state);
        assertEquals(0L, db.syncStateDao().get().lastPushAt);
        assertEquals(0L, db.syncStateDao().get().recoveryEpoch);
        // 3) sync_events 表可用且裁剪生效
        for (int i = 0; i < 60; i++) {
            com.skyanchor.bookkeeping.data.entity.SyncEventEntity event =
                    new com.skyanchor.bookkeeping.data.entity.SyncEventEntity();
            event.startedAt = i;
            event.finishedAt = i;
            event.result = "SUCCESS";
            event.pushCount = 0;
            event.pullCount = 0;
            event.conflictCount = 0;
            event.durationMs = 0;
            db.syncEventDao().insert(event);
        }
        assertEquals(50, db.syncEventDao().count());
        db.close();
    }
}
