package com.skyanchor.bookkeeping.server.backup;

import com.skyanchor.bookkeeping.server.auth.AuthService;
import com.skyanchor.bookkeeping.server.auth.AuthUser;
import com.skyanchor.bookkeeping.server.auth.CurrentUserHolder;
import com.skyanchor.bookkeeping.server.auth.MailService;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.LoginRequest;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.RegisterRequest;
import com.skyanchor.bookkeeping.server.auth.repo.RefreshTokenRepository;
import com.skyanchor.bookkeeping.server.auth.repo.UserRepository;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.BackupMeta;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.RestoreReport;
import com.skyanchor.bookkeeping.server.common.ApiException;
import com.skyanchor.bookkeeping.server.common.ServerMeta;
import com.skyanchor.bookkeeping.server.common.ServerMetaRepository;
import com.skyanchor.bookkeeping.server.sync.SyncPayload;
import com.skyanchor.bookkeeping.server.sync.SyncService;
import com.skyanchor.bookkeeping.server.sync.domain.CategoryRow;
import com.skyanchor.bookkeeping.server.sync.domain.SyncChangeRow;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PullRequest;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PullResponse;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushItem;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushRequest;
import com.skyanchor.bookkeeping.server.sync.repo.AccountRowRepository;
import com.skyanchor.bookkeeping.server.sync.repo.CategoryRowRepository;
import com.skyanchor.bookkeeping.server.sync.repo.ConflictLogRepository;
import com.skyanchor.bookkeeping.server.sync.repo.SyncChangeRepository;
import com.skyanchor.bookkeeping.server.sync.repo.TransactionRowRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;

/**
 * 备份 / 恢复全链路集成测试（H2 兼容 PostgreSQL）：导出 → 继续写入 → 恢复 →
 * 数据回到备份点、recovery_epoch 递增、sync_changes 重建、令牌全部失效。
 * V3.1 决策 2：恢复不回滚客户端游标，而是让客户端识别 epoch 变化后重置重新收敛。
 */
@SpringBootTest(properties = "app.backup.dir=build/test-backups")
@Transactional
@Rollback
class BackupRestoreIntegrationTest {

    private static final Path BACKUP_DIR = Path.of("build/test-backups");

    @Autowired
    AuthService authService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    BackupService backupService;
    @Autowired
    BackupRestoreService restoreService;
    @Autowired
    AdminGuard adminGuard;
    @Autowired
    CategoryRowRepository categoryRepository;
    @Autowired
    AccountRowRepository accountRepository;
    @Autowired
    TransactionRowRepository transactionRepository;
    @Autowired
    SyncChangeRepository changeRepository;
    @Autowired
    ConflictLogRepository conflictRepository;
    @Autowired
    RefreshTokenRepository refreshTokenRepository;
    @Autowired
    ServerMetaRepository serverMetaRepository;
    @Autowired
    SyncService syncService;

    @MockBean
    MailService mailService;

    private static final String EMAIL = "backup-test@example.com";
    private static final String PASSWORD = "password-123";
    private static final String CATEGORY_SYNC_ID = "aaaaaaaa-1111-1111-1111-111111111111";
    private static final String TRANSACTION_SYNC_ID = "aaaaaaaa-2222-2222-2222-222222222222";

    @BeforeAll
    static void cleanBackupDir() throws IOException {
        if (Files.exists(BACKUP_DIR)) {
            try (var walk = Files.walk(BACKUP_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(BACKUP_DIR);
    }

    @AfterAll
    static void removeBackupDir() throws IOException {
        if (Files.exists(BACKUP_DIR)) {
            try (var walk = Files.walk(BACKUP_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private AuthUser verifiedUser() {
        authService.register(new RegisterRequest(EMAIL, PASSWORD));
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mailService, times(1))
                .sendVerificationEmail(eq(EMAIL), tokenCaptor.capture());
        assertTrue(authService.verifyEmail(tokenCaptor.getValue()));
        long userId = userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow().getId();
        return new AuthUser(userId, EMAIL, "device-A", 1L);
    }

    private <T> T asUser(AuthUser user, java.util.function.Supplier<T> action) {
        CurrentUserHolder.set(user);
        try {
            return action.get();
        } finally {
            CurrentUserHolder.clear();
        }
    }

    private SyncPayload categoryPayload(String name) {
        SyncPayload payload = new SyncPayload();
        payload.name = name;
        payload.type = 1;
        payload.icon = "🍚";
        payload.clientUpdatedAt = 1_700_000_000_000L;
        return payload;
    }

    @Test
    void backup_and_restore_roundtrip_recovers_data_and_bumps_epoch() {
        AuthUser user = verifiedUser();
        long epochBefore = recoveryEpoch();

        // 1. 备份点：1 个分类 + 1 笔交易
        asUser(user, () -> syncService.push(user, new PushRequest(List.of(
                new PushItem("CATEGORY", CATEGORY_SYNC_ID, "UPSERT", 0,
                        categoryPayload("餐饮"))))));
        SyncPayload tx = new SyncPayload();
        tx.type = 1;
        tx.amount = 3500L;
        tx.date = 1_700_000_000_000L;
        tx.time = "12:30";
        tx.categorySyncId = CATEGORY_SYNC_ID;
        tx.clientUpdatedAt = 1_700_000_000_000L;
        asUser(user, () -> syncService.push(user, new PushRequest(List.of(
                new PushItem("TRANSACTION", TRANSACTION_SYNC_ID, "UPSERT", 0, tx)))));

        BackupMeta meta = backupService.createBackup(BackupDtos.TRIGGER_API);
        assertNotNull(meta.name());
        assertEquals(1, meta.counts().categories());
        assertEquals(1, meta.counts().transactions());
        assertTrue(backupService.backupFileExists(meta.name()));

        // 2. 备份之后继续写入：改名 + 新增交易 + 软删原交易
        SyncPayload renamed = categoryPayload("餐饮-改");
        renamed.clientUpdatedAt = 1_700_000_100_000L;
        asUser(user, () -> syncService.push(user, new PushRequest(List.of(
                new PushItem("CATEGORY", CATEGORY_SYNC_ID, "UPSERT", 1, renamed)))));
        SyncPayload tx2 = new SyncPayload();
        tx2.type = 1;
        tx2.amount = 9900L;
        tx2.date = 1_700_100_000_000L;
        tx2.time = "19:00";
        tx2.clientUpdatedAt = 1_700_100_000_000L;
        asUser(user, () -> syncService.push(user, new PushRequest(List.of(
                new PushItem("TRANSACTION", "aaaaaaaa-3333-3333-3333-333333333333",
                        "UPSERT", 0, tx2)))));
        asUser(user, () -> syncService.push(user, new PushRequest(List.of(
                new PushItem("TRANSACTION", TRANSACTION_SYNC_ID, "DELETE", 1, null)))));
        assertEquals(2, transactionRepository.count());

        // 3. 恢复
        RestoreReport report = restoreService.restore(meta.name());
        assertEquals(meta.name(), report.restoredFrom());
        assertEquals(epochBefore + 1, report.newRecoveryEpoch());
        assertEquals(epochBefore + 1, recoveryEpoch());

        // 4. 数据回到备份点：分类名/版本复原，多出的交易消失，被删交易复活
        assertEquals(1, categoryRepository.count());
        CategoryRow category = categoryRepository.findAll().get(0);
        assertEquals("餐饮", category.getName());
        assertEquals(1, category.getVersion());
        assertFalse(category.isDeleted());

        assertEquals(1, transactionRepository.count());
        assertFalse(transactionRepository.findAll().get(0).isDeleted());
        assertEquals(3500L, transactionRepository.findAll().get(0).getAmount());

        // 5. sync_changes 按业务行重建：每行一条最新变更，游标可从 0 全量重拉
        assertEquals(2, changeRepository.count());
        // 恢复重建了用户（自增 id 改变），客户端侧等价于重新登录后以新身份收敛
        long newUserId = userRepository.findAll().get(0).getId();
        AuthUser newUser = new AuthUser(newUserId, user.email(), user.deviceId(),
                user.deviceRowId());
        PullResponse pull = asUser(newUser, () -> syncService.pull(newUser,
                new PullRequest(0, 100)));
        assertEquals(2, pull.changes().size());

        // 6. 备份列表可读
        List<BackupMeta> backups = backupService.listBackups();
        assertEquals(1, backups.size());
        assertEquals(meta.name(), backups.get(0).name());
    }

    @Test
    void restore_invalidates_all_refresh_tokens() {
        AuthUser user = verifiedUser();
        authService.login(new LoginRequest(EMAIL, PASSWORD, null));
        assertTrue(refreshTokenRepository.count() > 0);

        BackupMeta meta = backupService.createBackup(BackupDtos.TRIGGER_API);
        authService.login(new LoginRequest(EMAIL, PASSWORD, null));
        assertTrue(refreshTokenRepository.count() > 0);

        restoreService.restore(meta.name());
        // 登录令牌不入备份：恢复后所有设备必须重新登录（V3.1 决策 1 安全边界）
        assertEquals(0, refreshTokenRepository.count());
    }

    @Test
    void restore_rejects_corrupted_file() {
        verifiedUser();
        ApiException e = assertThrows(ApiException.class,
                () -> restoreService.restore("bookkeeping-backup-19990101-000000.json"));
        assertEquals("VALIDATION_ERROR", e.getCode());
    }

    @Test
    void first_registered_user_is_admin_others_are_not() {
        AuthUser admin = verifiedUser();
        authService.register(new RegisterRequest("second-user@example.com", PASSWORD));
        long secondId = userRepository.findByEmailIgnoreCase("second-user@example.com")
                .orElseThrow().getId();
        AuthUser second = new AuthUser(secondId, "second-user@example.com", "device-B", 2L);

        assertTrue(adminGuard.isAdmin(admin));
        assertFalse(adminGuard.isAdmin(second));
        ApiException e = assertThrows(ApiException.class, () -> adminGuard.requireAdmin(second));
        assertEquals("NOT_ADMIN", e.getCode());
    }

    @Test
    void backup_updates_last_backup_meta() {
        verifiedUser();
        BackupMeta meta = backupService.createBackup(BackupDtos.TRIGGER_MANUAL);
        long lastBackupAt = Long.parseLong(serverMetaRepository
                .findById(ServerMeta.KEY_LAST_BACKUP_AT).orElseThrow().getValue());
        assertNotEquals(0, lastBackupAt);
        assertEquals(meta.name(), serverMetaRepository
                .findById(ServerMeta.KEY_LAST_BACKUP_FILE).orElseThrow().getValue());
    }

    private long recoveryEpoch() {
        return serverMetaRepository.findById(ServerMeta.KEY_RECOVERY_EPOCH)
                .map(meta -> Long.parseLong(meta.getValue()))
                .orElse(0L);
    }
}
