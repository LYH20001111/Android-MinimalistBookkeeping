package com.skyanchor.bookkeeping.domain.importexport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.entity.UserSettingsEntity;
import com.skyanchor.bookkeeping.data.model.BackupData;
import com.skyanchor.bookkeeping.util.DateUtil;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 备份序列化单元测试（V2 开发计划 Phase 7）。
 *
 * <p>核心承诺是「round-trip 保真」：备份 → 解析还原后，全部实体字段（含原始 id 与可空字段）
 * 与备份前完全一致，恢复时才能按原 id 重插并维持跨表引用。另覆盖可空字段省略、
 * 设置段缺失、版本号写入与非法 JSON。
 */
public class BackupSerializerTest {

    // ------------------------------------------------------------------
    // 造数工具：填满全部字段
    // ------------------------------------------------------------------

    private static AccountEntity account() {
        AccountEntity account = new AccountEntity("微信", AccountEntity.TYPE_WECHAT, 12_800L,
                false, 2);
        account.id = 5L;
        account.balance = -3_200L;
        account.isArchived = true;
        account.createdAt = 1_700_000_000_000L;
        account.updatedAt = 1_700_000_001_000L;
        return account;
    }

    private static CategoryEntity category() {
        CategoryEntity category = new CategoryEntity("餐饮", "🍜", CategoryEntity.TYPE_EXPENSE, 1,
                true);
        category.id = 7L;
        return category;
    }

    private static TransactionEntity transaction() {
        TransactionEntity transaction = new TransactionEntity();
        transaction.id = 11L;
        transaction.type = CategoryEntity.TYPE_EXPENSE;
        transaction.amount = 3_500L;
        transaction.categoryId = 7L;
        transaction.accountId = 5L;
        transaction.date = 1_700_000_400_000L;
        transaction.time = "12:30";
        transaction.note = "午餐,加\"冰\"";
        transaction.createdAt = 1_700_000_401_000L;
        transaction.updatedAt = 1_700_000_402_000L;
        return transaction;
    }

    private static BudgetEntity budget() {
        BudgetEntity budget = new BudgetEntity();
        budget.id = 3L;
        budget.year = 2026;
        budget.month = 9;
        budget.categoryId = BudgetEntity.CATEGORY_TOTAL;
        budget.amount = 200_000L;
        budget.createdAt = 1_700_000_000_000L;
        budget.updatedAt = 1_700_000_000_500L;
        return budget;
    }

    private static RecurringTransactionEntity recurring() {
        RecurringTransactionEntity item = new RecurringTransactionEntity();
        item.id = 9L;
        item.name = "房租";
        item.type = CategoryEntity.TYPE_EXPENSE;
        item.amount = 260_000L;
        item.categoryId = 7L;
        item.accountId = 5L;
        item.frequency = RecurringTransactionEntity.FREQUENCY_MONTHLY;
        item.interval = 1;
        item.anchorDayOfMonth = 31;
        item.startDate = 1_700_000_000_000L;
        item.endDate = 0L;
        item.nextRunDate = 1_700_090_000_000L;
        item.isEnabled = true;
        item.note = "每月一号";
        item.createdAt = 1_700_000_000_000L;
        item.updatedAt = 1_700_000_000_100L;
        return item;
    }

    private static UserSettingsEntity settings() {
        UserSettingsEntity settings = new UserSettingsEntity();
        settings.id = UserSettingsEntity.SINGLETON_ID;
        settings.theme = UserSettingsEntity.THEME_SYSTEM;
        settings.firstLaunch = false;
        settings.createdAt = 1_700_000_000_000L;
        settings.updatedAt = 1_700_000_500_000L;
        return settings;
    }

    private static BackupData fullData() {
        BackupData data = new BackupData();
        data.schemaVersion = BackupSerializer.SCHEMA_VERSION;
        data.accounts = new ArrayList<>();
        data.accounts.add(account());
        data.categories = new ArrayList<>();
        data.categories.add(category());
        data.transactions = new ArrayList<>();
        data.transactions.add(transaction());
        data.budgets = new ArrayList<>();
        data.budgets.add(budget());
        data.recurring = new ArrayList<>();
        data.recurring.add(recurring());
        data.settings = settings();
        return data;
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    /** 备份必须写入与 AppDatabase v3 对齐的格式版本号，恢复侧据此拒绝旧 / 新版本文件。 */
    @Test
    public void toJson_writesSchemaVersionAndSections() throws JSONException {
        String json = BackupSerializer.toJson(fullData());
        JSONObject root = new JSONObject(json);

        assertEquals(BackupSerializer.SCHEMA_VERSION, root.optInt("schemaVersion"));
        assertEquals(1, root.optJSONArray("accounts").length());
        assertEquals(1, root.optJSONArray("categories").length());
        assertEquals(1, root.optJSONArray("transactions").length());
        assertEquals(1, root.optJSONArray("budgets").length());
        assertEquals(1, root.optJSONArray("recurring").length());
        assertTrue(root.optJSONObject("settings").length() > 0);
    }

    /** round-trip：序列化 → 解析后全部字段（含原始 id、余额缓存、时间戳）保持一致。 */
    @Test
    public void roundTrip_preservesAllEntities() throws JSONException {
        BackupData restored = BackupSerializer.fromJson(BackupSerializer.toJson(fullData()));

        assertEquals(BackupSerializer.SCHEMA_VERSION, restored.schemaVersion);

        assertEquals(1, restored.accounts.size());
        AccountEntity account = restored.accounts.get(0);
        assertEquals(5L, account.id);
        assertEquals("微信", account.name);
        assertEquals(AccountEntity.TYPE_WECHAT, account.type);
        assertEquals(12_800L, account.initialBalance);
        assertEquals(-3_200L, account.balance);
        assertFalse(account.isCredit);
        assertTrue(account.isArchived);
        assertEquals(2, account.sortOrder);
        assertEquals(1_700_000_000_000L, account.createdAt);
        assertEquals(1_700_000_001_000L, account.updatedAt);

        assertEquals(1, restored.categories.size());
        CategoryEntity category = restored.categories.get(0);
        assertEquals(7L, category.id);
        assertEquals("餐饮", category.name);
        assertEquals("🍜", category.icon);
        assertEquals(CategoryEntity.TYPE_EXPENSE, category.type);
        assertTrue(category.isDefault);

        assertEquals(1, restored.transactions.size());
        TransactionEntity transaction = restored.transactions.get(0);
        assertEquals(11L, transaction.id);
        assertEquals(3_500L, transaction.amount);
        assertEquals(Long.valueOf(7L), transaction.categoryId);
        assertEquals(Long.valueOf(5L), transaction.accountId);
        assertNull(transaction.transferAccountId);
        assertEquals(1_700_000_400_000L, transaction.date);
        assertEquals("12:30", transaction.time);
        assertEquals("午餐,加\"冰\"", transaction.note);

        assertEquals(1, restored.budgets.size());
        BudgetEntity budget = restored.budgets.get(0);
        assertEquals(3L, budget.id);
        assertEquals(2026, budget.year);
        assertEquals(9, budget.month);
        assertEquals(BudgetEntity.CATEGORY_TOTAL, budget.categoryId);
        assertEquals(200_000L, budget.amount);

        assertEquals(1, restored.recurring.size());
        RecurringTransactionEntity recurring = restored.recurring.get(0);
        assertEquals(9L, recurring.id);
        assertEquals("房租", recurring.name);
        assertEquals(260_000L, recurring.amount);
        assertEquals(Long.valueOf(7L), recurring.categoryId);
        assertEquals(Long.valueOf(5L), recurring.accountId);
        assertEquals(RecurringTransactionEntity.FREQUENCY_MONTHLY, recurring.frequency);
        assertEquals(31, recurring.anchorDayOfMonth);
        assertEquals(0L, recurring.endDate);
        assertTrue(recurring.isEnabled);
        assertEquals("每月一号", recurring.note);

        assertEquals(UserSettingsEntity.THEME_SYSTEM, restored.settings.theme);
        assertFalse(restored.settings.firstLaunch);
    }

    /** 可空字段（转账的分类 / 账户、备注）序列化时省略，解析后还原为 null。 */
    @Test
    public void roundTrip_keepsNullFieldsNull() throws JSONException {
        BackupData data = new BackupData();
        TransactionEntity transfer = new TransactionEntity();
        transfer.id = 12L;
        transfer.type = CategoryEntity.TYPE_TRANSFER;
        transfer.amount = 9_900L;
        transfer.categoryId = null;
        transfer.accountId = 5L;
        transfer.transferAccountId = 6L;
        transfer.date = 1_700_000_400_000L;
        transfer.time = "08:00";
        transfer.note = null;
        data.transactions = new ArrayList<>();
        data.transactions.add(transfer);
        data.accounts = new ArrayList<>();
        data.categories = new ArrayList<>();
        data.budgets = new ArrayList<>();
        data.recurring = new ArrayList<>();
        data.settings = null;

        BackupData restored = BackupSerializer.fromJson(BackupSerializer.toJson(data));

        TransactionEntity back = restored.transactions.get(0);
        assertNull(back.categoryId);
        assertEquals(Long.valueOf(5L), back.accountId);
        assertEquals(Long.valueOf(6L), back.transferAccountId);
        assertNull(back.note);
        // 缺失的设置段还原为 null，由恢复侧回落到默认设置
        assertNull(restored.settings);
    }

    /** 空备份（空账本）也是合法文件：各段为空数组、设置可缺失。 */
    @Test
    public void fromJson_toleratesMissingSections() throws JSONException {
        String json = "{\"schemaVersion\":" + BackupSerializer.SCHEMA_VERSION + "}";
        BackupData restored = BackupSerializer.fromJson(json);

        assertEquals(BackupSerializer.SCHEMA_VERSION, restored.schemaVersion);
        assertTrue(restored.accounts.isEmpty());
        assertTrue(restored.categories.isEmpty());
        assertTrue(restored.transactions.isEmpty());
        assertTrue(restored.budgets.isEmpty());
        assertTrue(restored.recurring.isEmpty());
        assertNull(restored.settings);
    }

    /** 非 JSON 文本必须抛 JSONException，由恢复用例映射为「无法解析」。 */
    @Test(expected = JSONException.class)
    public void fromJson_rejectsMalformedJson() throws JSONException {
        BackupSerializer.fromJson("这不是一个 JSON 文件");
    }

    /**
     * V2（version 3）旧备份没有锚点日字段：解析时按开始日期的日推导补齐，
     * 语义与 3→4 迁移一致（基线 31.5：备份 / 恢复数据结构兼容新字段）。
     */
    @Test
    public void fromJson_v3BackupWithoutAnchorDerivesItFromStartDate() throws JSONException {
        RecurringTransactionEntity item = recurring();
        item.anchorDayOfMonth = 0;
        item.startDate = DateUtil.dayMillisOf(2026, 1, 31);
        BackupData data = new BackupData();
        data.schemaVersion = BackupSerializer.MIN_SUPPORTED_VERSION;
        data.accounts = new ArrayList<>();
        data.categories = new ArrayList<>();
        data.transactions = new ArrayList<>();
        data.budgets = new ArrayList<>();
        data.recurring = new ArrayList<>();
        data.recurring.add(item);
        data.settings = null;

        BackupData restored = BackupSerializer.fromJson(BackupSerializer.toJson(data));

        assertEquals(31, restored.recurring.get(0).anchorDayOfMonth);
    }
}
