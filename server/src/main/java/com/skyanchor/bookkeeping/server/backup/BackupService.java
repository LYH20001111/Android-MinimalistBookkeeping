package com.skyanchor.bookkeeping.server.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyanchor.bookkeeping.server.auth.domain.DeviceEntity;
import com.skyanchor.bookkeeping.server.auth.domain.UserEntity;
import com.skyanchor.bookkeeping.server.auth.repo.DeviceRepository;
import com.skyanchor.bookkeeping.server.auth.repo.UserRepository;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.AccountEntry;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.BackupCounts;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.BackupFile;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.BackupMeta;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.BudgetEntry;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.CategoryEntry;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.ConflictEntry;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.DeviceEntry;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.RecurringEntry;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.TransactionEntry;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.UserEntry;
import com.skyanchor.bookkeeping.server.backup.BackupRetentionPolicy.BackupRef;
import com.skyanchor.bookkeeping.server.common.ApiException;
import com.skyanchor.bookkeeping.server.common.ServerInfo;
import com.skyanchor.bookkeeping.server.common.ServerMeta;
import com.skyanchor.bookkeeping.server.common.ServerMetaRepository;
import com.skyanchor.bookkeeping.server.config.BackupProperties;
import com.skyanchor.bookkeeping.server.sync.domain.AccountRow;
import com.skyanchor.bookkeeping.server.sync.domain.BudgetRow;
import com.skyanchor.bookkeeping.server.sync.domain.CategoryRow;
import com.skyanchor.bookkeeping.server.sync.domain.ConflictLogRow;
import com.skyanchor.bookkeeping.server.sync.domain.RecurringRow;
import com.skyanchor.bookkeeping.server.sync.domain.SyncRow;
import com.skyanchor.bookkeeping.server.sync.domain.TransactionRow;
import com.skyanchor.bookkeeping.server.sync.repo.AccountRowRepository;
import com.skyanchor.bookkeeping.server.sync.repo.BudgetRowRepository;
import com.skyanchor.bookkeeping.server.sync.repo.CategoryRowRepository;
import com.skyanchor.bookkeeping.server.sync.repo.ConflictLogRepository;
import com.skyanchor.bookkeeping.server.sync.repo.RecurringRowRepository;
import com.skyanchor.bookkeeping.server.sync.repo.TransactionRowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 服务器备份（V3.1 基线第 11-14 章）：全库导出 JSON + 边车摘要 + 保留策略清理。
 * 手动 / 定时 / API 三种触发共用一条导出路径；调用方负责先取同步写屏障读锁，
 * 保证导出期间无并发写入，文件内容是一致性快照。
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    static final String FILE_PREFIX = "bookkeeping-backup-";
    /** 备份数据文件名（stamp 为 UTC 时间），边车摘要在其后追加 ".meta"。 */
    private static final Pattern SAFE_NAME = Pattern.compile(
            "^" + Pattern.quote(FILE_PREFIX) + "(?<stamp>\\d{8}-\\d{6})(-\\d+)?\\.json$");

    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final BackupProperties properties;
    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final CategoryRowRepository categoryRepository;
    private final AccountRowRepository accountRepository;
    private final TransactionRowRepository transactionRepository;
    private final BudgetRowRepository budgetRepository;
    private final RecurringRowRepository recurringRepository;
    private final ConflictLogRepository conflictRepository;
    private final ServerMetaRepository serverMetaRepository;
    private final ObjectMapper objectMapper;

    public BackupService(BackupProperties properties,
                         UserRepository userRepository,
                         DeviceRepository deviceRepository,
                         CategoryRowRepository categoryRepository,
                         AccountRowRepository accountRepository,
                         TransactionRowRepository transactionRepository,
                         BudgetRowRepository budgetRepository,
                         RecurringRowRepository recurringRepository,
                         ConflictLogRepository conflictRepository,
                         ServerMetaRepository serverMetaRepository,
                         ObjectMapper objectMapper) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.recurringRepository = recurringRepository;
        this.conflictRepository = conflictRepository;
        this.serverMetaRepository = serverMetaRepository;
        this.objectMapper = objectMapper;
    }

    // ===== 导出 =====

    /** 创建一份备份并写入 server_meta 最近备份信息；须在写屏障读锁内调用。 */
    @Transactional
    public BackupMeta createBackup(String trigger) {
        long now = System.currentTimeMillis();
        BackupFile file = exportAll(now, trigger);
        String name = FILE_PREFIX + STAMP.format(Instant.ofEpochMilli(now)) + ".json";
        Path target = backupDir().resolve(name);
        try {
            if (Files.exists(target)) {
                // 同秒重名（手动+定时撞车）：加序号保证不覆盖旧备份
                int seq = 1;
                while (Files.exists(target)) {
                    name = FILE_PREFIX + STAMP.format(Instant.ofEpochMilli(now))
                            + "-" + (seq++) + ".json";
                    target = backupDir().resolve(name);
                }
            }
            Files.createDirectories(backupDir());
            Files.writeString(target, objectMapper.writeValueAsString(file));
        } catch (IOException e) {
            log.error("backup write failed: {}", e.getMessage());
            throw new ApiException(500, "BACKUP_FAILED", "备份文件写入失败：" + e.getMessage());
        }
        BackupMeta meta = toMeta(name, target, file);
        writeMetaSidecar(meta);
        saveServerMeta(ServerMeta.KEY_LAST_BACKUP_AT, String.valueOf(now));
        saveServerMeta(ServerMeta.KEY_LAST_BACKUP_FILE, name);
        pruneRetention();
        log.info("backup created name={} trigger={} users={} transactions={}",
                name, trigger, file.counts().users(), file.counts().transactions());
        return meta;
    }

    private BackupFile exportAll(long now, String trigger) {
        List<UserEntry> users = new ArrayList<>();
        for (UserEntity user : userRepository.findAll()) {
            users.add(new UserEntry(user.getId(), user.getEmail(), user.getPasswordHash(),
                    user.isEmailVerified(), toMillis(user.getCreatedAt()),
                    toMillis(user.getUpdatedAt()), toMillisOrNull(user.getDeletedAt())));
        }
        List<DeviceEntry> devices = new ArrayList<>();
        for (DeviceEntity device : deviceRepository.findAll()) {
            devices.add(new DeviceEntry(device.getId(), device.getUserId(), device.getDeviceId(),
                    device.getDeviceName(), device.getPlatform(), device.getAppVersion(),
                    toMillis(device.getLastSeenAt()), toMillis(device.getCreatedAt()),
                    toMillisOrNull(device.getRevokedAt())));
        }
        List<CategoryEntry> categories = new ArrayList<>();
        for (CategoryRow row : categoryRepository.findAll()) {
            categories.add(new CategoryEntry(row.getUserId(), row.getSyncId(), row.getVersion(),
                    row.getServerReceivedAt().toEpochMilli(), row.getClientUpdatedAt(),
                    row.isDeleted(), row.getDeletedAt(), toMillis(row.getCreatedAt()),
                    row.getName(), row.getIcon(), row.getType(), row.getSortOrder(),
                    row.isDefault()));
        }
        List<AccountEntry> accounts = new ArrayList<>();
        for (AccountRow row : accountRepository.findAll()) {
            accounts.add(new AccountEntry(row.getUserId(), row.getSyncId(), row.getVersion(),
                    row.getServerReceivedAt().toEpochMilli(), row.getClientUpdatedAt(),
                    row.isDeleted(), row.getDeletedAt(), toMillis(row.getCreatedAt()),
                    row.getName(), row.getType(), row.getInitialBalance(), row.getBalance(),
                    row.isCredit(), row.getSortOrder(), row.isArchived()));
        }
        List<TransactionEntry> transactions = new ArrayList<>();
        for (TransactionRow row : transactionRepository.findAll()) {
            transactions.add(new TransactionEntry(row.getUserId(), row.getSyncId(),
                    row.getVersion(), row.getServerReceivedAt().toEpochMilli(),
                    row.getClientUpdatedAt(), row.isDeleted(), row.getDeletedAt(),
                    toMillis(row.getCreatedAt()), row.getType(), row.getAmount(), row.getDate(),
                    row.getTime(), row.getNote(), row.getCategorySyncId(),
                    row.getAccountSyncId(), row.getTransferAccountSyncId(),
                    row.getClientCreatedAt()));
        }
        List<BudgetEntry> budgets = new ArrayList<>();
        for (BudgetRow row : budgetRepository.findAll()) {
            budgets.add(new BudgetEntry(row.getUserId(), row.getSyncId(), row.getVersion(),
                    row.getServerReceivedAt().toEpochMilli(), row.getClientUpdatedAt(),
                    row.isDeleted(), row.getDeletedAt(), toMillis(row.getCreatedAt()),
                    row.getYear(), row.getMonth(), row.getCategorySyncId(), row.getAmount()));
        }
        List<RecurringEntry> recurring = new ArrayList<>();
        for (RecurringRow row : recurringRepository.findAll()) {
            recurring.add(new RecurringEntry(row.getUserId(), row.getSyncId(), row.getVersion(),
                    row.getServerReceivedAt().toEpochMilli(), row.getClientUpdatedAt(),
                    row.isDeleted(), row.getDeletedAt(), toMillis(row.getCreatedAt()),
                    row.getName(), row.getType(), row.getAmount(), row.getCategorySyncId(),
                    row.getAccountSyncId(), row.getFrequency(), row.getRepeatInterval(),
                    row.getStartDate(), row.getEndDate(), row.getNextRunDate(),
                    row.getAnchorDayOfMonth(), row.isEnabled(), row.getNote()));
        }
        List<ConflictEntry> conflicts = new ArrayList<>();
        for (ConflictLogRow row : conflictRepository.findAll()) {
            conflicts.add(new ConflictEntry(row.getUserId(), row.getEntityType(),
                    row.getSyncId(), row.getClientDeviceId(), row.getBaseVersion(),
                    row.getServerVersion(), row.getClientPayloadDigest(),
                    row.getServerPayloadDigest(), row.getWinner(),
                    toMillis(row.getCreatedAt())));
        }
        BackupCounts counts = new BackupCounts(users.size(), devices.size(), categories.size(),
                accounts.size(), transactions.size(), budgets.size(), recurring.size(),
                conflicts.size());
        return new BackupFile(BackupDtos.FORMAT, BackupDtos.FORMAT_VERSION,
                ServerInfo.SERVER_VERSION, now, recoveryEpoch(), trigger, counts,
                users, devices, categories, accounts, transactions, budgets, recurring,
                conflicts);
    }

    private long recoveryEpoch() {
        return serverMetaRepository.findById(ServerMeta.KEY_RECOVERY_EPOCH)
                .map(meta -> parseLongOrZero(meta.getValue()))
                .orElse(0L);
    }

    // ===== 列表 / 摘要 =====

    public List<BackupMeta> listBackups() {
        List<BackupMeta> list = new ArrayList<>();
        if (!Files.isDirectory(backupDir())) {
            return list;
        }
        try (var stream = Files.list(backupDir())) {
            stream.filter(p -> SAFE_NAME.matcher(p.getFileName().toString()).matches())
                    .map(p -> readMeta(p.getFileName().toString()))
                    .filter(java.util.Objects::nonNull)
                    .forEach(list::add);
        } catch (IOException e) {
            log.warn("backup list failed: {}", e.getMessage());
        }
        list.sort(Comparator.comparingLong(BackupMeta::createdAt).reversed());
        return list;
    }

    public BackupMeta readMeta(String name) {
        Path data = resolveExisting(name);
        Path sidecar = data.resolveSibling(data.getFileName().toString() + ".meta");
        if (Files.isRegularFile(sidecar)) {
            try {
                return objectMapper.readValue(Files.readString(sidecar), BackupMeta.class);
            } catch (IOException e) {
                log.warn("backup meta parse failed name={} : {}", name, e.getMessage());
            }
        }
        // 边车缺失（例如写入中断）：退化为文件名时间 + 文件大小
        var matcher = SAFE_NAME.matcher(data.getFileName().toString());
        if (!matcher.matches()) {
            return null;
        }
        try {
            long createdAt = Instant.from(STAMP.parse(matcher.group("stamp")))
                    .toEpochMilli();
            return new BackupMeta(name, createdAt, Files.size(data), null, 0, null, null);
        } catch (IOException | java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    byte[] readBackupBytes(String name) {
        try {
            return Files.readAllBytes(resolveExisting(name));
        } catch (IOException e) {
            throw new ApiException(500, "BACKUP_READ_FAILED", "备份文件读取失败");
        }
    }

    // ===== 保留策略 =====

    /** 按日 / 周 / 月分桶保留，其余删除（数据文件与边车摘要一起删）。 */
    void pruneRetention() {
        List<BackupRef> refs = new ArrayList<>();
        try (var stream = Files.list(backupDir())) {
            stream.map(p -> p.getFileName().toString())
                    .filter(name -> SAFE_NAME.matcher(name).matches())
                    .forEach(name -> {
                        var matcher = SAFE_NAME.matcher(name);
                        if (matcher.matches()) {
                            try {
                                refs.add(new BackupRef(name, Instant
                                        .from(STAMP.parse(matcher.group("stamp")))
                                        .toEpochMilli()));
                            } catch (java.time.format.DateTimeParseException ignored) {
                                // 文件名损坏：不参与分桶，也不删除（宁可多留）
                            }
                        }
                    });
        } catch (IOException e) {
            log.warn("backup prune list failed: {}", e.getMessage());
            return;
        }
        if (refs.size() <= 1) {
            return;
        }
        java.util.Set<String> keep = BackupRetentionPolicy.computeKeepSet(refs);
        for (BackupRef ref : refs) {
            if (keep.contains(ref.name())) {
                continue;
            }
            deleteQuietly(ref.name());
        }
    }

    private void deleteQuietly(String name) {
        try {
            Files.deleteIfExists(backupDir().resolve(name));
            Files.deleteIfExists(backupDir().resolve(name + ".meta"));
            log.info("backup pruned name={}", name);
        } catch (IOException e) {
            log.warn("backup delete failed name={} : {}", name, e.getMessage());
        }
    }

    // ===== 工具 =====

    Path backupDir() {
        return Path.of(properties.getDir());
    }

    /** 最近一次备份是否仍存在于磁盘（供统计接口校验 server_meta 记录有效性）。 */
    public boolean backupFileExists(String name) {
        return name != null && Files.isRegularFile(backupDir().resolve(name));
    }

    /** 只允许本服务命名的文件名，杜绝路径穿越。 */
    Path resolveExisting(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw ApiException.badRequest("非法的备份文件名");
        }
        Path path = backupDir().resolve(name);
        if (!Files.isRegularFile(path)) {
            throw ApiException.badRequest("备份不存在：" + name);
        }
        return path;
    }

    private BackupMeta toMeta(String name, Path path, BackupFile file) {
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            size = 0;
        }
        return new BackupMeta(name, file.createdAt(), size, file.serverVersion(),
                file.recoveryEpoch(), file.trigger(), file.counts());
    }

    private void writeMetaSidecar(BackupMeta meta) {
        try {
            Files.writeString(backupDir().resolve(meta.name() + ".meta"),
                    objectMapper.writeValueAsString(meta));
        } catch (IOException e) {
            log.warn("backup meta sidecar write failed: {}", e.getMessage());
        }
    }

    private void saveServerMeta(String key, String value) {
        ServerMeta meta = serverMetaRepository.findById(key).orElse(null);
        if (meta == null) {
            serverMetaRepository.save(new ServerMeta(key, value));
        } else {
            meta.setValue(value);
            serverMetaRepository.save(meta);
        }
    }

    private static long toMillis(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    private static Long toMillisOrNull(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static long parseLongOrZero(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
