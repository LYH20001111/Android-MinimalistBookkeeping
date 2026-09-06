package com.skyanchor.bookkeeping.server.sync;

import com.skyanchor.bookkeeping.server.auth.AuthService;
import com.skyanchor.bookkeeping.server.auth.AuthUser;
import com.skyanchor.bookkeeping.server.auth.CurrentUserHolder;
import com.skyanchor.bookkeeping.server.auth.MailService;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.RegisterRequest;
import com.skyanchor.bookkeeping.server.auth.repo.UserRepository;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.ConflictItem;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.ConflictsResponse;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushItem;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushRequest;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushResponse;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;

/**
 * 冲突历史查询（V3.1 基线第 26 章）：冲突自动收敛后可事后查看。
 * 覆盖 CLIENT 胜出落库；SERVER 胜出（同毫秒边界 / 恢复后旧记录）同样落库。
 */
@SpringBootTest
@Transactional
@Rollback
class ConflictHistoryIntegrationTest {

    @Autowired
    AuthService authService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    SyncController syncController;
    @Autowired
    ConflictLogRepository conflictRepository;
    @MockBean
    MailService mailService;

    private static final String EMAIL = "conflict-history@example.com";
    private static final String PASSWORD = "password-123";

    private AuthUser verifiedUser(String email) {
        authService.register(new RegisterRequest(email, PASSWORD));
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mailService, times(1))
                .sendVerificationEmail(eq(email), tokenCaptor.capture());
        assertTrue(authService.verifyEmail(tokenCaptor.getValue()));
        long userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        return new AuthUser(userId, email, "device-A", 1L);
    }

    private <T> T asUser(AuthUser user, java.util.function.Supplier<T> action) {
        CurrentUserHolder.set(user);
        try {
            return action.get();
        } finally {
            CurrentUserHolder.clear();
        }
    }

    private static SyncPayload categoryPayload(String name) {
        SyncPayload payload = new SyncPayload();
        payload.name = name;
        payload.type = 1;
        payload.clientUpdatedAt = 1_700_000_000_000L;
        return payload;
    }

    @Test
    void conflicts_are_listed_after_lww_resolution() {
        AuthUser user = verifiedUser(EMAIL);
        String syncId = "bbbbbbbb-1111-1111-1111-111111111111";

        asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("CATEGORY", syncId, "UPSERT", 0, categoryPayload("交通"))))));

        // 设备 A 基于 v1 修改并推送（版本 2）
        SyncPayload v2 = categoryPayload("交通-改");
        v2.clientUpdatedAt = 1_700_000_100_000L;
        asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("CATEGORY", syncId, "UPSERT", 1, v2)))));

        // 设备 B 仍基于 v1 → 冲突 → LWW 后到者胜（B），审计落库
        SyncPayload v3 = categoryPayload("交通-B设备");
        v3.clientUpdatedAt = 1_700_000_050_000L;
        PushResponse third = asUser(user, () -> syncController.push(new PushRequest(List.of(
                new PushItem("CATEGORY", syncId, "UPSERT", 1, v3)))));
        assertTrue(third.results().get(0).conflicted());
        assertEquals(1, conflictRepository.count());

        ConflictsResponse response = asUser(user, () -> syncController.conflicts(50));
        assertEquals(1, response.conflicts().size());
        ConflictItem item = response.conflicts().get(0);
        assertEquals("CATEGORY", item.entityType());
        assertEquals(syncId, item.syncId());
        assertFalse(item.winner().isEmpty());
        assertEquals(1, item.baseVersion());
        assertConflictLimitWorks(user);
    }

    private void assertConflictLimitWorks(AuthUser user) {
        ConflictsResponse limited = asUser(user, () -> syncController.conflicts(1));
        assertEquals(1, limited.conflicts().size());
    }
}
