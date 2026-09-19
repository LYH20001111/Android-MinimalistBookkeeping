package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 退款流水：一笔账单对应 0..N 条退款记录（开发基线第 10、12 章）。
 *
 * <p>原账单始终保留、金额不改写；退款只累计到
 * {@link TransactionEntity#refundedAmount}（已到账）与
 * {@link TransactionEntity#pendingRefundAmount}（待到账），两者之和不超过账单金额。
 * 统计与账户余额按净额（amount − refunded_amount）计算，因此本表存在期间删除必须走软删。
 *
 * <p>状态机（冻结决策第 4 条）：{@link #STATE_PENDING} →（标记到账）{@link #STATE_RECEIVED}；
 * {@link #STATE_PENDING} / {@link #STATE_RECEIVED} →（撤销）{@link #STATE_CANCELLED}。
 * 已到账允许撤销并同步还原冲减掉的统计与余额；撤销与失败的记录一律保留可查（基线第 16 章）。
 *
 * <p>与编辑日志不同，退款是资金事实，参与云同步，因此带齐 V3 同步元数据与 V3.2 账本归属。
 */
@Entity(
        tableName = "refund_record",
        foreignKeys = {
                @ForeignKey(
                        entity = TransactionEntity.class,
                        parentColumns = "id",
                        childColumns = "transaction_id",
                        onDelete = ForeignKey.RESTRICT)
        },
        indices = {
                @Index(value = "transaction_id"),
                @Index(value = "sync_id"),
                @Index(value = "ledger_id")
        })
public class RefundRecordEntity {

    /** 已发起、款项未到账：不冲减统计与余额。 */
    public static final String STATE_PENDING = "PENDING";

    /** 已到账：计入账单的 refunded_amount，统计与余额按净额。 */
    public static final String STATE_RECEIVED = "RECEIVED";

    /** 已撤销：保留记录，额度还原。 */
    public static final String STATE_CANCELLED = "CANCELLED";

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 跨设备稳定身份（UUID），入库时分配。 */
    @NonNull
    @ColumnInfo(name = "sync_id", defaultValue = "")
    public String syncId = "";

    /** 所属账单的本地行 id（transactions.id）。 */
    @ColumnInfo(name = "transaction_id")
    public long transactionId;

    /** 所属账本，随账单对齐。 */
    @ColumnInfo(name = "ledger_id", defaultValue = "1")
    public long ledgerId = 1;

    /** 本次退款金额，单位：分。 */
    @ColumnInfo(name = "amount")
    public long amount;

    /** {@link #STATE_PENDING} / {@link #STATE_RECEIVED} / {@link #STATE_CANCELLED}。 */
    @NonNull
    @ColumnInfo(name = "state")
    public String state = STATE_PENDING;

    /** 退款原因，可为空，最多 100 字。 */
    @Nullable
    @ColumnInfo(name = "reason")
    public String reason;

    /** 发起时间（epoch millis）。 */
    @ColumnInfo(name = "requested_at")
    public long requestedAt;

    /** 到账时间（epoch millis）；未到账与已撤销为 null。 */
    @Nullable
    @ColumnInfo(name = "received_at")
    public Long receivedAt;

    // ===== 同步元数据（与交易表同语义）=====

    @ColumnInfo(name = "version", defaultValue = "0")
    public long version;

    @ColumnInfo(name = "server_received_at", defaultValue = "0")
    public long serverReceivedAt;

    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    public boolean isDeleted;

    @Nullable
    @ColumnInfo(name = "deleted_at")
    public Long deletedAt;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    public boolean isPending() {
        return STATE_PENDING.equals(state);
    }

    public boolean isReceived() {
        return STATE_RECEIVED.equals(state);
    }

    public boolean isCancelled() {
        return STATE_CANCELLED.equals(state);
    }
}
