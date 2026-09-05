package com.skyanchor.bookkeeping.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.database.AppDatabase;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.data.entity.SyncEntityTypes;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;

import java.util.UUID;

/**
 * 实体 ↔ 同步载荷双向映射（纯逻辑为主，JVM 可测引用翻译规则）。
 *
 * <p>协议规则（开发计划第 3 章）：
 * <ul>
 *   <li>跨实体引用一律走 syncId；本地 id 与 syncId 在这里互译；</li>
 *   <li>预算分类引用：categoryId=0（总预算哨兵）↔ 协议 null；</li>
 *   <li>金额单位「分」（long），时间一律 epoch millis（long）；</li>
 *   <li>同步层不是账务层：不解释收支口径，只搬运状态（基线第 36 章）。</li>
 * </ul>
 */
public final class SyncPayloadMapper {

    private SyncPayloadMapper() {
    }

    /** 为尚未持有 syncId 的行生成 UUID（首次同步前的修复通道）。 */
    public static void ensureSyncId(@NonNull TransactionEntity entity) {
        if (entity.syncId == null || entity.syncId.isEmpty()) {
            entity.syncId = UUID.randomUUID().toString();
        }
    }

    /** 同上。 */
    public static void ensureSyncId(@NonNull CategoryEntity entity) {
        if (entity.syncId == null || entity.syncId.isEmpty()) {
            entity.syncId = UUID.randomUUID().toString();
        }
    }

    /** 同上。 */
    public static void ensureSyncId(@NonNull AccountEntity entity) {
        if (entity.syncId == null || entity.syncId.isEmpty()) {
            entity.syncId = UUID.randomUUID().toString();
        }
    }

    /** 同上。 */
    public static void ensureSyncId(@NonNull BudgetEntity entity) {
        if (entity.syncId == null || entity.syncId.isEmpty()) {
            entity.syncId = UUID.randomUUID().toString();
        }
    }

    /** 同上。 */
    public static void ensureSyncId(@NonNull RecurringTransactionEntity entity) {
        if (entity.syncId == null || entity.syncId.isEmpty()) {
            entity.syncId = UUID.randomUUID().toString();
        }
    }

    // ===== 实体 → 载荷（引用列翻译为 syncId，查不到的引用置 null） =====

    @Nullable
    public static ApiDtos.SyncPayload toPayload(@NonNull TransactionEntity entity,
                                                @NonNull AppDatabase db) {
        ApiDtos.SyncPayload payload = new ApiDtos.SyncPayload();
        payload.type = entity.type;
        payload.amount = entity.amount;
        payload.categorySyncId = entity.categoryId == null ? null
                : syncIdOfCategory(db, entity.categoryId);
        payload.accountSyncId = entity.accountId == null ? null
                : syncIdOfAccount(db, entity.accountId);
        payload.transferAccountSyncId = entity.transferAccountId == null ? null
                : syncIdOfAccount(db, entity.transferAccountId);
        payload.date = entity.date;
        payload.time = entity.time;
        payload.note = entity.note;
        payload.clientCreatedAt = entity.createdAt;
        payload.clientUpdatedAt = entity.updatedAt;
        payload.isDeleted = entity.isDeleted;
        return payload;
    }

    @Nullable
    public static ApiDtos.SyncPayload toPayload(@NonNull CategoryEntity entity,
                                                @NonNull AppDatabase db) {
        ApiDtos.SyncPayload payload = new ApiDtos.SyncPayload();
        payload.name = entity.name;
        payload.icon = entity.icon;
        payload.type = entity.type;
        payload.sortOrder = entity.sortOrder;
        payload.isDefault = entity.isDefault;
        payload.clientUpdatedAt = 0L; // category 表无 updated_at 列，协议允许缺省
        payload.isDeleted = entity.isDeleted;
        return payload;
    }

    @Nullable
    public static ApiDtos.SyncPayload toPayload(@NonNull AccountEntity entity,
                                                @NonNull AppDatabase db) {
        ApiDtos.SyncPayload payload = new ApiDtos.SyncPayload();
        payload.name = entity.name;
        payload.type = entity.type;
        payload.initialBalance = entity.initialBalance;
        payload.balance = entity.balance; // 仅缓存镜像，服务器不做裁决
        payload.isCredit = entity.isCredit;
        payload.sortOrder = entity.sortOrder;
        payload.isArchived = entity.isArchived;
        payload.clientUpdatedAt = entity.updatedAt;
        payload.isDeleted = entity.isDeleted;
        return payload;
    }

    @Nullable
    public static ApiDtos.SyncPayload toPayload(@NonNull BudgetEntity entity,
                                                @NonNull AppDatabase db) {
        ApiDtos.SyncPayload payload = new ApiDtos.SyncPayload();
        payload.year = entity.year;
        payload.month = entity.month;
        payload.categorySyncId = ApiDtos.budgetCategoryRef(entity.categoryId,
                entity.categoryId == 0 ? null : syncIdOfCategory(db, entity.categoryId));
        payload.amount = entity.amount;
        payload.clientUpdatedAt = entity.updatedAt;
        payload.isDeleted = entity.isDeleted;
        return payload;
    }

    @Nullable
    public static ApiDtos.SyncPayload toPayload(@NonNull RecurringTransactionEntity entity,
                                                @NonNull AppDatabase db) {
        ApiDtos.SyncPayload payload = new ApiDtos.SyncPayload();
        payload.name = entity.name;
        payload.type = entity.type;
        payload.amount = entity.amount;
        payload.categorySyncId = entity.categoryId == null ? null
                : syncIdOfCategory(db, entity.categoryId);
        payload.accountSyncId = entity.accountId == null ? null
                : syncIdOfAccount(db, entity.accountId);
        payload.frequency = entity.frequency;
        payload.repeatInterval = entity.interval;
        payload.startDate = entity.startDate;
        payload.endDate = entity.endDate;
        payload.nextRunDate = entity.nextRunDate;
        payload.anchorDayOfMonth = entity.anchorDayOfMonth;
        payload.isEnabled = entity.isEnabled;
        payload.note = entity.note;
        payload.clientUpdatedAt = entity.updatedAt;
        payload.isDeleted = entity.isDeleted;
        return payload;
    }

    // ===== 引用翻译工具 =====

    @Nullable
    public static String syncIdOfCategory(@NonNull AppDatabase db, long categoryId) {
        CategoryEntity category = db.categoryDao().getById(categoryId);
        return category == null ? null : category.syncId;
    }

    @Nullable
    public static String syncIdOfAccount(@NonNull AppDatabase db, long accountId) {
        AccountEntity account = db.accountDao().getById(accountId);
        return account == null ? null : account.syncId;
    }

    @Nullable
    public static Long localCategoryId(@NonNull AppDatabase db, @Nullable String categorySyncId) {
        if (categorySyncId == null || categorySyncId.isEmpty()) {
            return null;
        }
        CategoryEntity category = db.categoryDao().getBySyncId(categorySyncId);
        return category == null ? null : category.id;
    }

    @Nullable
    public static Long localAccountId(@NonNull AppDatabase db, @Nullable String accountSyncId) {
        if (accountSyncId == null || accountSyncId.isEmpty()) {
            return null;
        }
        AccountEntity account = db.accountDao().getBySyncId(accountSyncId);
        return account == null ? null : account.id;
    }

    /** Push 批次的实体处理顺序：分类 → 账户 → 交易 → 预算 → 周期（降低悬挂引用）。 */
    public static int orderOf(@NonNull String entityType) {
        switch (entityType) {
            case SyncEntityTypes.CATEGORY:
                return 0;
            case SyncEntityTypes.ACCOUNT:
                return 1;
            case SyncEntityTypes.TRANSACTION:
                return 2;
            case SyncEntityTypes.BUDGET:
                return 3;
            case SyncEntityTypes.RECURRING:
                return 4;
            default:
                return 5;
        }
    }
}
