package com.skyanchor.bookkeeping.server.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyanchor.bookkeeping.server.auth.domain.DeviceEntity;
import com.skyanchor.bookkeeping.server.auth.domain.UserEntity;
import com.skyanchor.bookkeeping.server.auth.repo.DeviceRepository;
import com.skyanchor.bookkeeping.server.auth.repo.EmailVerificationTokenRepository;
import com.skyanchor.bookkeeping.server.auth.repo.RefreshTokenRepository;
import com.skyanchor.bookkeeping.server.auth.repo.UserRepository;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.AccountEntry;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.BackupCounts;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.BackupFile;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.BudgetEntry;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.CategoryEntry;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.ConflictEntry;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.DeviceEntry;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.RestoreReport;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.RecurringEntry;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.TransactionEntry;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.UserEntry;
import com.skyanchor.bookkeeping.server.common.ApiException;
import com.skyanchor.bookkeeping.server.common.ServerMeta;
import com.skyanchor.bookkeeping.server.common.ServerMetaRepository;
import com.skyanchor.bookkeeping.server.sync.domain.AccountRow;
import com.skyanchor.bookkeeping.server.sync.domain.BudgetRow;
import com.skyanchor.bookkeeping.server.sync.domain.CategoryRow;
import com.skyanchor.bookkeeping.server.sync.domain.ConflictLogRow;
import com.skyanchor.bookkeeping.server.sync.domain.RecurringRow;
import com.skyanchor.bookkeeping.server.sync.domain.SyncChangeRow;
import com.skyanchor.bookkeeping.server.sync.domain.SyncRow;
import com.skyanchor.bookkeeping.server.sync.domain.TransactionRow;
import com.skyanchor.bookkeeping.server.sync.repo.AccountRowRepository;
import com.skyanchor.bookkeeping.server.sync.repo.BudgetRowRepository;
import com.skyanchor.bookkeeping.server.sync.repo.CategoryRowRepository;
import com.skyanchor.bookkeeping.server.sync.repo.ConflictLogRepository;
import com.skyanchor.bookkeeping.server.sync.repo.RecurringRowRepository;
import com.skyanchor.bookkeeping.server.sync.repo.SyncChangeRepository;
import com.skyanchor.bookkeeping.server.sync.repo.TransactionRowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务器恢复（V3.1 基线第 15/16 章）。流程：
 * 校验文件 → 清空全部数据 → 按依赖顺序重建（users → devices → 业务表 → 冲突日志）
 * → 从业务行重建 sync_changes（每行一条最新变更）→ recovery_epoch +1。
 *
 * <p>安全策略（基线第 14 章）：refresh_tokens / email_verification_tokens 不备份、
 * 不恢复——恢复后所有设备必须重新登录。调用方负责先取同步写屏障写锁，
 * 恢复期间 Push/Pull 全部阻塞。
 */
@Service
public class BackupRestoreService {

    private static final Logger log = LoggerFactory.getLogger(BackupRestoreService.class);

    private final BackupService backupService;
    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final CategoryRowRepository categoryRepository;
    private final AccountRowRepository accountRepository;
    private final TransactionRowRepository transactionRepository;
    private final BudgetRowRepository budgetRepository;
    private final RecurringRowRepository recurringRepository;
    private final SyncChangeRepository changeRepository;
    private final ConflictLogRepository conflictRepository;
    private final ServerMetaRepository serverMetaRepository;
    private final ObjectMapper objectMapper;

    public BackupRestoreService(BackupService backupService,
                                UserRepository userRepository,
                                DeviceRepository deviceRepository,
                                RefreshTokenRepository refreshTokenRepository,
                                EmailVerificationTokenRepository emailVerificationTokenRepository,
                                CategoryRowRepository categoryRepository,
                                AccountRowRepository accountRepository,
                                TransactionRowRepository transactionRepository,
                                BudgetRowRepository budgetRepository,
                                RecurringRowRepository recurringRepository,
                                SyncChangeRepository changeRepository,
                                ConflictLogRepository conflictRepository,
                                ServerMetaRepository serverMetaRepository,
                                ObjectMapper objectMapper) {
        this.backupService = backupService;
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.recurringRepository = recurringRepository;
        this.changeRepository = changeRepository;
        this.conflictRepository = conflictRepository;
        this.serverMetaRepository = serverMetaRepository;
        this.objectMapper = objectMapper;
    }

    /** 恢复指定备份；须在写屏障写锁内调用。 */
    @Transactional
    public RestoreReport restore(String name) {
        BackupFile file = parse(backupService.readBackupBytes(name));
        wipeAll();

        Map<Long, UserEntity> userMap = insertUsers(file.users());
        insertDevices(file.devices(), userMap);
        List<SyncRow> allRows = new ArrayList<>();
        allRows.addAll(insertCategories(file.categories(), userMap));
        allRows.addAll(insertAccounts(file.accounts(), userMap));
        allRows.addAll(insertTransactions(file.transactions(), userMap));
        allRows.addAll(insertBudgets(file.budgets(), userMap));
        allRows.addAll(insertRecurring(file.recurring(), userMap));
        insertConflictLogs(file.conflictLogs(), userMap);
        rebuildSyncChanges(allRows);

        long newEpoch = incrementRecoveryEpoch();
        BackupCounts counts = new BackupCounts(userMap.size(),
                file.devices() == null ? 0 : file.devices().size(),
                file.categories() == null ? 0 : file.categories().size(),
                file.accounts() == null ? 0 : file.accounts().size(),
                file.transactions() == null ? 0 : file.transactions().size(),
                file.budgets() == null ? 0 : file.budgets().size(),
                file.recurring() == null ? 0 : file.recurring().size(),
                file.conflictLogs() == null ? 0 : file.conflictLogs().size());
        log.warn("server restored from={} newEpoch={} transactions={}",
                name, newEpoch, counts.transactions());
        return new RestoreReport(name, newEpoch, counts, System.currentTimeMillis());
    }

    private BackupFile parse(byte[] bytes) {
        BackupFile file;
        try {
            file = objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8),
                    BackupFile.class);
        } catch (IOException e) {
            throw ApiException.badRequest("备份文件损坏，无法解析");
        }
        if (file == null || !BackupDtos.FORMAT.equals(file.format())
                || file.formatVersion() != BackupDtos.FORMAT_VERSION) {
            throw ApiException.badRequest("备份文件格式不受支持");
        }
        return file;
    }

    /** 清库：先令牌类，再日志类，再业务表，最后设备与用户（FK 均级联）。 */
    private void wipeAll() {
        refreshTokenRepository.deleteAllInBatch();
        emailVerificationTokenRepository.deleteAllInBatch();
        changeRepository.deleteAllInBatch();
        conflictRepository.deleteAllInBatch();
        categoryRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        transactionRepository.deleteAllInBatch();
        budgetRepository.deleteAllInBatch();
        recurringRepository.deleteAllInBatch();
        deviceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private Map<Long, UserEntity> insertUsers(List<UserEntry> entries) {
        Map<Long, UserEntity> byRef = new HashMap<>();
        if (entries == null) {
            return byRef;
        }
        List<UserEntity> users = new ArrayList<>();
        for (UserEntry entry : entries) {
            UserEntity user = new UserEntity();
            user.setEmail(entry.email());
            user.setPasswordHash(entry.passwordHash());
            user.setEmailVerified(entry.emailVerified());
            user.setCreatedAt(Instant.ofEpochMilli(entry.createdAt()));
            user.setUpdatedAt(Instant.ofEpochMilli(entry.updatedAt()));
            user.setDeletedAt(entry.deletedAt() == null
                    ? null : Instant.ofEpochMilli(entry.deletedAt()));
            users.add(user);
            byRef.put(entry.refId(), user);
        }
        userRepository.saveAll(users);
        userRepository.flush();
        return byRef;
    }

    private void insertDevices(List<DeviceEntry> entries, Map<Long, UserEntity> userMap) {
        if (entries == null) {
            return;
        }
        List<DeviceEntity> devices = new ArrayList<>();
        for (DeviceEntry entry : entries) {
            UserEntity owner = requireUser(userMap, entry.userRefId());
            DeviceEntity device = new DeviceEntity();
            device.setUserId(owner.getId());
            device.setDeviceId(entry.deviceId());
            device.setDeviceName(orEmpty(entry.deviceName()));
            device.setPlatform(orEmpty(entry.platform()));
            device.setAppVersion(orEmpty(entry.appVersion()));
            device.setLastSeenAt(Instant.ofEpochMilli(entry.lastSeenAt()));
            device.setCreatedAt(Instant.ofEpochMilli(entry.createdAt()));
            device.setRevokedAt(entry.revokedAt() == null
                    ? null : Instant.ofEpochMilli(entry.revokedAt()));
            devices.add(device);
        }
        deviceRepository.saveAll(devices);
        deviceRepository.flush();
    }

    private List<SyncRow> insertCategories(List<CategoryEntry> entries,
                                           Map<Long, UserEntity> userMap) {
        List<SyncRow> rows = new ArrayList<>();
        if (entries == null) {
            return rows;
        }
        List<CategoryRow> saved = new ArrayList<>();
        for (CategoryEntry entry : entries) {
            CategoryRow row = new CategoryRow();
            applySyncMeta(row, userMap, entry.userRefId(), entry.syncId(), entry.version(),
                    entry.serverReceivedAt(), entry.clientUpdatedAt(), entry.deleted(),
                    entry.deletedAt(), entry.createdAt());
            row.setName(entry.name());
            row.setIcon(orEmpty(entry.icon()));
            row.setType(entry.type());
            row.setSortOrder(entry.sortOrder());
            row.setDefault(entry.isDefault());
            saved.add(row);
            rows.add(row);
        }
        categoryRepository.saveAll(saved);
        categoryRepository.flush();
        return rows;
    }

    private List<SyncRow> insertAccounts(List<AccountEntry> entries,
                                         Map<Long, UserEntity> userMap) {
        List<SyncRow> rows = new ArrayList<>();
        if (entries == null) {
            return rows;
        }
        List<AccountRow> saved = new ArrayList<>();
        for (AccountEntry entry : entries) {
            AccountRow row = new AccountRow();
            applySyncMeta(row, userMap, entry.userRefId(), entry.syncId(), entry.version(),
                    entry.serverReceivedAt(), entry.clientUpdatedAt(), entry.deleted(),
                    entry.deletedAt(), entry.createdAt());
            row.setName(entry.name());
            row.setType(entry.type());
            row.setInitialBalance(entry.initialBalance());
            row.setBalance(entry.balance());
            row.setCredit(entry.isCredit());
            row.setSortOrder(entry.sortOrder());
            row.setArchived(entry.isArchived());
            saved.add(row);
            rows.add(row);
        }
        accountRepository.saveAll(saved);
        accountRepository.flush();
        return rows;
    }

    private List<SyncRow> insertTransactions(List<TransactionEntry> entries,
                                             Map<Long, UserEntity> userMap) {
        List<SyncRow> rows = new ArrayList<>();
        if (entries == null) {
            return rows;
        }
        List<TransactionRow> saved = new ArrayList<>();
        for (TransactionEntry entry : entries) {
            TransactionRow row = new TransactionRow();
            applySyncMeta(row, userMap, entry.userRefId(), entry.syncId(), entry.version(),
                    entry.serverReceivedAt(), entry.clientUpdatedAt(), entry.deleted(),
                    entry.deletedAt(), entry.createdAt());
            row.setType(entry.type());
            row.setAmount(entry.amount());
            row.setDate(entry.date());
            row.setTime(entry.time() == null ? "00:00" : entry.time());
            row.setNote(entry.note());
            row.setCategorySyncId(entry.categorySyncId());
            row.setAccountSyncId(entry.accountSyncId());
            row.setTransferAccountSyncId(entry.transferAccountSyncId());
            row.setClientCreatedAt(entry.clientCreatedAt());
            saved.add(row);
            rows.add(row);
        }
        transactionRepository.saveAll(saved);
        transactionRepository.flush();
        return rows;
    }

    private List<SyncRow> insertBudgets(List<BudgetEntry> entries,
                                        Map<Long, UserEntity> userMap) {
        List<SyncRow> rows = new ArrayList<>();
        if (entries == null) {
            return rows;
        }
        List<BudgetRow> saved = new ArrayList<>();
        for (BudgetEntry entry : entries) {
            BudgetRow row = new BudgetRow();
            applySyncMeta(row, userMap, entry.userRefId(), entry.syncId(), entry.version(),
                    entry.serverReceivedAt(), entry.clientUpdatedAt(), entry.deleted(),
                    entry.deletedAt(), entry.createdAt());
            row.setYear(entry.year());
            row.setMonth(entry.month());
            row.setCategorySyncId(entry.categorySyncId() == null ? "" : entry.categorySyncId());
            row.setAmount(entry.amount());
            saved.add(row);
            rows.add(row);
        }
        budgetRepository.saveAll(saved);
        budgetRepository.flush();
        return rows;
    }

    private List<SyncRow> insertRecurring(List<RecurringEntry> entries,
                                          Map<Long, UserEntity> userMap) {
        List<SyncRow> rows = new ArrayList<>();
        if (entries == null) {
            return rows;
        }
        List<RecurringRow> saved = new ArrayList<>();
        for (RecurringEntry entry : entries) {
            RecurringRow row = new RecurringRow();
            applySyncMeta(row, userMap, entry.userRefId(), entry.syncId(), entry.version(),
                    entry.serverReceivedAt(), entry.clientUpdatedAt(), entry.deleted(),
                    entry.deletedAt(), entry.createdAt());
            row.setName(entry.name());
            row.setType(entry.type());
            row.setAmount(entry.amount());
            row.setCategorySyncId(entry.categorySyncId());
            row.setAccountSyncId(entry.accountSyncId());
            row.setFrequency(entry.frequency());
            row.setRepeatInterval(entry.repeatInterval());
            row.setStartDate(entry.startDate());
            row.setEndDate(entry.endDate());
            row.setNextRunDate(entry.nextRunDate());
            row.setAnchorDayOfMonth(entry.anchorDayOfMonth());
            row.setEnabled(entry.isEnabled());
            row.setNote(entry.note());
            saved.add(row);
            rows.add(row);
        }
        recurringRepository.saveAll(saved);
        recurringRepository.flush();
        return rows;
    }

    private void insertConflictLogs(List<ConflictEntry> entries,
                                    Map<Long, UserEntity> userMap) {
        if (entries == null) {
            return;
        }
        List<ConflictLogRow> logs = new ArrayList<>();
        for (ConflictEntry entry : entries) {
            ConflictLogRow row = new ConflictLogRow();
            row.setUserId(requireUser(userMap, entry.userRefId()).getId());
            row.setEntityType(entry.entityType());
            row.setSyncId(entry.syncId());
            row.setClientDeviceId(orEmpty(entry.clientDeviceId()));
            row.setBaseVersion(entry.baseVersion());
            row.setServerVersion(entry.serverVersion());
            row.setClientPayloadDigest(orEmpty(entry.clientPayloadDigest()));
            row.setServerPayloadDigest(orEmpty(entry.serverPayloadDigest()));
            row.setWinner(entry.winner());
            row.setCreatedAt(Instant.ofEpochMilli(entry.createdAt()));
            logs.add(row);
        }
        conflictRepository.saveAll(logs);
        conflictRepository.flush();
    }

    /**
     * 从业务行重建变更日志：每行一条“最新状态”变更（软删行记 DELETE）。
     * 客户端游标经 recovery_epoch 重置为 0 后全量拉取，即收敛到服务器当前状态。
     */
    private void rebuildSyncChanges(List<SyncRow> rows) {
        List<SyncChangeRow> changes = new ArrayList<>();
        for (SyncRow row : rows) {
            SyncChangeRow change = new SyncChangeRow();
            change.setUserId(row.getUserId());
            change.setEntityType(entityTypeOf(row));
            change.setSyncId(row.getSyncId());
            change.setVersion(row.getVersion());
            change.setOperation(row.isDeleted()
                    ? SyncChangeRow.OP_DELETE : SyncChangeRow.OP_UPSERT);
            change.setServerReceivedAt(row.getServerReceivedAt());
            changes.add(change);
        }
        changeRepository.saveAll(changes);
        changeRepository.flush();
    }

    private String entityTypeOf(SyncRow row) {
        if (row instanceof CategoryRow) {
            return "CATEGORY";
        }
        if (row instanceof AccountRow) {
            return "ACCOUNT";
        }
        if (row instanceof TransactionRow) {
            return "TRANSACTION";
        }
        if (row instanceof BudgetRow) {
            return "BUDGET";
        }
        if (row instanceof RecurringRow) {
            return "RECURRING";
        }
        throw new IllegalStateException("unknown row type: " + row.getClass());
    }

    private void applySyncMeta(SyncRow row, Map<Long, UserEntity> userMap, long userRefId,
                               String syncId, long version, long serverReceivedAt,
                               long clientUpdatedAt, boolean deleted, Long deletedAt,
                               long createdAt) {
        row.setUserId(requireUser(userMap, userRefId).getId());
        row.setSyncId(syncId);
        row.setVersion(version);
        row.setServerReceivedAt(Instant.ofEpochMilli(serverReceivedAt));
        row.setClientUpdatedAt(clientUpdatedAt);
        row.setDeleted(deleted);
        row.setDeletedAt(deletedAt);
        row.setCreatedAt(Instant.ofEpochMilli(createdAt));
    }

    private UserEntity requireUser(Map<Long, UserEntity> userMap, long refId) {
        UserEntity user = userMap.get(refId);
        if (user == null) {
            throw ApiException.badRequest("备份文件损坏：引用了不存在的用户");
        }
        return user;
    }

    private long incrementRecoveryEpoch() {
        ServerMeta meta = serverMetaRepository.findById(ServerMeta.KEY_RECOVERY_EPOCH)
                .orElseGet(() -> new ServerMeta(ServerMeta.KEY_RECOVERY_EPOCH, "0"));
        long next;
        try {
            next = Long.parseLong(meta.getValue()) + 1;
        } catch (NumberFormatException e) {
            next = 1;
        }
        meta.setValue(String.valueOf(next));
        serverMetaRepository.save(meta);
        return next;
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
