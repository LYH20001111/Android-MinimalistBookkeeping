package com.skyanchor.bookkeeping.server.sync.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 同步变更日志（基线 21.3）：change_id 单调递增，客户端按游标增量拉取。
 * 载荷不冗余存储，Pull 时按业务表当前状态组装（只回传每个 syncId 的最新一条）。
 */
@Entity
@Table(name = "sync_changes")
public class SyncChangeRow {

    public static final String OP_UPSERT = "UPSERT";
    public static final String OP_DELETE = "DELETE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 变更所属账本（V3.2）：Pull 按账本隔离，同一账本的所有成员共享这条变更流。 */
    @Column(name = "ledger_id", nullable = false)
    private Long ledgerId;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "sync_id", nullable = false)
    private String syncId;

    @Column(nullable = false)
    private long version;

    @Column(nullable = false)
    private String operation;

    @Column(name = "server_received_at", nullable = false)
    private Instant serverReceivedAt;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getLedgerId() {
        return ledgerId;
    }

    public void setLedgerId(Long ledgerId) {
        this.ledgerId = ledgerId;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getSyncId() {
        return syncId;
    }

    public void setSyncId(String syncId) {
        this.syncId = syncId;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Instant getServerReceivedAt() {
        return serverReceivedAt;
    }

    public void setServerReceivedAt(Instant serverReceivedAt) {
        this.serverReceivedAt = serverReceivedAt;
    }
}
