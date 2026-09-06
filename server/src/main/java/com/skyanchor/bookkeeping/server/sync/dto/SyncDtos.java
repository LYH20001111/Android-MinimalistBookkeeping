package com.skyanchor.bookkeeping.server.sync.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.skyanchor.bookkeeping.server.sync.SyncPayload;

import java.util.List;

/** 同步协议请求/响应体（Sync Protocol Version 1，见开发计划第 3 章）。 */
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

    public record PushItem(String entityType, String syncId, String operation,
                           long baseVersion, SyncPayload payload) {
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

    public record PullRequest(long sinceChangeId, int limit) {
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

    public record StatusResponse(long serverTime, boolean emailVerified,
                                 String serverVersion, long recoveryEpoch) {
    }

    // ===== V3.1 冲突历史（基线第 26 章：自动收敛 + 可事后查看） =====

    public record ConflictItem(long id, String entityType, String syncId,
                               String clientDeviceId, long baseVersion, long serverVersion,
                               String winner, long createdAt) {
    }

    public record ConflictsResponse(List<ConflictItem> conflicts, long serverTime) {
    }
}
