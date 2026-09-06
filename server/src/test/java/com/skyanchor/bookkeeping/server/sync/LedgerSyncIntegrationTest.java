package com.skyanchor.bookkeeping.server.sync;

import com.skyanchor.bookkeeping.server.auth.AuthService;
import com.skyanchor.bookkeeping.server.auth.AuthUser;
import com.skyanchor.bookkeeping.server.auth.CurrentUserHolder;
import com.skyanchor.bookkeeping.server.auth.MailService;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.LoginRequest;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.RegisterRequest;
import com.skyanchor.bookkeeping.server.auth.repo.UserRepository;
import com.skyanchor.bookkeeping.server.common.ApiException;
import com.skyanchor.bookkeeping.server.ledger.LedgerController;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.AcceptInvitationResponse;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.CreateInvitationRequest;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.InvitationItem;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.InvitationsResponse;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.LedgerListResponse;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.MembersResponse;
import com.skyanchor.bookkeeping.server.ledger.repo.LedgerMemberRowRepository;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.LedgerMembershipSummary;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PullRequest;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PullResponse;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushItem;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushRequest;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushResponse;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushResultItem;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.StatusResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;

/**
 * V3.2 多账本核心链路集成测试（H2 兼容 PostgreSQL）。
 * 覆盖：账本隔离、成员鉴权与角色、邀请全生命周期、设备/账号禁用、
 * 默认账本 claim 合并、账本删除与恢复（开发计划第 5 章测试矩阵）。
 */
@SpringBootTest
@Transactional
@Rollback
class LedgerSyncIntegrationTest {

    @Autowired
    AuthService authService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    SyncController syncController;
    @Autowired
    LedgerController ledgerController;
    @Autowired
    LedgerMemberRowRepository memberRepository;
    @MockBean
    MailService mailService;

    private static final String PASSWORD = "password-123";

    // ===== 基础设施 =====

    private AuthUser registerAndVerify(String email) {
        authService.register(new RegisterRequest(email, PASSWORD));
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mailService, times(1))
                .sendVerificationEmail(eq(email), tokenCaptor.capture());
        assertTrue(authService.verifyEmail(tokenCaptor.getValue()));
        long userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        return new AuthUser(userId, email, "device-" + email.hashCode(), 1L);
    }

    private <T> T asUser(AuthUser user, java.util.function.Supplier<T> action) {
        CurrentUserHolder.set(user);
        try {
            return action.get();
        } finally {
            CurrentUserHolder.clear();
        }
    }

    /** claim 一本默认账本；返回服务端认可的 syncId（若被合并则返回 mergedInto）。 */
    private String claimLedger(AuthUser user, String syncId, boolean isDefault) {
        SyncPayload ledger = new SyncPayload();
        ledger.name = isDefault ? "我的账本" : ("账本-" + syncId.substring(0, 8));
        ledger.currency = "CNY";
        ledger.isDefault = isDefault;
        PushResponse push = asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("LEDGER", syncId, "UPSERT", 0, syncId, ledger)))));
        PushResultItem result = push.results().get(0);
        assertTrue(result.accepted(), () -> "claim 失败: " + result.errorCode());
        return result.mergedInto() != null ? result.mergedInto() : syncId;
    }

    private PushResultItem pushCategory(AuthUser user, String ledgerSyncId, String syncId,
                                        String name) {
        SyncPayload category = new SyncPayload();
        category.name = name;
        category.type = 1;
        category.clientUpdatedAt = 1_700_000_000_000L;
        PushResponse push = asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("CATEGORY", syncId, "UPSERT", 0, ledgerSyncId, category)))));
        return push.results().get(0);
    }

    /** 邀请已注册用户并以其身份接受；返回服务端认可的账本 syncId。 */
    private String inviteAndAccept(AuthUser owner, String ledgerSyncId,
                                   AuthUser invitee, String role) {
        InvitationItem invitation = asUser(owner, () -> ledgerController.invite(
                ledgerSyncId, new CreateInvitationRequest(invitee.email(), role)));
        assertNotNull(invitation.invitationId());
        AcceptInvitationResponse accepted = asUser(invitee, () -> ledgerController
                .accept(invitation.invitationId()));
        assertEquals(role, accepted.role());
        return accepted.ledgerSyncId();
    }

    // ===== 隔离 =====

    @Test
    void pull_is_scoped_to_requested_ledger() {
        AuthUser user = registerAndVerify("isolation@example.com");
        String ledgerA = claimLedger(user, "bbbbbbbb-0000-0000-0000-00000000000a", false);
        String ledgerB = claimLedger(user, "bbbbbbbb-0000-0000-0000-00000000000b", false);

        pushCategory(user, ledgerA, "bbbbbbbb-0001-0000-0000-00000000000a", "A账本分类");

        PullResponse pullB = asUser(user, () -> syncController.pull(
                new PullRequest(ledgerB, 0, 100)));
        assertTrue(pullB.changes().stream().noneMatch(c ->
                "bbbbbbbb-0001-0000-0000-00000000000a".equals(c.syncId())));
        PullResponse pullA = asUser(user, () -> syncController.pull(
                new PullRequest(ledgerA, 0, 100)));
        assertTrue(pullA.changes().stream().anyMatch(c ->
                "bbbbbbbb-0001-0000-0000-00000000000a".equals(c.syncId())));
    }

    @Test
    void non_member_push_and_pull_are_denied() {
        AuthUser owner = registerAndVerify("owner@example.com");
        String ledger = claimLedger(owner, "bbbbbbbb-0000-0000-0000-00000000000c", false);
        AuthUser stranger = registerAndVerify("stranger@example.com");

        PushResultItem pushResult = pushCategory(stranger, ledger,
                "bbbbbbbb-0001-0000-0000-00000000000c", "偷渡分类");
        assertFalse(pushResult.accepted());
        assertEquals("LEDGER_ACCESS_DENIED", pushResult.errorCode());

        ApiException e = assertThrows(ApiException.class, () -> asUser(stranger,
                () -> syncController.pull(new PullRequest(ledger, 0, 100))));
        assertEquals("LEDGER_ACCESS_DENIED", e.getCode());

        ApiException missing = assertThrows(ApiException.class, () -> asUser(stranger,
                () -> syncController.pull(new PullRequest("no-such-ledger", 0, 100))));
        assertEquals("LEDGER_NOT_FOUND", missing.getCode());
    }

    // ===== 角色 =====

    @Test
    void viewer_cannot_write_but_can_read() {
        AuthUser owner = registerAndVerify("viewer-owner@example.com");
        String ledger = claimLedger(owner, "bbbbbbbb-0000-0000-0000-00000000000d", false);
        AuthUser viewer = registerAndVerify("viewer@example.com");
        inviteAndAccept(owner, ledger, viewer, "VIEWER");

        PushResultItem result = pushCategory(viewer, ledger,
                "bbbbbbbb-0001-0000-0000-00000000000d", "只读者的写入");
        assertFalse(result.accepted());
        assertEquals("LEDGER_ROLE_REQUIRED", result.errorCode());

        // OWNER 的数据对 VIEWER 可见
        pushCategory(owner, ledger, "bbbbbbbb-0002-0000-0000-00000000000d", "所有者的分类");
        PullResponse pull = asUser(viewer, () -> syncController.pull(
                new PullRequest(ledger, 0, 100)));
        assertTrue(pull.changes().stream().anyMatch(c ->
                "bbbbbbbb-0002-0000-0000-00000000000d".equals(c.syncId())));
    }

    @Test
    void member_cannot_delete_or_rename_ledger_but_can_write() {
        AuthUser owner = registerAndVerify("member-owner@example.com");
        String ledger = claimLedger(owner, "bbbbbbbb-0000-0000-0000-00000000000e", false);
        AuthUser member = registerAndVerify("member@example.com");
        inviteAndAccept(owner, ledger, member, "MEMBER");

        PushResultItem write = pushCategory(member, ledger,
                "bbbbbbbb-0001-0000-0000-00000000000e", "成员的写入");
        assertTrue(write.accepted());

        SyncPayload tombstone = new SyncPayload();
        tombstone.name = "我的账本";
        tombstone.isDeleted = true;
        PushResponse deleteAttempt = asUser(member, () -> syncController.push(new PushRequest(
                List.of(new PushItem("LEDGER", ledger, "DELETE", 1, ledger, null)))));
        assertFalse(deleteAttempt.results().get(0).accepted());
        assertEquals("LEDGER_ROLE_REQUIRED", deleteAttempt.results().get(0).errorCode());

        SyncPayload rename = new SyncPayload();
        rename.name = "被成员改名的账本";
        rename.clientUpdatedAt = 1_700_000_300_000L;
        PushResponse renameAttempt = asUser(member, () -> syncController.push(new PushRequest(
                List.of(new PushItem("LEDGER", ledger, "UPSERT", 1, ledger, rename)))));
        assertFalse(renameAttempt.results().get(0).accepted());
        assertEquals("LEDGER_ROLE_REQUIRED", renameAttempt.results().get(0).errorCode());
    }

    @Test
    void owner_deletes_ledger_member_sees_tombstone_and_write_rejected() {
        AuthUser owner = registerAndVerify("delete-owner@example.com");
        String ledger = claimLedger(owner, "bbbbbbbb-0000-0000-0000-00000000000f", false);
        AuthUser member = registerAndVerify("delete-member@example.com");
        String memberLedger = inviteAndAccept(owner, ledger, member, "MEMBER");

        PushResponse delete = asUser(owner, () -> syncController.push(new PushRequest(
                List.of(new PushItem("LEDGER", ledger, "DELETE", 1, ledger, null)))));
        assertTrue(delete.results().get(0).accepted());

        PushResultItem writeAfterDelete = pushCategory(member, ledger,
                "bbbbbbbb-0001-0000-0000-00000000000f", "删除后的写入");
        assertFalse(writeAfterDelete.accepted());
        assertEquals("LEDGER_DELETED", writeAfterDelete.errorCode());

        // 成员仍可拉取（需要看到墓碑并切换）
        PullResponse pull = asUser(member, () -> syncController.pull(
                new PullRequest(memberLedger, 0, 100)));
        assertTrue(pull.changes().stream().anyMatch(c ->
                "LEDGER".equals(c.entityType()) && Boolean.TRUE.equals(c.payload().isDeleted)));

        // 只有 OWNER 能恢复
        ApiException denied = assertThrows(ApiException.class, () -> asUser(member,
                () -> ledgerController.restore(ledger)));
        assertEquals("LEDGER_ROLE_REQUIRED", denied.getCode());
        asUser(owner, () -> ledgerController.restore(ledger));
        PushResultItem afterRestore = pushCategory(member, ledger,
                "bbbbbbbb-0003-0000-0000-00000000000f", "恢复后的写入");
        assertTrue(afterRestore.accepted());
    }

    // ===== 默认账本合并（V3.1 → V3.2 升级链路） =====

    @Test
    void second_device_claim_merges_into_existing_default_ledger() {
        AuthUser user = registerAndVerify("claim-merge@example.com");
        String first = claimLedger(user, "bbbbbbbb-0000-0000-0000-00000000000g", true);

        // 另一台设备不知道服务端已回填/已创建默认账本，带着自己的本地默认账本 claim
        SyncPayload ledger = new SyncPayload();
        ledger.name = "我的账本";
        ledger.currency = "CNY";
        ledger.isDefault = true;
        PushResponse push = asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("LEDGER", "bbbbbbbb-0000-0000-0000-00000000000h", "UPSERT", 0,
                        "bbbbbbbb-0000-0000-0000-00000000000h", ledger)))));
        PushResultItem result = push.results().get(0);
        assertTrue(result.accepted());
        assertEquals(first, result.mergedInto());

        // 用户仍然只有一本默认账本
        LedgerListResponse ledgers = asUser(user, () -> ledgerController.ledgers());
        assertEquals(1, ledgers.ledgers().stream()
                .filter(l -> "OWNER".equals(l.role())).count());
    }

    // ===== 邀请 =====

    @Autowired
    com.skyanchor.bookkeeping.server.ledger.repo.LedgerInvitationRowRepository
            invitationRepository;

    @Test
    void invitation_full_lifecycle() {
        AuthUser owner = registerAndVerify("invite-owner@example.com");
        String ledger = claimLedger(owner, "bbbbbbbb-0000-0000-0000-00000000000i", false);

        // 邀请 → 出现在受邀人邀请列表 → 接受 → 可同步
        InvitationItem created = asUser(owner, () -> ledgerController.invite(ledger,
                new CreateInvitationRequest("invitee@example.com", "MEMBER")));
        assertEquals("invite-owner@example.com", created.inviterEmail());
        AuthUser invitee = registerAndVerify("invitee@example.com");
        InvitationsResponse invitations = asUser(invitee, () -> ledgerController.myInvitations());
        assertEquals(1, invitations.invitations().size());
        InvitationItem item = invitations.invitations().get(0);
        assertEquals(ledger, item.ledgerSyncId());

        AcceptInvitationResponse accepted = asUser(invitee,
                () -> ledgerController.accept(item.invitationId()));
        assertEquals("MEMBER", accepted.role());

        // 幂等：重复接受同一 invitationId 成功并返回成员关系
        AcceptInvitationResponse again = asUser(invitee,
                () -> ledgerController.accept(item.invitationId()));
        assertEquals("MEMBER", again.role());

        // 成员可读写共享账本：成员写入 → OWNER 拉到
        PushResultItem memberWrite = pushCategory(invitee, ledger,
                "bbbbbbbb-0001-0000-0000-00000000000i", "成员记账");
        assertTrue(memberWrite.accepted());
        PullResponse ownerPull = asUser(owner, () -> syncController.pull(
                new PullRequest(ledger, 0, 100)));
        assertTrue(ownerPull.changes().stream().anyMatch(c ->
                "bbbbbbbb-0001-0000-0000-00000000000i".equals(c.syncId())));

        // 移除成员 → 访问被拒 + status 通知
        long inviteeId = userRepository.findByEmailIgnoreCase("invitee@example.com")
                .orElseThrow().getId();
        asUser(owner, () -> ledgerController.removeMember(ledger, inviteeId));
        ApiException denied = assertThrows(ApiException.class, () -> asUser(invitee,
                () -> syncController.pull(new PullRequest(ledger, 0, 100))));
        assertEquals("LEDGER_ACCESS_DENIED", denied.getCode());
        StatusResponse status = asUser(invitee, () -> syncController.status());
        assertTrue(status.ledgerMemberships().stream().anyMatch(m ->
                ledger.equals(m.ledgerSyncId())
                        && "REMOVED".equals(m.membershipStatus())));
    }

    @Test
    void expired_invitation_cannot_be_accepted() {
        AuthUser owner = registerAndVerify("expired-owner@example.com");
        String ledger = claimLedger(owner, "bbbbbbbb-0000-0000-0000-00000000000j", false);
        InvitationItem invitation = asUser(owner, () -> ledgerController.invite(ledger,
                new CreateInvitationRequest("late@example.com", "MEMBER")));
        AuthUser invitee = registerAndVerify("late@example.com");
        // 直接把过期时间改到过去（等价于 7 天后）
        var entity = invitationRepository.findByInvitationId(invitation.invitationId())
                .orElseThrow();
        entity.setExpiresAt(java.time.Instant.now().minusSeconds(60));
        invitationRepository.save(entity);

        ApiException e = assertThrows(ApiException.class, () -> asUser(invitee,
                () -> ledgerController.accept(invitation.invitationId())));
        assertEquals("INVITATION_EXPIRED", e.getCode());
    }

    @Test
    void member_cannot_invite_but_admin_can() {
        AuthUser owner = registerAndVerify("rank-owner@example.com");
        String ledger = claimLedger(owner, "bbbbbbbb-0000-0000-0000-00000000000k", false);
        AuthUser plainMember = registerAndVerify("plain-member@example.com");
        inviteAndAccept(owner, ledger, plainMember, "MEMBER");

        ApiException denied = assertThrows(ApiException.class, () -> asUser(plainMember,
                () -> ledgerController.invite(ledger,
                        new CreateInvitationRequest("newbie@example.com", "VIEWER"))));
        assertEquals("LEDGER_ROLE_REQUIRED", denied.getCode());

        AuthUser adminMember = registerAndVerify("admin-member@example.com");
        inviteAndAccept(owner, ledger, adminMember, "ADMIN");
        InvitationItem byAdmin = asUser(adminMember, () -> ledgerController.invite(ledger,
                new CreateInvitationRequest("newbie@example.com", "VIEWER")));
        assertNotNull(byAdmin.invitationId());
        // ADMIN 不能把别人提升为 ADMIN
        ApiException overRank = assertThrows(ApiException.class, () -> asUser(adminMember,
                () -> ledgerController.invite(ledger,
                        new CreateInvitationRequest("other@example.com", "ADMIN"))));
        assertEquals("LEDGER_ROLE_REQUIRED", overRank.getCode());
    }

    // ===== 用户状态 =====

    @Test
    void disabled_user_cannot_login_refresh_or_sync() {
        AuthUser user = registerAndVerify("disabled@example.com");
        var tokens = authService.login(new LoginRequest("disabled@example.com", PASSWORD, null));
        String ledger = claimLedger(user, "bbbbbbbb-0000-0000-0000-00000000000l", false);

        var entity = userRepository.findById(user.userId()).orElseThrow();
        entity.setStatus(com.skyanchor.bookkeeping.server.auth.domain.UserEntity.Status.DISABLED
                .name());
        userRepository.save(entity);

        ApiException loginDenied = assertThrows(ApiException.class,
                () -> authService.login(new LoginRequest("disabled@example.com", PASSWORD, null)));
        assertEquals("USER_DISABLED", loginDenied.getCode());

        ApiException refreshDenied = assertThrows(ApiException.class,
                () -> authService.refresh(tokens.refreshToken()));
        assertEquals("USER_DISABLED", refreshDenied.getCode());

        ApiException syncDenied = assertThrows(ApiException.class, () -> asUser(user,
                () -> syncController.pull(new PullRequest(ledger, 0, 100))));
        assertEquals("USER_DISABLED", syncDenied.getCode());
    }

    // ===== 成员摘要 =====

    @Test
    void status_lists_memberships_with_roles() {
        AuthUser owner = registerAndVerify("status-owner@example.com");
        String ledger = claimLedger(owner, "bbbbbbbb-0000-0000-0000-00000000000m", false);
        AuthUser viewer = registerAndVerify("status-viewer@example.com");
        inviteAndAccept(owner, ledger, viewer, "VIEWER");

        StatusResponse ownerStatus = asUser(owner, () -> syncController.status());
        assertTrue(ownerStatus.ledgerMemberships().stream().anyMatch(m ->
                ledger.equals(m.ledgerSyncId()) && "OWNER".equals(m.role())));

        StatusResponse viewerStatus = asUser(viewer, () -> syncController.status());
        LedgerMembershipSummary summary = viewerStatus.ledgerMemberships().stream()
                .filter(m -> ledger.equals(m.ledgerSyncId())).findFirst().orElseThrow();
        assertEquals("VIEWER", summary.role());
        assertEquals("ACTIVE", summary.membershipStatus());

        MembersResponse members = asUser(owner, () -> ledgerController.members(ledger));
        assertEquals(2, members.members().size());
    }
}
