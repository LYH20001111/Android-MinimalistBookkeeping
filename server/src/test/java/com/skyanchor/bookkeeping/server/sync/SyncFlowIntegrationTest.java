package com.skyanchor.bookkeeping.server.sync;

import com.skyanchor.bookkeeping.server.auth.AuthService;
import com.skyanchor.bookkeeping.server.auth.AuthUser;
import com.skyanchor.bookkeeping.server.auth.CurrentUserHolder;
import com.skyanchor.bookkeeping.server.auth.MailService;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.RegisterRequest;
import com.skyanchor.bookkeeping.server.auth.repo.UserRepository;
import com.skyanchor.bookkeeping.server.common.ApiException;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.BootstrapSummaryResponse;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.ChangeItem;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PullRequest;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PullResponse;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushItem;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushRequest;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushResponse;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushResultItem;
import com.skyanchor.bookkeeping.server.sync.repo.ConflictLogRepository;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;

/**
 * 全链路集成测试（H2 兼容 PostgreSQL 方言）：注册 → 验证邮箱 → claim 默认账本 →
 * Push → Pull → LWW 冲突 → 软删传播 → bootstrap 统计 → 未验证邮箱被拒。
 * V3.2：业务变更必须携带 ledgerId，服务端按账本隔离与鉴权。
 * 覆盖基线第 43.5/43.8 章的服务端可验证部分。
 */
@SpringBootTest
@Transactional
@Rollback
class SyncFlowIntegrationTest {

    @Autowired
    AuthService authService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ConflictLogRepository conflictRepository;

    @Autowired
    SyncController syncController;

    @MockBean
    MailService mailService;

    private static final String EMAIL = "sync-test@example.com";
    private static final String PASSWORD = "password-123";
    private static final String LEDGER_SYNC_ID = "aaaaaaaa-0000-0000-0000-000000000001";

    /** 注册（幂等）+ 用捕获的验证令牌完成邮箱验证，返回可直接注入控制器的认证上下文。 */
    private AuthUser verifiedUser() {
        registerAndVerify(EMAIL, PASSWORD);
        long userId = userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow().getId();
        return new AuthUser(userId, EMAIL, "device-A", 1L);
    }

    /** V3.2：注册验证后先 claim 默认账本（服务端创建账本 + OWNER 成员 + 种子默认数据）。 */
    private AuthUser verifiedUserWithLedger() {
        AuthUser user = verifiedUser();
        claimDefaultLedger(user, LEDGER_SYNC_ID);
        return user;
    }

    private void claimDefaultLedger(AuthUser user, String ledgerSyncId) {
        SyncPayload ledger = new SyncPayload();
        ledger.name = "我的账本";
        ledger.currency = "CNY";
        ledger.isDefault = true;
        PushResponse push = asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("LEDGER", ledgerSyncId, "UPSERT", 0, ledgerSyncId, ledger)))));
        PushResultItem result = push.results().get(0);
        assertTrue(result.accepted(), () -> "claim ledger 失败: " + result.errorCode());
    }

    private <T> T asUser(AuthUser user, java.util.function.Supplier<T> action) {
        CurrentUserHolder.set(user);
        try {
            return action.get();
        } finally {
            CurrentUserHolder.clear();
        }
    }

    private void registerAndVerify(String email, String password) {
        if (userRepository.findByEmailIgnoreCase(email).isEmpty()) {
            authService.register(new RegisterRequest(email, password));
        } else {
            authService.resendVerification(email);
        }
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mailService, times(1))
                .sendVerificationEmail(eq(email), tokenCaptor.capture());
        assertTrue(authService.verifyEmail(tokenCaptor.getValue()));
    }

    @Test
    void push_then_pull_roundtrip() {
        AuthUser user = verifiedUserWithLedger();

        SyncPayload category = payload("往返分类");
        category.type = 1;
        PushResponse push = asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("CATEGORY", "11111111-1111-1111-1111-111111111111", "UPSERT", 0,
                        LEDGER_SYNC_ID, category)))));
        PushResultItem result = push.results().get(0);
        assertTrue(result.accepted());
        assertFalse(result.conflicted());
        assertEquals(1, result.version());

        PullResponse pull = asUser(user, () -> syncController.pull(
                new PullRequest(LEDGER_SYNC_ID, 0, 100)));
        ChangeItem change = pull.changes().stream()
                .filter(c -> c.syncId().equals("11111111-1111-1111-1111-111111111111"))
                .findFirst().orElseThrow();
        assertEquals("CATEGORY", change.entityType());
        assertEquals(1, change.version());
        assertEquals("往返分类", change.payload().name);
        assertTrue(pull.lastChangeId() > 0);
        assertFalse(pull.hasMore());
    }

    @Test
    void claimLedger_seedsDefaultDataOnce() {
        AuthUser user = verifiedUserWithLedger();
        // 服务端初始化了一次默认分类/账户（基线第 27 章）
        PullResponse pull = asUser(user, () -> syncController.pull(
                new PullRequest(LEDGER_SYNC_ID, 0, 100)));
        long seededCategories = pull.changes().stream()
                .filter(c -> "CATEGORY".equals(c.entityType())).count();
        long seededAccounts = pull.changes().stream()
                .filter(c -> "ACCOUNT".equals(c.entityType())).count();
        assertEquals(16, seededCategories);
        assertEquals(6, seededAccounts);
    }

    @Test
    void conflictingPush_lww_overrides_and_logs() {
        AuthUser user = verifiedUserWithLedger();
        String syncId = "22222222-2222-2222-2222-222222222222";

        SyncPayload v1 = payload("冲突分类");
        v1.type = 1;
        PushResponse first = asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("CATEGORY", syncId, "UPSERT", 0, LEDGER_SYNC_ID, v1)))));
        long version = first.results().get(0).version();
        assertEquals(1, version);

        // 设备 A 基于 v1 修改并推送（成功，版本 +1）
        SyncPayload v2 = payload("冲突分类-改");
        v2.type = 1;
        v2.clientUpdatedAt = 1_700_000_100_000L;
        PushResponse second = asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("CATEGORY", syncId, "UPSERT", version, LEDGER_SYNC_ID, v2)))));
        assertEquals(2, second.results().get(0).version());

        // 设备 B 仍基于 v1 修改（旧 base）→ 冲突 → LWW：后到者胜，conflict log 留痕
        SyncPayload v3 = payload("冲突分类-B设备");
        v3.type = 1;
        v3.clientUpdatedAt = 1_700_000_050_000L; // B 的业务修改时间更早，但不参与裁决
        PushResponse third = asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("CATEGORY", syncId, "UPSERT", version, LEDGER_SYNC_ID, v3)))));
        PushResultItem conflictResult = third.results().get(0);
        assertTrue(conflictResult.accepted());
        assertTrue(conflictResult.conflicted());
        assertEquals(3, conflictResult.version());
        assertEquals("冲突分类-B设备", conflictResult.payload().name);
        assertEquals(1, conflictRepository.count());
    }

    @Test
    void softDelete_propagates_via_pull() {
        AuthUser user = verifiedUserWithLedger();
        String syncId = "33333333-3333-3333-3333-333333333333";

        SyncPayload create = payload("软删分类");
        create.type = 1;
        asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("CATEGORY", syncId, "UPSERT", 0, LEDGER_SYNC_ID, create)))));

        PushResponse delete = asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("CATEGORY", syncId, "DELETE", 1, LEDGER_SYNC_ID, null)))));
        PushResultItem deleteResult = delete.results().get(0);
        assertTrue(deleteResult.accepted());
        assertEquals(2, deleteResult.version());

        PullResponse pull = asUser(user, () -> syncController.pull(
                new PullRequest(LEDGER_SYNC_ID, 0, 100)));
        ChangeItem latest = pull.changes().stream()
                .filter(c -> c.syncId().equals(syncId))
                .findFirst().orElseThrow();
        assertEquals(2, latest.version());
        assertTrue(latest.payload().isDeleted);
    }

    @Test
    void delete_unknown_syncId_isIdempotentNoop() {
        AuthUser user = verifiedUserWithLedger();
        PushResponse push = asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("CATEGORY", "44444444-4444-4444-4444-444444444444", "DELETE", 0,
                        LEDGER_SYNC_ID, null)))));
        assertTrue(push.results().get(0).accepted());
        assertEquals(0, push.results().get(0).version());
    }

    @Test
    void bootstrap_summary_counts() {
        AuthUser user = verifiedUserWithLedger();
        SyncPayload category = payload("医疗专用分类");
        category.type = 1;
        asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("CATEGORY", "55555555-5555-5555-5555-555555555555", "UPSERT", 0,
                        LEDGER_SYNC_ID, category)))));
        BootstrapSummaryResponse summary = asUser(user, () -> syncController.bootstrapSummary());
        assertTrue(summary.hasCloudData());
        // 16 个种子默认分类 + 本次推送 1 个（测试分类名不与种子撞名）
        assertEquals(17, summary.counts().category());
        assertEquals(6, summary.counts().account());
        assertNotEquals(0, summary.serverTime());
    }

    @Test
    void push_requires_email_verified() {
        authService.register(new RegisterRequest("unverified@example.com", PASSWORD));
        long userId = userRepository.findByEmailIgnoreCase("unverified@example.com")
                .orElseThrow().getId();
        AuthUser unverified = new AuthUser(userId, "unverified@example.com", "device-A", 1L);
        ApiException e = assertThrows(ApiException.class, () -> asUser(unverified,
                () -> syncController.bootstrapSummary()));
        assertEquals("EMAIL_NOT_VERIFIED", e.getCode());
    }

    @Test
    void duplicateNamePush_mergesIntoExistingSyncId() {
        AuthUser user = verifiedUserWithLedger();
        String firstId = "77777777-7777-7777-7777-777777777777";
        String secondId = "88888888-8888-8888-8888-888888888888";

        SyncPayload original = payload("合并测试分类");
        original.type = 1;
        PushResponse first = asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("CATEGORY", firstId, "UPSERT", 0, LEDGER_SYNC_ID, original)))));
        assertTrue(first.results().get(0).accepted());

        // 另一台设备独立创建了同名分类（不同 syncId）→ 不新建，返回 mergedInto
        SyncPayload twin = payload("合并测试分类");
        twin.type = 1;
        twin.clientUpdatedAt = 1_700_000_200_000L;
        PushResponse second = asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("CATEGORY", secondId, "UPSERT", 0, LEDGER_SYNC_ID, twin)))));
        PushResultItem result = second.results().get(0);
        assertTrue(result.accepted());
        assertEquals(firstId, result.mergedInto());
        assertEquals(1, result.version());

        // 云端只保留一条「餐饮」（16 个种子默认分类 + 1）
        BootstrapSummaryResponse summary = asUser(user, () -> syncController.bootstrapSummary());
        assertEquals(17, summary.counts().category());
    }

    @Test
    void reference_validation_rejects_dangling_refs() {
        AuthUser user = verifiedUserWithLedger();
        SyncPayload transaction = payload(null);
        transaction.type = 1;
        transaction.amount = 1000L;
        transaction.categorySyncId = "not-exists";
        PushResponse push = asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("TRANSACTION", "66666666-6666-6666-6666-666666666666",
                        "UPSERT", 0, LEDGER_SYNC_ID, transaction)))));
        PushResultItem result = push.results().get(0);
        assertFalse(result.accepted());
        assertEquals("MISSING_REFERENCE", result.errorCode());
    }

    private static SyncPayload payload(String name) {
        SyncPayload payload = new SyncPayload();
        payload.name = name;
        payload.clientUpdatedAt = 1_700_000_000_000L;
        return payload;
    }
}
