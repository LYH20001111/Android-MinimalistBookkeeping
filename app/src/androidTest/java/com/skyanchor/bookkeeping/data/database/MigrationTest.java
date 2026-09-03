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

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * 迁移测试（V2 开发计划 Phase 10）。
 *
 * <p>v2 schema 当年未导出 JSON，因此这里用框架 SQLite 按当年实体结构手工搭建 v2 库
 * （DDL 与 {@code MIGRATION_1_2} 的产物一致），再交给 Room 打开：Room 会执行
 * {@code MIGRATION_2_3}，并在开库时把迁移结果与导出的 v3 schema（3.json）逐项比对，
 * 列、可空性、默认值、索引、外键任一不符即抛异常——这是最强的一层结构断言。
 *
 * <p>数据场景覆盖计划要求的五种：空库、含账单、含分类、含预算、大量账单；
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

    private AppDatabase openV3() {
        return Room.databaseBuilder(context, AppDatabase.class, DB_NAME)
                .addMigrations(AppDatabase.MIGRATION_2_3)
                .allowMainThreadQueries()
                .build();
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
        AppDatabase db = openV3();

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

        AppDatabase db = openV3();
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

        AppDatabase db = openV3();
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

        AppDatabase db = openV3();

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

        AppDatabase db = openV3();
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
    // 结构断言：外键与索引
    // ------------------------------------------------------------------

    @Test
    public void migrate_resultingSchemaHasExpectedForeignKeysAndIndexes() {
        createV2Database().close();
        AppDatabase db = openV3();

        Cursor fk = db.getOpenHelper().getWritableDatabase().query(
                "PRAGMA foreign_key_list(transactions)");
        assertEquals(3, fk.getCount());
        fk.close();

        for (String index : new String[]{
                "index_transactions_category_id", "index_transactions_date",
                "index_transactions_account_id", "index_transactions_transfer_account_id",
                "index_recurring_transaction_next_run_date",
                "index_recurring_transaction_is_enabled"}) {
            Cursor cursor = db.getOpenHelper().getWritableDatabase().query(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND name = '"
                            + index + "'");
            assertEquals("缺少索引 " + index, 1, cursor.getCount());
            cursor.close();
        }
        db.close();
    }
}
