package com.skyanchor.bookkeeping.server.backup;

import com.skyanchor.bookkeeping.server.common.SyncWriteBarrier;
import com.skyanchor.bookkeeping.server.config.BackupProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;

/**
 * 每日自动备份（V3.1 基线第 12.2 章，默认 03:00，可配置）。
 * 磁盘剩余空间低于阈值时跳过并记日志（基线第 13 章：保留数量受磁盘限制）。
 */
@Component
public class BackupScheduler {

    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);

    private final BackupService backupService;
    private final BackupProperties properties;
    private final SyncWriteBarrier writeBarrier;

    public BackupScheduler(BackupService backupService, BackupProperties properties,
                           SyncWriteBarrier writeBarrier) {
        this.backupService = backupService;
        this.properties = properties;
        this.writeBarrier = writeBarrier;
    }

    @Scheduled(cron = "${app.backup.daily-cron:0 0 3 * * *}")
    public void dailyBackup() {
        if (!properties.isAutoEnabled()) {
            return;
        }
        long free = Paths.get("").toAbsolutePath().toFile().getUsableSpace();
        if (free < properties.getMinFreeBytes()) {
            log.warn("auto backup skipped: free disk {} bytes < min {} bytes",
                    free, properties.getMinFreeBytes());
            return;
        }
        try {
            writeBarrier.read(() -> backupService.createBackup(BackupDtos.TRIGGER_SCHEDULED));
        } catch (Exception e) {
            // 定时任务不允许异常外抛，避免中断调度线程
            log.error("auto backup failed: {}", e.getMessage());
        }
    }
}
