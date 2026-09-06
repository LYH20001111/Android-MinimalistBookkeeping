package com.skyanchor.bookkeeping.server.ledger;

import com.skyanchor.bookkeeping.server.auth.AuthUser;
import com.skyanchor.bookkeeping.server.auth.domain.UserEntity;
import com.skyanchor.bookkeeping.server.auth.repo.UserRepository;
import com.skyanchor.bookkeeping.server.common.ApiException;
import com.skyanchor.bookkeeping.server.ledger.domain.LedgerInvitationRow;
import com.skyanchor.bookkeeping.server.ledger.domain.LedgerMemberRow;
import com.skyanchor.bookkeeping.server.ledger.domain.LedgerRow;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.AcceptInvitationResponse;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.CreateInvitationRequest;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.InvitationItem;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.InvitationsResponse;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.LedgerSummary;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.LedgerListResponse;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.MemberItem;
import com.skyanchor.bookkeeping.server.ledger.dto.LedgerDtos.MembersResponse;
import com.skyanchor.bookkeeping.server.ledger.repo.LedgerInvitationRowRepository;
import com.skyanchor.bookkeeping.server.ledger.repo.LedgerMemberRowRepository;
import com.skyanchor.bookkeeping.server.ledger.repo.LedgerRowRepository;
import com.skyanchor.bookkeeping.server.sync.domain.SyncChangeRow;
import com.skyanchor.bookkeeping.server.sync.repo.SyncChangeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 账本 / 成员 / 邀请 REST 服务（V3.2 基线第 8、9 章）。
 *
 * <p>成员关系不走同步通道：服务端是唯一权威，客户端经这里查询与变更，
 * 再通过 sync/status 的成员摘要感知变化。所有写操作都做角色检查：
 * 邀请与移除普通成员、修改普通成员角色 = ADMIN+；删除/恢复账本、
 * 移除或修改 ADMIN = 仅 OWNER。
 */
@Service
public class LedgerService {

    /** 邀请默认有效期（基线 8.2）：7 天。 */
    private static final long INVITATION_TTL_DAYS = 7;

    private final LedgerRowRepository ledgerRepository;
    private final LedgerMemberRowRepository memberRepository;
    private final LedgerInvitationRowRepository invitationRepository;
    private final UserRepository userRepository;
    private final SyncChangeRepository changeRepository;

    public LedgerService(LedgerRowRepository ledgerRepository,
                         LedgerMemberRowRepository memberRepository,
                         LedgerInvitationRowRepository invitationRepository,
                         UserRepository userRepository,
                         SyncChangeRepository changeRepository) {
        this.ledgerRepository = ledgerRepository;
        this.memberRepository = memberRepository;
        this.invitationRepository = invitationRepository;
        this.userRepository = userRepository;
        this.changeRepository = changeRepository;
    }

    // ===== 查询 =====

    @Transactional(readOnly = true)
    public LedgerListResponse listLedgers(long userId) {
        requireActiveUser(userId);
        List<LedgerSummary> summaries = new ArrayList<>();
        for (LedgerMemberRow member : memberRepository.findAllByUserId(userId)) {
            ledgerRepository.findById(member.getLedgerId())
                    .ifPresent(ledger -> summaries.add(toSummary(ledger, member)));
        }
        return new LedgerListResponse(summaries, System.currentTimeMillis());
    }

    @Transactional(readOnly = true)
    public MembersResponse listMembers(long userId, String ledgerSyncId) {
        LedgerRow ledger = requireMembership(userId, ledgerSyncId, null);
        List<MemberItem> members = new ArrayList<>();
        for (LedgerMemberRow member : memberRepository.findByLedgerIdOrderByCreatedAtAsc(ledger.getId())) {
            String email = userRepository.findById(member.getUserId())
                    .map(UserEntity::getEmail).orElse("");
            members.add(new MemberItem(member.getUserId(), email, member.getRole(),
                    member.getStatus(),
                    member.getAcceptedAt() != null ? member.getAcceptedAt().toEpochMilli() : 0));
        }
        return new MembersResponse(members, System.currentTimeMillis());
    }

    @Transactional(readOnly = true)
    public InvitationsResponse listMyInvitations(long userId) {
        UserEntity user = requireActiveUser(userId);
        List<InvitationItem> items = new ArrayList<>();
        for (LedgerInvitationRow invitation : invitationRepository
                .findByInviteeEmailIgnoreCaseAndStatusOrderByCreatedAtDesc(
                        user.getEmail(), LedgerInvitationRow.STATUS_PENDING)) {
            LedgerRow ledger = ledgerRepository.findById(invitation.getLedgerId()).orElse(null);
            if (ledger == null || ledger.isDeleted()) {
                continue;
            }
            String inviterEmail = userRepository.findById(invitation.getInviterUserId())
                    .map(UserEntity::getEmail).orElse("");
            items.add(new InvitationItem(invitation.getInvitationId(), ledger.getSyncId(),
                    ledger.getName(), inviterEmail, invitation.getRole(),
                    invitation.getCreatedAt().toEpochMilli(),
                    invitation.getExpiresAt().toEpochMilli()));
        }
        return new InvitationsResponse(items, System.currentTimeMillis());
    }

    // ===== 邀请 =====

    @Transactional
    public InvitationItem invite(long userId, String ledgerSyncId,
                                 CreateInvitationRequest request) {
        LedgerRow ledger = requireMembership(userId, ledgerSyncId, LedgerMemberRow.ROLE_ADMIN);
        if (ledger.isDeleted()) {
            throw ApiException.forbidden("LEDGER_DELETED", "账本已删除，不能邀请成员");
        }
        if (request == null || request.email() == null || request.email().isBlank()) {
            throw ApiException.badRequest("被邀请邮箱不能为空");
        }
        String email = request.email().trim();
        String role = request.role() == null || request.role().isBlank()
                ? LedgerMemberRow.ROLE_MEMBER : request.role();
        if (LedgerMemberRow.rank(role) < 0) {
            throw ApiException.badRequest("未知角色: " + role);
        }
        // 只能授予低于自身角色的权限（OWNER 才能邀请 ADMIN；ADMIN 只能邀 MEMBER/VIEWER，
        // 基线第 9 章：ADMIN 管理普通成员，ADMIN 的任免属于 OWNER）
        LedgerMemberRow inviter = memberRepository
                .findByLedgerIdAndUserId(ledger.getId(), userId).orElseThrow();
        if (LedgerMemberRow.rank(role) >= LedgerMemberRow.rank(inviter.getRole())) {
            throw ApiException.forbidden("LEDGER_ROLE_REQUIRED", "不能授予不低于自身角色的权限");
        }
        // 重新邀请：作废同账本同邮箱的旧 pending 邀请（基线 8.2 允许重发）
        for (LedgerInvitationRow old : invitationRepository
                .findByLedgerIdAndInviteeEmailIgnoreCaseAndStatus(
                        ledger.getId(), email, LedgerInvitationRow.STATUS_PENDING)) {
            old.setStatus(LedgerInvitationRow.STATUS_DECLINED);
            invitationRepository.save(old);
        }
        LedgerInvitationRow invitation = new LedgerInvitationRow();
        invitation.setInvitationId(UUID.randomUUID().toString());
        invitation.setLedgerId(ledger.getId());
        invitation.setInviterUserId(userId);
        invitation.setInviteeEmail(email);
        invitation.setRole(role);
        invitation.setStatus(LedgerInvitationRow.STATUS_PENDING);
        invitation.setCreatedAt(Instant.now());
        invitation.setExpiresAt(Instant.now().plus(INVITATION_TTL_DAYS, ChronoUnit.DAYS));
        invitationRepository.save(invitation);
        String inviterEmail = userRepository.findById(userId)
                .map(UserEntity::getEmail).orElse("");
        return new InvitationItem(invitation.getInvitationId(), ledger.getSyncId(),
                ledger.getName(), inviterEmail, invitation.getRole(),
                invitation.getCreatedAt().toEpochMilli(),
                invitation.getExpiresAt().toEpochMilli());
    }

    /** 接受邀请：一次性、限期内、邮箱匹配；重复接受同一 invitationId 幂等（基线第 32 章）。 */
    @Transactional
    public AcceptInvitationResponse accept(long userId, String invitationId) {
        UserEntity user = requireActiveUser(userId);
        LedgerInvitationRow invitation = invitationRepository.findByInvitationId(
                        invitationId == null ? "" : invitationId)
                .orElseThrow(() -> ApiException.badRequest("邀请不存在"));
        if (LedgerInvitationRow.STATUS_ACCEPTED.equals(invitation.getStatus())) {
            // 幂等：已接受过的邀请重复接受，返回当前成员关系
            LedgerRow ledger = ledgerRepository.findById(invitation.getLedgerId())
                    .orElseThrow(() -> ApiException.badRequest("账本不存在"));
            LedgerMemberRow member = memberRepository
                    .findByLedgerIdAndUserId(ledger.getId(), userId)
                    .filter(m -> LedgerMemberRow.STATUS_ACTIVE.equals(m.getStatus()))
                    .orElseThrow(() -> ApiException.forbidden("LEDGER_ACCESS_DENIED",
                            "你不是该账本成员"));
            return new AcceptInvitationResponse(ledger.getSyncId(), ledger.getName(),
                    member.getRole(), System.currentTimeMillis());
        }
        if (!LedgerInvitationRow.STATUS_PENDING.equals(invitation.getStatus())) {
            throw ApiException.conflict("邀请已被处理");
        }
        if (invitation.expired(Instant.now())) {
            invitation.setStatus(LedgerInvitationRow.STATUS_DECLINED);
            invitationRepository.save(invitation);
            throw ApiException.forbidden("INVITATION_EXPIRED", "邀请已过期，请让对方重新邀请");
        }
        if (!user.getEmail().equalsIgnoreCase(invitation.getInviteeEmail())) {
            throw ApiException.forbidden("LEDGER_ACCESS_DENIED", "该邀请不是发给当前账号的");
        }
        LedgerRow ledger = ledgerRepository.findById(invitation.getLedgerId())
                .filter(l -> !l.isDeleted())
                .orElseThrow(() -> ApiException.forbidden("LEDGER_DELETED", "账本已删除"));
        Instant now = Instant.now();
        LedgerMemberRow member = memberRepository
                .findByLedgerIdAndUserId(ledger.getId(), userId).orElse(null);
        if (member == null) {
            member = new LedgerMemberRow();
            member.setLedgerId(ledger.getId());
            member.setUserId(userId);
            member.setCreatedAt(now);
        }
        member.setRole(invitation.getRole());
        member.setStatus(LedgerMemberRow.STATUS_ACTIVE);
        member.setInvitedBy(invitation.getInviterUserId());
        member.setInvitedAt(invitation.getCreatedAt());
        member.setAcceptedAt(now);
        member.setUpdatedAt(now);
        memberRepository.save(member);
        invitation.setStatus(LedgerInvitationRow.STATUS_ACCEPTED);
        invitation.setAcceptedAt(now);
        invitation.setAcceptedBy(userId);
        invitationRepository.save(invitation);
        return new AcceptInvitationResponse(ledger.getSyncId(), ledger.getName(),
                member.getRole(), System.currentTimeMillis());
    }

    @Transactional
    public void decline(long userId, String invitationId) {
        UserEntity user = requireActiveUser(userId);
        LedgerInvitationRow invitation = invitationRepository.findByInvitationId(
                        invitationId == null ? "" : invitationId)
                .orElseThrow(() -> ApiException.badRequest("邀请不存在"));
        if (!user.getEmail().equalsIgnoreCase(invitation.getInviteeEmail())) {
            throw ApiException.forbidden("LEDGER_ACCESS_DENIED", "该邀请不是发给当前账号的");
        }
        if (!LedgerInvitationRow.STATUS_PENDING.equals(invitation.getStatus())) {
            return; // 幂等
        }
        invitation.setStatus(LedgerInvitationRow.STATUS_DECLINED);
        invitationRepository.save(invitation);
    }

    // ===== 成员管理 =====

    @Transactional
    public void removeMember(long userId, String ledgerSyncId, long targetUserId) {
        LedgerRow ledger = requireMembership(userId, ledgerSyncId, LedgerMemberRow.ROLE_ADMIN);
        LedgerMemberRow operator = memberRepository
                .findByLedgerIdAndUserId(ledger.getId(), userId).orElseThrow();
        LedgerMemberRow target = memberRepository
                .findByLedgerIdAndUserId(ledger.getId(), targetUserId)
                .filter(m -> LedgerMemberRow.STATUS_ACTIVE.equals(m.getStatus()))
                .orElseThrow(() -> ApiException.badRequest("成员不存在"));
        if (LedgerMemberRow.ROLE_OWNER.equals(target.getRole())) {
            throw ApiException.forbidden("LEDGER_ROLE_REQUIRED", "不能移除账本所有者");
        }
        // ADMIN 只能操作 MEMBER/VIEWER；动 ADMIN 必须 OWNER
        if (!LedgerMemberRow.ROLE_OWNER.equals(operator.getRole())
                && LedgerMemberRow.rank(target.getRole()) >= LedgerMemberRow.rank(LedgerMemberRow.ROLE_ADMIN)) {
            throw ApiException.forbidden("LEDGER_ROLE_REQUIRED", "只有账本所有者可以移除管理员");
        }
        target.setStatus(LedgerMemberRow.STATUS_REMOVED);
        target.setUpdatedAt(Instant.now());
        memberRepository.save(target);
    }

    @Transactional
    public void updateMemberRole(long userId, String ledgerSyncId, long targetUserId,
                                 String role) {
        LedgerRow ledger = requireMembership(userId, ledgerSyncId, LedgerMemberRow.ROLE_ADMIN);
        if (role == null || LedgerMemberRow.rank(role) < 0
                || LedgerMemberRow.ROLE_OWNER.equals(role)) {
            throw ApiException.badRequest("角色不合法");
        }
        LedgerMemberRow operator = memberRepository
                .findByLedgerIdAndUserId(ledger.getId(), userId).orElseThrow();
        LedgerMemberRow target = memberRepository
                .findByLedgerIdAndUserId(ledger.getId(), targetUserId)
                .filter(m -> LedgerMemberRow.STATUS_ACTIVE.equals(m.getStatus()))
                .orElseThrow(() -> ApiException.badRequest("成员不存在"));
        if (LedgerMemberRow.ROLE_OWNER.equals(target.getRole())) {
            throw ApiException.forbidden("LEDGER_ROLE_REQUIRED", "不能修改账本所有者的角色");
        }
        if (!LedgerMemberRow.ROLE_OWNER.equals(operator.getRole())
                && LedgerMemberRow.rank(target.getRole()) >= LedgerMemberRow.rank(LedgerMemberRow.ROLE_ADMIN)) {
            throw ApiException.forbidden("LEDGER_ROLE_REQUIRED", "只有账本所有者可以修改管理员角色");
        }
        if (!LedgerMemberRow.ROLE_OWNER.equals(operator.getRole())
                && LedgerMemberRow.rank(role) >= LedgerMemberRow.rank(LedgerMemberRow.ROLE_ADMIN)) {
            throw ApiException.forbidden("LEDGER_ROLE_REQUIRED", "只有账本所有者可以授予管理员");
        }
        target.setRole(role);
        target.setUpdatedAt(Instant.now());
        memberRepository.save(target);
    }

    // ===== 账本恢复（删除走同步 tombstone，恢复走 REST，仅 OWNER，基线 12.2） =====

    @Transactional
    public void restoreLedger(long userId, String ledgerSyncId) {
        requireActiveUser(userId);
        LedgerRow ledger = ledgerRepository.findBySyncId(ledgerSyncId == null ? "" : ledgerSyncId)
                .orElseThrow(() -> ApiException.forbidden("LEDGER_NOT_FOUND", "账本不存在"));
        LedgerMemberRow member = memberRepository
                .findByLedgerIdAndUserId(ledger.getId(), userId)
                .filter(m -> LedgerMemberRow.STATUS_ACTIVE.equals(m.getStatus()))
                .orElseThrow(() -> ApiException.forbidden("LEDGER_ACCESS_DENIED",
                        "你不是该账本成员"));
        if (!LedgerMemberRow.ROLE_OWNER.equals(member.getRole())) {
            throw ApiException.forbidden("LEDGER_ROLE_REQUIRED", "只有账本所有者可以恢复账本");
        }
        if (!ledger.isDeleted()) {
            return; // 幂等
        }
        ledger.setDeleted(false);
        ledger.setDeletedAt(null);
        ledger.setVersion(ledger.getVersion() + 1);
        ledger.setServerReceivedAt(Instant.now());
        ledgerRepository.save(ledger);
        SyncChangeRow change = new SyncChangeRow();
        change.setLedgerId(ledger.getId());
        change.setUserId(userId);
        change.setEntityType("LEDGER");
        change.setSyncId(ledger.getSyncId());
        change.setVersion(ledger.getVersion());
        change.setOperation(SyncChangeRow.OP_UPSERT);
        change.setServerReceivedAt(ledger.getServerReceivedAt());
        changeRepository.save(change);
    }

    // ===== 内部工具 =====

    private LedgerSummary toSummary(LedgerRow ledger, LedgerMemberRow member) {
        long memberCount = memberRepository.findByLedgerIdOrderByCreatedAtAsc(ledger.getId())
                .stream().filter(m -> LedgerMemberRow.STATUS_ACTIVE.equals(m.getStatus())).count();
        return new LedgerSummary(ledger.getSyncId(), ledger.getName(), ledger.getDescription(),
                ledger.getCurrency(), ledger.getUserId(), ledger.isDefaultLedger(),
                ledger.isArchived(), ledger.isDeleted(), member.getRole(), member.getStatus(),
                ledger.getVersion(), memberCount);
    }

    private UserEntity requireActiveUser(long userId) {
        return userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null && u.isActive())
                .orElseThrow(() -> ApiException.forbidden("USER_DISABLED", "账号已被服务器禁用"));
    }

    /** 要求当前用户是指定账本的 ACTIVE 成员；requiredRole 非空时同时要求角色达标。 */
    private LedgerRow requireMembership(long userId, String ledgerSyncId, String requiredRole) {
        requireActiveUser(userId);
        LedgerRow ledger = ledgerRepository.findBySyncId(ledgerSyncId == null ? "" : ledgerSyncId)
                .orElseThrow(() -> ApiException.forbidden("LEDGER_NOT_FOUND", "账本不存在"));
        LedgerMemberRow member = memberRepository
                .findByLedgerIdAndUserId(ledger.getId(), userId)
                .filter(m -> LedgerMemberRow.STATUS_ACTIVE.equals(m.getStatus()))
                .orElseThrow(() -> ApiException.forbidden("LEDGER_ACCESS_DENIED",
                        "你不是该账本成员"));
        if (requiredRole != null && !member.atLeast(requiredRole)) {
            throw ApiException.forbidden("LEDGER_ROLE_REQUIRED", "权限不足");
        }
        return ledger;
    }
}
