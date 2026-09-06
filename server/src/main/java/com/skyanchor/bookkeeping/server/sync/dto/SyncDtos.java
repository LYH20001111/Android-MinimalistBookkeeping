package com.skyanchor.bookkeeping.server.sync.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.skyanchor.bookkeeping.server.sync.SyncPayload;

import java.util.List;

/** 同步协议请求/响应体（Sync Protocol Version 2：账本级隔离与共享，见开发计划第 3 章）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SyncDtos {

    private SyncDtos() {
    }

    public static final String ENTITY_CATEGORY = "CATEGORY";
    public static final String ENTITY_ACCOUNT = "ACCOUNT";
    public static final String ENTITY_TRANSACTION = "TRANSACTION";
    public static final String ENTITY_BUDGET = "BUDGET";
    public static final String ENTITY_RECURRING = "RECURRING";
    public static final String ENTITY_LEDGER = "LEDGER";

    /** ledgerId = 账本 syncId（V3.2）：每个变更显式声明所属账本，服务端逐一校验成员与角色。 */
    public record PushItem(String entityType, String syncId, String operation,
                           long baseVersion, String ledgerId, SyncPayload payload) {
    }

    public record PushRequest(List<PushItem> changes) {
    }

    public record PushResultItem(String entityType, String syncId, boolean accepted,
                                 boolean conflicted, long version, long serverReceivedAt,
                                 SyncPayload payload, String errorCode, String mergedInto) {
    }

    public record PushResponse(List<PushResultItem> results, long serverTime,
                               long recoveryEpoch) {
    }

    /** ledgerId = 账本 syncId：Pull 只返回该账本的变更（服务端隔离，禁客户端自滤）。 */
    public record PullRequest(String ledgerId, long sinceChangeId, int limit) {
    }

    public record ChangeItem(long changeId, String entityType, String syncId, String operation,
                             long version, long serverReceivedAt, SyncPayload payload) {
    }

    public record PullResponse(List<ChangeItem> changes, long lastChangeId,
                               boolean hasMore, long serverTime, long recoveryEpoch) {
    }

    public record BootstrapSummaryResponse(boolean hasCloudData, Counts counts, long serverTime) {
    }

    public record Counts(long category, long account, long transaction,
                         long budget, long recurring) {
    }

    /** 成员关系摘要（V3.2 基线第 25 章）：客户端据此对账本地账本表并提示邀请/移除/角色变化。 */
    public record LedgerMembershipSummary(String ledgerSyncId, String name, String description,
                                          String currency, Long ownerUserId, boolean isDefault,
                                          boolean isArchived, boolean isDeleted, String role,
                                          String membershipStatus, long version) {
    }

    public record StatusResponse(long serverTime, boolean emailVerified,
                                 String serverVersion, long recoveryEpoch,
                                 List<LedgerMembershipSummary> ledgerMemberships) {
    }

    // ===== V3.1 冲突历史（基线第 26 章：自动收敛 + 可事后查看） =====

    public record ConflictItem(long id, String entityType, String syncId,
                               String clientDeviceId, long baseVersion, long serverVersion,
                               String winner, long createdAt) {
    }

    public record ConflictsResponse(List<ConflictItem> conflicts, long serverTime) {
    }
}
