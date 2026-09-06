package com.skyanchor.bookkeeping.server.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 账本（V3.2 基线第 3.2 章）：所有业务数据的根节点。
 *
 * <p>user_id 恒为账本 OWNER；成员关系见 {@link LedgerMemberRow}。
 * sync_id 全局唯一：成员设备凭账本 syncId 寻址，不要求是 OWNER。
 * Ledger 不是 SyncRow 子类（不挂 user_id 隔离语义），但遵循同一套
 * version / server_received_at / 软删元数据，参与 LEDGER 实体类型的同步。
 */
@Entity
@Table(name = "ledgers")
public class LedgerRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "sync_id", nullable = false, unique = true)
    private String syncId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description = "";

    @Column(nullable = false)
    private String currency = "CNY";

    /** 迁移回填产生的默认账本标记：客户端 claim 合并与 UI 展示依赖它。 */
    @Column(name = "is_default", nullable = false)
    private boolean defaultLedger;

    @Column(name = "is_archived", nullable = false)
    private boolean archived;

    @Column(nullable = false)
    private long version;

    @Column(name = "server_received_at", nullable = false)
    private Instant serverReceivedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    /** 软删时间（epoch millis，客户端删除时产生并随载荷传播）。 */
    @Column(name = "deleted_at")
    private Long deletedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private long clientUpdatedAt;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSyncId() {
        return syncId;
    }

    public void setSyncId(String syncId) {
        this.syncId = syncId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public boolean isDefaultLedger() {
        return defaultLedger;
    }

    public void setDefaultLedger(boolean defaultLedger) {
        this.defaultLedger = defaultLedger;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
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

    public long getClientUpdatedAt() {
        return clientUpdatedAt;
    }

    public void setClientUpdatedAt(long clientUpdatedAt) {
        this.clientUpdatedAt = clientUpdatedAt;
    }
}
