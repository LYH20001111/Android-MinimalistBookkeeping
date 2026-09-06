package com.skyanchor.bookkeeping.server.backup;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 服务器备份文件格式（V3.2 基线第 18 章，formatVersion 2）。
 *
 * <p>范围：users、devices、ledgers、ledger_members、5 张业务表、conflict_logs。安全边界：
 * <ul>
 *   <li>不含 refresh_tokens / email_verification_tokens —— 恢复后所有设备
 *       必须重新登录，降低备份文件泄露后的冒用风险（基线第 14 章建议）；</li>
 *   <li>不含 ledger_invitations —— 邀请是短期凭据，恢复后重新发起；</li>
 *   <li>不含 sync_changes —— 恢复时按业务行当前状态重建“每行一条最新变更”，
 *       客户端游标经 recovery_epoch 重置后全量重拉，保证不回退、可收敛。</li>
 * </ul>
 *
 * <p>id 不跨服务器复用：users/devices/ledgers 以 refId 记录备份内引用关系，
 * 恢复时按插入顺序重新分配自增 id 并重映射 userRefId / ledgerRefId；业务行之间的引用
 * 一律是 syncId，不受重映射影响。时间一律 epoch millis。
 * v1 备份（无 ledgers 段）仍可恢复：恢复时按用户补建默认账本并回填。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BackupDtos {

    private BackupDtos() {
    }

    public static final String FORMAT = "bookkeeping-server-backup";
    public static final int FORMAT_VERSION = 2;
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_API = "API";

    public record BackupCounts(long users, long devices, long ledgers, long ledgerMembers,
                               long categories, long accounts,
                               long transactions, long budgets, long recurring,
                               long conflictLogs) {
    }

    public record UserEntry(long refId, String email, String passwordHash, boolean emailVerified,
                            String status, long createdAt, long updatedAt, Long deletedAt) {
    }

    public record DeviceEntry(long refId, long userRefId, String deviceId, String deviceName,
                              String platform, String appVersion, long lastSeenAt, long createdAt,
                              Long revokedAt) {
    }

    public record LedgerEntry(long refId, long ownerUserRefId, String syncId, String name,
                              String description, String currency, boolean isDefault,
                              boolean isArchived, long version, long serverReceivedAt,
                              boolean isDeleted, Long deletedAt, long createdAt,
                              long clientUpdatedAt) {
    }

    public record LedgerMemberEntry(long ledgerRefId, long userRefId, String role, String status,
                                    Long invitedByRefId, Long invitedAt, Long acceptedAt,
                                    long createdAt, long updatedAt) {
    }

    /** 5 张业务表公共同步元数据；business 字段在各自 record 中。ledgerRefId 为 v2 新增。 */
    public record CategoryEntry(long userRefId, String syncId, long version,
                                long serverReceivedAt, long clientUpdatedAt, boolean deleted,
                                Long deletedAt, long createdAt, String name, String icon,
                                int type, int sortOrder, boolean isDefault, Long ledgerRefId) {
    }

    public record AccountEntry(long userRefId, String syncId, long version,
                               long serverReceivedAt, long clientUpdatedAt, boolean deleted,
                               Long deletedAt, long createdAt, String name, int type,
                               long initialBalance, long balance, boolean isCredit,
                               int sortOrder, boolean isArchived, Long ledgerRefId) {
    }

    public record TransactionEntry(long userRefId, String syncId, long version,
                                   long serverReceivedAt, long clientUpdatedAt, boolean deleted,
                                   Long deletedAt, long createdAt, int type, long amount,
                                   long date, String time, String note, String categorySyncId,
                                   String accountSyncId, String transferAccountSyncId,
                                   long clientCreatedAt, Long ledgerRefId) {
    }

    public record BudgetEntry(long userRefId, String syncId, long version,
                              long serverReceivedAt, long clientUpdatedAt, boolean deleted,
                              Long deletedAt, long createdAt, int year, int month,
                              String categorySyncId, long amount, Long ledgerRefId) {
    }

    public record RecurringEntry(long userRefId, String syncId, long version,
                                 long serverReceivedAt, long clientUpdatedAt, boolean deleted,
                                 Long deletedAt, long createdAt, String name, int type,
                                 long amount, String categorySyncId, String accountSyncId,
                                 int frequency, int repeatInterval, long startDate, long endDate,
                                 long nextRunDate, int anchorDayOfMonth, boolean isEnabled,
                                 String note, Long ledgerRefId) {
    }

    public record ConflictEntry(long userRefId, String entityType, String syncId,
                                String clientDeviceId, long baseVersion, long serverVersion,
                                String clientPayloadDigest, String serverPayloadDigest,
                                String winner, long createdAt) {
    }

    public record BackupFile(String format, int formatVersion, String serverVersion,
                             long createdAt, long recoveryEpoch, String trigger,
                             BackupCounts counts, List<UserEntry> users, List<DeviceEntry> devices,
                             List<LedgerEntry> ledgers, List<LedgerMemberEntry> ledgerMembers,
                             List<CategoryEntry> categories, List<AccountEntry> accounts,
                             List<TransactionEntry> transactions, List<BudgetEntry> budgets,
                             List<RecurringEntry> recurring, List<ConflictEntry> conflictLogs) {
    }

    /** 备份摘要（.meta 边车文件 / 列表接口返回），不包含业务数据。 */
    public record BackupMeta(String name, long createdAt, long sizeBytes, String serverVersion,
                             long recoveryEpoch, String trigger, BackupCounts counts) {
    }

    /** 恢复报告：恢复来源、新恢复代际与各表行数。 */
    public record RestoreReport(String restoredFrom, long newRecoveryEpoch, BackupCounts counts,
                                long serverTime) {
    }
}
