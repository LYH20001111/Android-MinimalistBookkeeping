package com.skyanchor.bookkeeping.data.remote;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * 服务端 API DTO 集合（API Version 2 / Sync Protocol Version 2：账本级隔离与共享）。
 * 字段名与服务器 JSON 对齐；Gson 宽松解析未知字段（协议向前兼容）。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    // ===== Auth =====

    public static class DeviceInfo {
        public String deviceId;
        public String deviceName;
        public String platform;
        public String appVersion;

        public DeviceInfo(String deviceId, String deviceName, String platform, String appVersion) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.platform = platform;
            this.appVersion = appVersion;
        }
    }

    public static class LoginRequest {
        public String email;
        public String password;
        public DeviceInfo device;

        public LoginRequest(String email, String password, DeviceInfo device) {
            this.email = email;
            this.password = password;
            this.device = device;
        }
    }

    public static class RegisterRequest {
        public String email;
        public String password;

        public RegisterRequest(String email, String password) {
            this.email = email;
            this.password = password;
        }
    }

    public static class RefreshRequest {
        public String refreshToken;

        public RefreshRequest(String refreshToken) {
            this.refreshToken = refreshToken;
        }
    }

    public static class DeleteAccountRequest {
        public String password;

        public DeleteAccountRequest(String password) {
            this.password = password;
        }
    }

    public static class AuthResponse {
        public String accessToken;
        public String refreshToken;
        public String email;
        public boolean emailVerified;
        public DeviceDto device;
    }

    public static class RefreshResponse {
        public String accessToken;
        public String refreshToken;
        public long expiresAt;
    }

    public static class DeviceDto {
        public long id;
        public String deviceId;
        public String deviceName;
        public String platform;
        public String appVersion;
        public long lastSeenAt;
        public long createdAt;
        public boolean revoked;
        public boolean current;
    }

    public static class SimpleResponse {
        public boolean ok;
        public String message;
    }

    /** 统一错误体：{ "error": { "code": "...", "message": "..." } }。 */
    public static class ErrorEnvelope {
        public ErrorBody error;

        public static class ErrorBody {
            public String code;
            public String message;
        }
    }

    // ===== Sync =====

    public static class SyncPayload {
        // Category
        public String name;
        public String icon;
        public Integer type;
        public Integer sortOrder;
        public Boolean isDefault;
        // Account
        public Long initialBalance;
        public Long balance;
        public Boolean isCredit;
        public Boolean isArchived;
        // Transaction / Recurring
        public Long amount;
        public String categorySyncId;
        public String accountSyncId;
        public String transferAccountSyncId;
        public Long date;
        public String time;
        public String note;
        public Long clientCreatedAt;
        // Budget
        public Integer year;
        public Integer month;
        // Recurring
        public Integer frequency;
        public Integer repeatInterval;
        public Long startDate;
        public Long endDate;
        public Long nextRunDate;
        public Integer anchorDayOfMonth;
        public Boolean isEnabled;
        // Ledger（entityType=LEDGER 专用；name/isArchived/isDefault/isDeleted 复用上方字段）
        public String description;
        public String currency;
        /** 账本所有者的服务器用户 id：仅服务端下发，客户端只读展示。 */
        public Long ownerUserId;
        // 通用
        public Long clientUpdatedAt;
        public Boolean isDeleted;
        /** 软删发生时间（epoch millis，V3.1）；仅 isDeleted=true 有意义，恢复置回 null。 */
        public Long deletedAt;
    }

    public static class PushItem {
        public String entityType;
        public String syncId;
        public String operation;
        public long baseVersion;
        /** 所属账本 syncId（V3.2 基线 10.1）；LEDGER 实体传自身 syncId。 */
        @Nullable
        public String ledgerId;
        @Nullable
        public SyncPayload payload;

        public PushItem(String entityType, String syncId, String operation,
                        long baseVersion, @Nullable String ledgerId,
                        @Nullable SyncPayload payload) {
            this.entityType = entityType;
            this.syncId = syncId;
            this.operation = operation;
            this.baseVersion = baseVersion;
            this.ledgerId = ledgerId;
            this.payload = payload;
        }
    }

    public static class PushRequest {
        public List<PushItem> changes;

        public PushRequest(List<PushItem> changes) {
            this.changes = changes;
        }
    }

    public static class PushResultItem {
        public String entityType;
        public String syncId;
        public boolean accepted;
        public boolean conflicted;
        public long version;
        public long serverReceivedAt;
        @Nullable
        public SyncPayload payload;
        @Nullable
        public String errorCode;
        /**
         * 非空 = 服务器按业务键（同名分类/账户、同年月分类预算）把本条合并进了
         * 已有实体，值即已有实体的 syncId。客户端应把本地行身份重映射过去
         * （本地已有该实体时改为改指向并删除重复行）。
         */
        @Nullable
        public String mergedInto;
    }

    public static class PushResponse {
        public List<PushResultItem> results;
        public long serverTime;
        /** 服务器恢复代际（V3.1）：与本机记录不一致说明服务器恢复过备份，需重置游标重新收敛。 */
        public long recoveryEpoch;
    }

    public static class PullRequest {
        /** 目标账本 syncId（V3.2 基线 10.3）：Pull 只返回该账本变更，服务端隔离。 */
        @Nullable
        public String ledgerId;
        public long sinceChangeId;
        public int limit;

        public PullRequest(@Nullable String ledgerId, long sinceChangeId, int limit) {
            this.ledgerId = ledgerId;
            this.sinceChangeId = sinceChangeId;
            this.limit = limit;
        }
    }

    public static class ChangeItem {
        public long changeId;
        public String entityType;
        public String syncId;
        public String operation;
        public long version;
        public long serverReceivedAt;
        @Nullable
        public SyncPayload payload;
    }

    public static class PullResponse {
        public List<ChangeItem> changes;
        public long lastChangeId;
        public boolean hasMore;
        public long serverTime;
        public long recoveryEpoch;
    }

    public static class BootstrapSummaryResponse {
        public boolean hasCloudData;
        public Counts counts;
        public long serverTime;

        public static class Counts {
            public long category;
            public long account;
            public long transaction;
            public long budget;
            public long recurring;
        }
    }

    public static class StatusResponse {
        public long serverTime;
        public boolean emailVerified;
        /** 服务器应用版本（仅展示用，不参与业务判断）。 */
        public String serverVersion;
        public long recoveryEpoch;
        /** 我的成员关系摘要（V3.2 基线第 25 章）：对账本地账本表并提示邀请/移除/角色变化。 */
        @Nullable
        public List<LedgerMembershipSummary> ledgerMemberships;
    }

    /** 成员关系摘要：账本元数据 + 我在其中扮演的角色/状态。 */
    public static class LedgerMembershipSummary {
        public String ledgerSyncId;
        public String name;
        public String description;
        public String currency;
        public Long ownerUserId;
        public boolean isDefault;
        public boolean isArchived;
        public boolean isDeleted;
        /** OWNER / ADMIN / MEMBER / VIEWER。 */
        public String role;
        /** ACTIVE / REMOVED。 */
        public String membershipStatus;
        public long version;
    }

    // ===== V3.2 账本 / 成员 / 邀请 REST（基线第 8、9 章） =====

    public static class LedgerSummary {
        public String syncId;
        public String name;
        public String description;
        public String currency;
        public Long ownerUserId;
        public boolean isDefault;
        public boolean isArchived;
        public boolean isDeleted;
        public String role;
        public String membershipStatus;
        public long version;
        public long memberCount;
    }

    public static class LedgerListResponse {
        public List<LedgerSummary> ledgers;
        public long serverTime;
    }

    public static class MemberItem {
        public long userId;
        public String email;
        public String role;
        public String status;
        public long joinedAtMillis;
    }

    public static class MembersResponse {
        public List<MemberItem> members;
        public long serverTime;
    }

    public static class CreateInvitationRequest {
        public String email;
        public String role;

        public CreateInvitationRequest(String email, String role) {
            this.email = email;
            this.role = role;
        }
    }

    public static class InvitationItem {
        public String invitationId;
        public String ledgerSyncId;
        public String ledgerName;
        public String inviterEmail;
        public String role;
        public long createdAt;
        public long expiresAt;
    }

    public static class InvitationsResponse {
        public List<InvitationItem> invitations;
        public long serverTime;
    }

    public static class UpdateMemberRequest {
        public String role;

        public UpdateMemberRequest(String role) {
            this.role = role;
        }
    }

    public static class AcceptInvitationResponse {
        public String ledgerSyncId;
        public String ledgerName;
        public String role;
        public long serverTime;
    }

    // ===== V3.1 服务器健康（基线第 8/10 章，公开端点） =====

    public static class ServerHealthResponse {
        public String status;
        public String serverVersion;
        public int apiVersion;
        public int syncProtocolVersion;
        public String database;
        public Storage storage;

        public static class Storage {
            public String status;
            public long totalBytes;
            public long freeBytes;
        }
    }

    // ===== V3.1 冲突历史（基线第 26 章） =====

    public static class ConflictItem {
        public long id;
        public String entityType;
        public String syncId;
        public String clientDeviceId;
        public long baseVersion;
        public long serverVersion;
        /** CLIENT = 保留本机修改；SERVER = 采用服务器版本。 */
        public String winner;
        public long createdAt;
    }

    public static class ConflictsResponse {
        public List<ConflictItem> conflicts;
        public long serverTime;
    }

    /** 预算的分类引用协议值：null = 总预算（对应本地 categoryId=0 哨兵）。 */
    @Nullable
    public static String budgetCategoryRef(long categoryId, @Nullable String categorySyncId) {
        return categoryId == 0 ? null : categorySyncId;
    }
}
