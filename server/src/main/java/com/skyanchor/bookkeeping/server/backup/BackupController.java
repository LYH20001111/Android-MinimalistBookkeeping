package com.skyanchor.bookkeeping.server.backup;

import com.skyanchor.bookkeeping.server.auth.AuthUser;
import com.skyanchor.bookkeeping.server.auth.repo.DeviceRepository;
import com.skyanchor.bookkeeping.server.auth.repo.UserRepository;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.BackupMeta;
import com.skyanchor.bookkeeping.server.backup.BackupDtos.RestoreReport;
import com.skyanchor.bookkeeping.server.common.ApiVersionFilter;
import com.skyanchor.bookkeeping.server.common.ServerInfo;
import com.skyanchor.bookkeeping.server.common.ServerMeta;
import com.skyanchor.bookkeeping.server.common.ServerMetaRepository;
import com.skyanchor.bookkeeping.server.common.SyncWriteBarrier;
import com.skyanchor.bookkeeping.server.sync.repo.TransactionRowRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Paths;
import java.util.List;

/**
 * 服务器管理 API（V3.1 决策 1：备份 / 恢复由服务器管理员操作）。
 * 全部接口要求登录 + 管理员身份；浏览器端入口是内置管理页 /admin。
 * 任何接口都不返回用户业务数据明细，只返回计数与文件摘要。
 */
@RestController
@RequestMapping("/api/v1/server")
public class BackupController {

    public record StatsResponse(String serverVersion, int apiVersion, int syncProtocolVersion,
                                long users, long devices, long transactionRows,
                                StorageSummary storage, Long lastBackupAt, String lastBackupFile,
                                long recoveryEpoch, long serverTime) {
    }

    public record StorageSummary(long totalBytes, long freeBytes) {
    }

    private final BackupService backupService;
    private final BackupRestoreService restoreService;
    private final AdminGuard adminGuard;
    private final SyncWriteBarrier writeBarrier;
    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final TransactionRowRepository transactionRepository;
    private final ServerMetaRepository serverMetaRepository;

    public BackupController(BackupService backupService, BackupRestoreService restoreService,
                            AdminGuard adminGuard, SyncWriteBarrier writeBarrier,
                            UserRepository userRepository, DeviceRepository deviceRepository,
                            TransactionRowRepository transactionRepository,
                            ServerMetaRepository serverMetaRepository) {
        this.backupService = backupService;
        this.restoreService = restoreService;
        this.adminGuard = adminGuard;
        this.writeBarrier = writeBarrier;
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.transactionRepository = transactionRepository;
        this.serverMetaRepository = serverMetaRepository;
    }

    @GetMapping("/backup")
    public List<BackupMeta> listBackups() {
        requireAdmin();
        return backupService.listBackups();
    }

    @PostMapping("/backup")
    public BackupMeta createBackup() {
        requireAdmin();
        // 读锁：导出期间允许普通读，阻塞恢复；导出是一致性快照
        return writeBarrier.read(() -> backupService.createBackup(BackupDtos.TRIGGER_API));
    }

    @GetMapping("/backup/{name}/meta")
    public BackupMeta backupMeta(@PathVariable("name") String name) {
        requireAdmin();
        return backupService.readMeta(name);
    }

    /** 恢复前请阅读管理页警告：影响所有设备，恢复后设备需重新登录并重新收敛。 */
    @PostMapping("/backup/{name}/restore")
    public RestoreReport restore(@PathVariable("name") String name) {
        requireAdmin();
        return writeBarrier.write(() -> restoreService.restore(name));
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        requireAdmin();
        var probe = Paths.get("").toAbsolutePath().toFile();
        Long lastBackupAt = serverMetaRepository.findById(ServerMeta.KEY_LAST_BACKUP_AT)
                .map(meta -> {
                    try {
                        long value = Long.parseLong(meta.getValue());
                        return value > 0 ? value : null;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }).orElse(null);
        String lastBackupFile = serverMetaRepository
                .findById(ServerMeta.KEY_LAST_BACKUP_FILE)
                .map(meta -> backupService.backupFileExists(meta.getValue())
                        ? meta.getValue() : null)
                .orElse(null);
        return new StatsResponse(ServerInfo.SERVER_VERSION, ApiVersionFilter.API_VERSION,
                ApiVersionFilter.SYNC_PROTOCOL_VERSION,
                userRepository.count(), deviceRepository.count(), transactionRepository.count(),
                new StorageSummary(probe.getTotalSpace(), probe.getUsableSpace()),
                lastBackupAt, lastBackupFile, recoveryEpoch(), System.currentTimeMillis());
    }

    private long recoveryEpoch() {
        return serverMetaRepository.findById(ServerMeta.KEY_RECOVERY_EPOCH)
                .map(meta -> {
                    try {
                        return Long.parseLong(meta.getValue());
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    private void requireAdmin() {
        adminGuard.requireAdmin(AuthUser.current());
    }
}
