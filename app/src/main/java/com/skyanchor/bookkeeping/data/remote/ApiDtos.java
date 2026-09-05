package com.skyanchor.bookkeeping.data.remote;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * 服务端 API DTO 集合（API Version 1 / Sync Protocol Version 1）。
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
        // 通用
        public Long clientUpdatedAt;
        public Boolean isDeleted;
    }

    public static class PushItem {
        public String entityType;
        public String syncId;
        public String operation;
        public long baseVersion;
        @Nullable
        public SyncPayload payload;

        public PushItem(String entityType, String syncId, String operation,
                        long baseVersion, @Nullable SyncPayload payload) {
            this.entityType = entityType;
            this.syncId = syncId;
            this.operation = operation;
            this.baseVersion = baseVersion;
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
    }

    public static class PullRequest {
        public long sinceChangeId;
        public int limit;

        public PullRequest(long sinceChangeId, int limit) {
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
    }

    /** 预算的分类引用协议值：null = 总预算（对应本地 categoryId=0 哨兵）。 */
    @Nullable
    public static String budgetCategoryRef(long categoryId, @Nullable String categorySyncId) {
        return categoryId == 0 ? null : categorySyncId;
    }
}
