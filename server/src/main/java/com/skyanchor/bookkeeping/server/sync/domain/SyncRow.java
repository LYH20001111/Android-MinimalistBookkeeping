package com.skyanchor.bookkeeping.server.sync.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import java.time.Instant;

/**
 * 可同步业务行的公共同步元数据契约（基线第 14 章）：
 * version = 服务器维护的整数版本；server_received_at = 服务器接收时间（最终裁决依据）；
 * is_deleted = Soft Delete；updated_at = 客户端业务修改时间（仅记录事实，不参与裁决）。
 */
@MappedSuperclass
public abstract class SyncRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 所属账本（V3.2）：业务根节点，所有查询与鉴权都按它隔离（基线第 4 章）。 */
    @Column(name = "ledger_id", nullable = false)
    private Long ledgerId;

    @Column(name = "sync_id", nullable = false)
    private String syncId;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "server_received_at", nullable = false)
    private Instant serverReceivedAt;

    @Column(name = "updated_at", nullable = false)
    private long clientUpdatedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    /** 软删发生时间（epoch millis，客户端删除时产生并随载荷传播）；未删除为 null。 */
    @Column(name = "deleted_at")
    private Long deletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

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

    public Instant getServerReceivedAt() {
        return serverReceivedAt;
    }

    public void setServerReceivedAt(Instant serverReceivedAt) {
        this.serverReceivedAt = serverReceivedAt;
    }

    public long getClientUpdatedAt() {
        return clientUpdatedAt;
    }

    public void setClientUpdatedAt(long clientUpdatedAt) {
        this.clientUpdatedAt = clientUpdatedAt;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
