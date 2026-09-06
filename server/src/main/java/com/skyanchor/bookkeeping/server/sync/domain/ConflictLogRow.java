package com.skyanchor.bookkeeping.server.sync.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** LWW 冲突审计（基线第 18 章）：记录败者摘要，不阻塞用户、仅用于追踪与验证。 */
@Entity
@Table(name = "conflict_logs")
public class ConflictLogRow {

    public static final String WINNER_CLIENT = "CLIENT";
    public static final String WINNER_SERVER = "SERVER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 冲突所属账本（V3.2）；v1 备份恢复的旧行允许为空。 */
    @Column(name = "ledger_id")
    private Long ledgerId;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "sync_id", nullable = false)
    private String syncId;

    @Column(name = "client_device_id", nullable = false)
    private String clientDeviceId = "";

    @Column(name = "base_version", nullable = false)
    private long baseVersion;

    @Column(name = "server_version", nullable = false)
    private long serverVersion;

    @Column(name = "client_payload_digest", nullable = false)
    private String clientPayloadDigest;

    @Column(name = "server_payload_digest", nullable = false)
    private String serverPayloadDigest;

    @Column(nullable = false)
    private String winner;

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

    public String getClientDeviceId() {
        return clientDeviceId;
    }

    public void setClientDeviceId(String clientDeviceId) {
        this.clientDeviceId = clientDeviceId;
    }

    public long getBaseVersion() {
        return baseVersion;
    }

    public void setBaseVersion(long baseVersion) {
        this.baseVersion = baseVersion;
    }

    public long getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(long serverVersion) {
        this.serverVersion = serverVersion;
    }

    public String getClientPayloadDigest() {
        return clientPayloadDigest;
    }

    public void setClientPayloadDigest(String clientPayloadDigest) {
        this.clientPayloadDigest = clientPayloadDigest;
    }

    public String getServerPayloadDigest() {
        return serverPayloadDigest;
    }

    public void setServerPayloadDigest(String serverPayloadDigest) {
        this.serverPayloadDigest = serverPayloadDigest;
    }

    public String getWinner() {
        return winner;
    }

    public void setWinner(String winner) {
        this.winner = winner;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
