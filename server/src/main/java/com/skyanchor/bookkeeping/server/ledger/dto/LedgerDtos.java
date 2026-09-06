package com.skyanchor.bookkeeping.server.ledger.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 账本/成员/邀请 REST 接口的请求与响应体（V3.2 基线第 8、9 章）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class LedgerDtos {

    private LedgerDtos() {
    }

    public record LedgerSummary(String syncId, String name, String description, String currency,
                                Long ownerUserId, boolean isDefault, boolean isArchived,
                                boolean isDeleted, String role, String membershipStatus,
                                long version, long memberCount) {
    }

    public record LedgerListResponse(List<LedgerSummary> ledgers, long serverTime) {
    }

    public record MemberItem(long userId, String email, String role, String status,
                             long joinedAtMillis) {
    }

    public record MembersResponse(List<MemberItem> members, long serverTime) {
    }

    public record CreateInvitationRequest(String email, String role) {
    }

    public record InvitationItem(String invitationId, String ledgerSyncId, String ledgerName,
                                 String inviterEmail, String role, long createdAt,
                                 long expiresAt) {
    }

    public record InvitationsResponse(List<InvitationItem> invitations, long serverTime) {
    }

    public record UpdateMemberRequest(String role) {
    }

    public record AcceptInvitationResponse(String ledgerSyncId, String ledgerName,
                                           String role, long serverTime) {
    }

    public record SimpleResponse(boolean success, String message) {
    }
}
