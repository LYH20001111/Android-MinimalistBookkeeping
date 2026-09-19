package com.skyanchor.bookkeeping.server.sync.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 退款流水（V4.0，Sync Protocol 3）。
 *
 * <p>服务端只存事实：原账单金额不因退款而改写，退款以独立行存在，
 * {@code state} 的三态语义（PENDING/RECEIVED/CANCELLED）由客户端解释，
 * 账单上的退款累计是客户端从本表重算出的缓存。
 */
@Entity
@Table(name = "refunds")
public class RefundRow extends SyncRow {

    /** state 缺省值：备份 / 推送未带状态时按「已发起、未到账」落库。 */
    public static final String STATE_PENDING = "PENDING";

    /** 所属账单的 syncId（服务层引用校验，不建跨表外键）。 */
    @Column(name = "transaction_sync_id", nullable = false)
    private String transactionSyncId;

    /** 本次退款金额，单位：分。 */
    @Column(nullable = false)
    private long amount;

    /** PENDING / RECEIVED / CANCELLED。 */
    @Column(nullable = false)
    private String state = STATE_PENDING;

    @Column(nullable = false)
    private String reason = "";

    /** 发起时间（epoch millis，客户端产生）。 */
    @Column(name = "requested_at", nullable = false)
    private long requestedAt;

    /** 到账时间（epoch millis）；未到账与已撤销为 null。 */
    @Column(name = "received_at")
    private Long receivedAt;

    public String getTransactionSyncId() {
        return transactionSyncId;
    }

    public void setTransactionSyncId(String transactionSyncId) {
        this.transactionSyncId = transactionSyncId;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public long getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(long requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Long getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Long receivedAt) {
        this.receivedAt = receivedAt;
    }
}
