package com.skyanchor.bookkeeping.domain.importexport;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.entity.UserSettingsEntity;
import com.skyanchor.bookkeeping.data.model.BackupData;
import com.skyanchor.bookkeeping.util.DateUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 备份序列化（V2 新增，开发计划 Phase 7）。
 *
 * <p>用平台内置 {@code org.json} 实现版本化 JSON（不引入第三方运行时依赖）：
 * <pre>{@code
 * {"schemaVersion":4,"accounts":[...],"categories":[...],"transactions":[...],
 *  "budgets":[...],"recurring":[...],"settings":{...}}
 * }</pre>
 *
 * <p>实体字段一一对应、保留原始 id，恢复时按原 id 重插才能维持跨表引用；
 * 可空字段（交易的分类 / 账户、备注等）缺失即还原为 null。金额与时间戳均为 long 原值，
 * 不经任何格式化，避免精度或时区损失。
 */
public final class BackupSerializer {

    /**
     * 备份文件格式版本，当前与 {@code AppDatabase} 的 version 4 对齐（V2.1 增加
     * 周期账单锚点日 {@code anchorDayOfMonth}）。
     * 恢复时拒绝高于当前版本的文件（schema 未知，混写有风险）；
     * V2 的 version 3 备份仍可恢复，缺失的锚点日由序列化侧按开始日期推导补齐。
     */
    /**
     * V3 = 5：每个实体增补 {@code syncId}（跨设备身份）。恢复时保留身份、
     * 重置版本号并全量重推，云端以 LWW 收敛（开发计划备注 9）。
     * 旧备份缺 syncId 时恢复侧自动补发新身份。
     */
    public static final int SCHEMA_VERSION = 5;

    /** 仍可恢复的最低备份格式版本：3 = V2 基线（无锚点日字段）。 */
    public static final int MIN_SUPPORTED_VERSION = 3;

    private BackupSerializer() {
    }

    /** 序列化为 JSON 文本（紧凑格式，UTF-8 编码由写入方负责）。 */
    @NonNull
    public static String toJson(@NonNull BackupData data) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);

        JSONArray accounts = new JSONArray();
        if (data.accounts != null) {
            for (AccountEntity account : data.accounts) {
                if (account != null) {
                    accounts.put(accountToJson(account));
                }
            }
        }
        root.put("accounts", accounts);

        JSONArray categories = new JSONArray();
        if (data.categories != null) {
            for (CategoryEntity category : data.categories) {
                if (category != null) {
                    categories.put(categoryToJson(category));
                }
            }
        }
        root.put("categories", categories);

        JSONArray transactions = new JSONArray();
        if (data.transactions != null) {
            for (TransactionEntity transaction : data.transactions) {
                if (transaction != null) {
                    transactions.put(transactionToJson(transaction));
                }
            }
        }
        root.put("transactions", transactions);

        JSONArray budgets = new JSONArray();
        if (data.budgets != null) {
            for (BudgetEntity budget : data.budgets) {
                if (budget != null) {
                    budgets.put(budgetToJson(budget));
                }
            }
        }
        root.put("budgets", budgets);

        JSONArray recurring = new JSONArray();
        if (data.recurring != null) {
            for (RecurringTransactionEntity item : data.recurring) {
                if (item != null) {
                    recurring.put(recurringToJson(item));
                }
            }
        }
        root.put("recurring", recurring);

        root.put("settings", settingsToJson(data.settings));
        return root.toString();
    }

    /**
     * 解析备份文本。顶层缺数组 / 缺设置时按空数据还原，由调用方决定是否可用；
     * JSON 本身非法时抛 {@link JSONException}。
     */
    @NonNull
    public static BackupData fromJson(@NonNull String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        BackupData data = new BackupData();
        data.schemaVersion = root.optInt("schemaVersion", 0);

        data.accounts = accountList(root.optJSONArray("accounts"));
        data.categories = categoryList(root.optJSONArray("categories"));
        data.transactions = transactionList(root.optJSONArray("transactions"));
        data.budgets = budgetList(root.optJSONArray("budgets"));
        data.recurring = recurringList(root.optJSONArray("recurring"));
        data.settings = settingsFrom(root.optJSONObject("settings"));
        return data;
    }

    // ------------------------------------------------------------------
    // 实体 → JSON
    // ------------------------------------------------------------------

    @NonNull
    private static JSONObject accountToJson(@NonNull AccountEntity account) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", account.id);
        json.put("name", account.name);
        json.put("type", account.type);
        json.put("initialBalance", account.initialBalance);
        json.put("balance", account.balance);
        json.put("isCredit", account.isCredit);
        json.put("sortOrder", account.sortOrder);
        json.put("isArchived", account.isArchived);
        json.put("createdAt", account.createdAt);
        json.put("updatedAt", account.updatedAt);
        json.put("syncId", account.syncId);
        return json;
    }

    @NonNull
    private static JSONObject categoryToJson(@NonNull CategoryEntity category)
            throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", category.id);
        json.put("name", category.name);
        json.put("icon", category.icon);
        json.put("type", category.type);
        json.put("sortOrder", category.sortOrder);
        json.put("isDefault", category.isDefault);
        return json;
    }

    @NonNull
    private static JSONObject transactionToJson(@NonNull TransactionEntity transaction)
            throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", transaction.id);
        json.put("type", transaction.type);
        json.put("amount", transaction.amount);
        putNullableLong(json, "categoryId", transaction.categoryId);
        putNullableLong(json, "accountId", transaction.accountId);
        putNullableLong(json, "transferAccountId", transaction.transferAccountId);
        json.put("date", transaction.date);
        json.put("time", transaction.time);
        putNullableString(json, "note", transaction.note);
        json.put("createdAt", transaction.createdAt);
        json.put("updatedAt", transaction.updatedAt);
        json.put("syncId", transaction.syncId);
        return json;
    }

    @NonNull
    private static JSONObject budgetToJson(@NonNull BudgetEntity budget) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", budget.id);
        json.put("year", budget.year);
        json.put("month", budget.month);
        json.put("categoryId", budget.categoryId);
        json.put("amount", budget.amount);
        json.put("createdAt", budget.createdAt);
        json.put("updatedAt", budget.updatedAt);
        json.put("syncId", budget.syncId);
        return json;
    }

    @NonNull
    private static JSONObject recurringToJson(@NonNull RecurringTransactionEntity item)
            throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", item.id);
        json.put("name", item.name);
        json.put("type", item.type);
        json.put("amount", item.amount);
        putNullableLong(json, "categoryId", item.categoryId);
        putNullableLong(json, "accountId", item.accountId);
        json.put("frequency", item.frequency);
        json.put("interval", item.interval);
        json.put("anchorDayOfMonth", item.anchorDayOfMonth);
        json.put("startDate", item.startDate);
        json.put("endDate", item.endDate);
        json.put("nextRunDate", item.nextRunDate);
        json.put("isEnabled", item.isEnabled);
        json.put("syncId", item.syncId);
        putNullableString(json, "note", item.note);
        json.put("createdAt", item.createdAt);
        json.put("updatedAt", item.updatedAt);
        return json;
    }

    /** 设置缺失（旧文件或空备份）时序列化为空对象，解析侧还原为 null 由恢复方兜底。 */
    @NonNull
    private static JSONObject settingsToJson(@Nullable UserSettingsEntity settings)
            throws JSONException {
        JSONObject json = new JSONObject();
        if (settings == null) {
            return json;
        }
        json.put("id", settings.id);
        json.put("theme", settings.theme);
        json.put("firstLaunch", settings.firstLaunch);
        json.put("createdAt", settings.createdAt);
        json.put("updatedAt", settings.updatedAt);
        return json;
    }

    // ------------------------------------------------------------------
    // JSON → 实体
    // ------------------------------------------------------------------

    @NonNull
    private static List<AccountEntity> accountList(@Nullable JSONArray array) {
        List<AccountEntity> list = new ArrayList<>();
        if (array == null) {
            return list;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) {
                continue;
            }
            AccountEntity account = new AccountEntity();
            account.id = json.optLong("id");
            account.name = json.optString("name", "");
            account.type = json.optInt("type", AccountEntity.TYPE_CASH);
            account.initialBalance = json.optLong("initialBalance");
            account.balance = json.optLong("balance");
            account.isCredit = json.optBoolean("isCredit");
            account.sortOrder = json.optInt("sortOrder");
            account.isArchived = json.optBoolean("isArchived");
            account.createdAt = json.optLong("createdAt");
            account.updatedAt = json.optLong("updatedAt");
            account.syncId = json.optString("syncId", "");
            list.add(account);
        }
        return list;
    }

    @NonNull
    private static List<CategoryEntity> categoryList(@Nullable JSONArray array) {
        List<CategoryEntity> list = new ArrayList<>();
        if (array == null) {
            return list;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) {
                continue;
            }
            CategoryEntity category = new CategoryEntity();
            category.id = json.optLong("id");
            category.name = json.optString("name", "");
            category.icon = json.optString("icon", "");
            category.type = json.optInt("type", CategoryEntity.TYPE_EXPENSE);
            category.sortOrder = json.optInt("sortOrder");
            category.isDefault = json.optBoolean("isDefault");
            category.syncId = json.optString("syncId", "");
            list.add(category);
        }
        return list;
    }

    @NonNull
    private static List<TransactionEntity> transactionList(@Nullable JSONArray array) {
        List<TransactionEntity> list = new ArrayList<>();
        if (array == null) {
            return list;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) {
                continue;
            }
            TransactionEntity transaction = new TransactionEntity();
            transaction.id = json.optLong("id");
            transaction.syncId = json.optString("syncId", "");
            transaction.type = json.optInt("type", CategoryEntity.TYPE_EXPENSE);
            transaction.amount = json.optLong("amount");
            transaction.categoryId = nullableLong(json, "categoryId");
            transaction.accountId = nullableLong(json, "accountId");
            transaction.transferAccountId = nullableLong(json, "transferAccountId");
            transaction.date = json.optLong("date");
            transaction.time = json.optString("time", "00:00");
            transaction.note = nullableString(json, "note");
            transaction.createdAt = json.optLong("createdAt");
            transaction.updatedAt = json.optLong("updatedAt");
            list.add(transaction);
        }
        return list;
    }

    @NonNull
    private static List<BudgetEntity> budgetList(@Nullable JSONArray array) {
        List<BudgetEntity> list = new ArrayList<>();
        if (array == null) {
            return list;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) {
                continue;
            }
            BudgetEntity budget = new BudgetEntity();
            budget.id = json.optLong("id");
            budget.year = json.optInt("year");
            budget.month = json.optInt("month");
            budget.categoryId = json.optInt("categoryId", BudgetEntity.CATEGORY_TOTAL);
            budget.amount = json.optLong("amount");
            budget.createdAt = json.optLong("createdAt");
            budget.updatedAt = json.optLong("updatedAt");
            budget.syncId = json.optString("syncId", "");
            list.add(budget);
        }
        return list;
    }

    @NonNull
    private static List<RecurringTransactionEntity> recurringList(@Nullable JSONArray array) {
        List<RecurringTransactionEntity> list = new ArrayList<>();
        if (array == null) {
            return list;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) {
                continue;
            }
            RecurringTransactionEntity item = new RecurringTransactionEntity();
            item.syncId = json.optString("syncId", "");
            item.id = json.optLong("id");
            item.name = json.optString("name", "");
            item.type = json.optInt("type", CategoryEntity.TYPE_EXPENSE);
            item.amount = json.optLong("amount");
            item.categoryId = nullableLong(json, "categoryId");
            item.accountId = nullableLong(json, "accountId");
            item.frequency = json.optInt("frequency", RecurringTransactionEntity.FREQUENCY_MONTHLY);
            item.interval = json.optInt("interval", 1);
            item.startDate = json.optLong("startDate");
            // V2（version 3）备份没有锚点日字段：按开始日期的日推导补齐，语义与迁移一致
            item.anchorDayOfMonth = json.optInt("anchorDayOfMonth", 0);
            if (item.anchorDayOfMonth <= 0 && item.startDate != 0L) {
                item.anchorDayOfMonth = DateUtil.dayOfMonthOf(item.startDate);
            }
            item.endDate = json.optLong("endDate");
            item.nextRunDate = json.optLong("nextRunDate");
            item.isEnabled = json.optBoolean("isEnabled", true);
            item.note = nullableString(json, "note");
            item.createdAt = json.optLong("createdAt");
            item.updatedAt = json.optLong("updatedAt");
            list.add(item);
        }
        return list;
    }

    @Nullable
    private static UserSettingsEntity settingsFrom(@Nullable JSONObject json) {
        if (json == null || json.length() == 0) {
            return null;
        }
        UserSettingsEntity settings = new UserSettingsEntity();
        settings.id = UserSettingsEntity.SINGLETON_ID;
        settings.theme = json.optString("theme", UserSettingsEntity.THEME_LIGHT);
        settings.firstLaunch = json.optBoolean("firstLaunch");
        settings.createdAt = json.optLong("createdAt");
        settings.updatedAt = json.optLong("updatedAt");
        return settings;
    }

    // ------------------------------------------------------------------
    // 可空字段处理：缺失或 JSON null 一律还原为 Java null
    // ------------------------------------------------------------------

    private static void putNullableLong(@NonNull JSONObject json, @NonNull String key,
                                        @Nullable Long value) throws JSONException {
        if (value != null) {
            json.put(key, value.longValue());
        }
    }

    private static void putNullableString(@NonNull JSONObject json, @NonNull String key,
                                          @Nullable String value) throws JSONException {
        if (value != null) {
            json.put(key, value);
        }
    }

    @Nullable
    private static Long nullableLong(@NonNull JSONObject json, @NonNull String key) {
        if (json.isNull(key)) {
            return null;
        }
        return json.optLong(key);
    }

    @Nullable
    private static String nullableString(@NonNull JSONObject json, @NonNull String key) {
        if (json.isNull(key)) {
            return null;
        }
        return json.optString(key, null);
    }
}
