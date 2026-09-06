package com.skyanchor.bookkeeping.server.ledger;

import com.skyanchor.bookkeeping.server.auth.AuthUser;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.AcceptInvitationResponse;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.CreateInvitationRequest;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.InvitationItem;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.InvitationsResponse;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.LedgerListResponse;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.MembersResponse;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.SimpleResponse;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.UpdateMemberRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账本 / 成员 / 邀请 REST API（V3.2 基线第 8、9 章）。
 * 全部要求登录 + 邮箱已验证 + 账号未被禁用；角色检查在 LedgerService。
 */
@RestController
@RequestMapping("/api/v1")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/ledgers")
    public LedgerListResponse ledgers() {
        return ledgerService.listLedgers(AuthUser.current().userId());
    }

    @GetMapping("/ledgers/{ledgerSyncId}/members")
    public MembersResponse members(@PathVariable String ledgerSyncId) {
        return ledgerService.listMembers(AuthUser.current().userId(), ledgerSyncId);
    }

    @PostMapping("/ledgers/{ledgerSyncId}/invitations")
    public InvitationItem invite(@PathVariable String ledgerSyncId,
                                 @RequestBody CreateInvitationRequest request) {
        return ledgerService.invite(AuthUser.current().userId(), ledgerSyncId, request);
    }

    @GetMapping("/invitations")
    public InvitationsResponse myInvitations() {
        return ledgerService.listMyInvitations(AuthUser.current().userId());
    }

    @PostMapping("/invitations/{invitationId}/accept")
    public AcceptInvitationResponse accept(@PathVariable String invitationId) {
        return ledgerService.accept(AuthUser.current().userId(), invitationId);
    }

    @PostMapping("/invitations/{invitationId}/decline")
    public SimpleResponse decline(
            @PathVariable String invitationId) {
        ledgerService.decline(AuthUser.current().userId(), invitationId);
        return new SimpleResponse(
                true, "已拒绝邀请");
    }

    @DeleteMapping("/ledgers/{ledgerSyncId}/members/{userId}")
    public SimpleResponse removeMember(
            @PathVariable String ledgerSyncId, @PathVariable long userId) {
        ledgerService.removeMember(AuthUser.current().userId(), ledgerSyncId, userId);
        return new SimpleResponse(
                true, "已移除成员");
    }

    @PatchMapping("/ledgers/{ledgerSyncId}/members/{userId}")
    public SimpleResponse updateMemberRole(
            @PathVariable String ledgerSyncId, @PathVariable long userId,
            @RequestBody UpdateMemberRequest request) {
        ledgerService.updateMemberRole(AuthUser.current().userId(), ledgerSyncId, userId,
                request == null ? null : request.role());
        return new SimpleResponse(
                true, "已更新角色");
    }

    @PostMapping("/ledgers/{ledgerSyncId}/restore")
    public SimpleResponse restore(
            @PathVariable String ledgerSyncId) {
        ledgerService.restoreLedger(AuthUser.current().userId(), ledgerSyncId);
        return new SimpleResponse(
                true, "账本已恢复");
    }
}
