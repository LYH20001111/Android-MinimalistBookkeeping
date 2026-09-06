package com.skyanchor.bookkeeping.server.server;

import com.skyanchor.bookkeeping.server.common.ApiVersionFilter;
import com.skyanchor.bookkeeping.server.common.ServerInfo;
import com.skyanchor.bookkeeping.server.common.ServerMeta;
import com.skyanchor.bookkeeping.server.common.ServerMetaRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 服务器健康检查（V3.1 基线第 10/34 章，目标 A：服务器可用）。
 *
 * <p>公开端点（无需登录）：只暴露运行状态与容量水位，不含任何用户数据，
 * 供客户端“测试连接”、管理页与人工排障使用。磁盘水位：剩余 &gt;20% 正常，
 * 10%~20% 警告，&lt;10% 严重。
 */
@RestController
@RequestMapping("/api/v1/server")
public class ServerHealthController {

    private static final Logger log = LoggerFactory.getLogger(ServerHealthController.class);

    /** 磁盘剩余比例低于该值视为警告（10%）。 */
    private static final double DISK_WARN_RATIO = 0.10;
    /** 磁盘剩余比例低于该值视为严重（20%）。 */
    private static final double DISK_CRITICAL_RATIO = 0.20;

    public record StorageStatus(String status, long totalBytes, long freeBytes) {
    }

    public record HealthResponse(String status, String serverVersion, int apiVersion,
                                 int syncProtocolVersion, String database, StorageStatus storage,
                                 long recoveryEpoch, Long lastBackupAt, long serverTime) {
    }

    private final EntityManager entityManager;
    private final ServerMetaRepository serverMetaRepository;

    public ServerHealthController(EntityManager entityManager,
                                  ServerMetaRepository serverMetaRepository) {
        this.entityManager = entityManager;
        this.serverMetaRepository = serverMetaRepository;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        String database = checkDatabase();
        StorageStatus storage = checkStorage();
        // 总体状态：数据库不可用即 DOWN；数据库可用但磁盘严重不足降级为 DEGRADED
        String status;
        if ("DOWN".equals(database)) {
            status = "DOWN";
        } else if ("CRITICAL".equals(storage.status())) {
            status = "DEGRADED";
        } else {
            status = "UP";
        }
        return new HealthResponse(status, ServerInfo.SERVER_VERSION,
                ApiVersionFilter.API_VERSION, ApiVersionFilter.SYNC_PROTOCOL_VERSION,
                database, storage, recoveryEpoch(), lastBackupAt(), System.currentTimeMillis());
    }

    private String checkDatabase() {
        try {
            entityManager.createNativeQuery("SELECT 1").getSingleResult();
            return "UP";
        } catch (Exception e) {
            log.warn("health database check failed: {}", e.getMessage());
            return "DOWN";
        }
    }

    private StorageStatus checkStorage() {
        try {
            Path workDir = Paths.get("").toAbsolutePath();
            Path probe = Files.exists(workDir) ? workDir : Paths.get(".").toAbsolutePath();
            long total = probe.toFile().getTotalSpace();
            long free = probe.toFile().getUsableSpace();
            String status;
            if (total <= 0) {
                status = "UNKNOWN";
            } else {
                double freeRatio = (double) free / total;
                if (freeRatio < DISK_WARN_RATIO) {
                    status = "CRITICAL";
                } else if (freeRatio < DISK_CRITICAL_RATIO) {
                    status = "WARN";
                } else {
                    status = "UP";
                }
            }
            return new StorageStatus(status, total, free);
        } catch (Exception e) {
            log.warn("health storage check failed: {}", e.getMessage());
            return new StorageStatus("UNKNOWN", 0, 0);
        }
    }

    private long recoveryEpoch() {
        return serverMetaRepository.findById(ServerMeta.KEY_RECOVERY_EPOCH)
                .map(meta -> parseLongOrZero(meta.getValue()))
                .orElse(0L);
    }

    private Long lastBackupAt() {
        return serverMetaRepository.findById(ServerMeta.KEY_LAST_BACKUP_AT)
                .map(meta -> {
                    long value = parseLongOrZero(meta.getValue());
                    return value > 0 ? value : null;
                })
                .orElse(null);
    }

    private long parseLongOrZero(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
