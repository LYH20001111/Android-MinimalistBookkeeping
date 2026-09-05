package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import java.util.Objects;

/**
 * 账户表（V2 新增）。现金 / 微信 / 支付宝 / 储蓄卡 / 信用卡 / 其他。
 *
 * <p>余额统一「可正可负」模型：
 * {@code balance = initial_balance + 收入 - 支出 + 转入 - 转出}，
 * 信用卡欠款即负余额，不单独设计信用账务。
 *
 * <p>{@link #balance} 是缓存列：写入交易时在同一 DB 事务内重算并更新；
 * {@code CalculateAccountBalanceUseCase} 从交易重算，是唯一真值来源，
 * 缓存与重算不一致时以重算纠正。
 *
 * <p>账户被历史账单引用后禁止物理删除，只能归档（{@link #isArchived}）。
 */
@Entity(tableName = "account", indices = {@Index(value = "sync_id")})
public class AccountEntity {

    /** 现金。 */
    public static final int TYPE_CASH = 1;
    /** 微信。 */
    public static final int TYPE_WECHAT = 2;
    /** 支付宝。 */
    public static final int TYPE_ALIPAY = 3;
    /** 储蓄卡（借记卡）。 */
    public static final int TYPE_DEBIT = 4;
    /** 信用卡。 */
    public static final int TYPE_CREDIT = 5;
    /** 其他。 */
    public static final int TYPE_OTHER = 6;

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "name")
    public String name = "";

    /** 账户类型，取值见 TYPE_* 常量。 */
    @ColumnInfo(name = "type")
    public int type = TYPE_CASH;

    /** 初始余额，单位：分，可正可负。 */
    @ColumnInfo(name = "initial_balance")
    public long initialBalance;

    /** 余额缓存列，单位：分，可正可负；真值以交易重算为准。 */
    @ColumnInfo(name = "balance")
    public long balance;

    /** 是否为信用账户（信用卡），仅用于展示语义，不改变余额模型。 */
    @ColumnInfo(name = "is_credit")
    public boolean isCredit;

    @ColumnInfo(name = "sort_order")
    public int sortOrder;

    /** 归档标记：被账单引用的账户只能归档、不能物理删除。 */
    @ColumnInfo(name = "is_archived")
    public boolean isArchived;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;


    // ===== V3 同步元数据（基线第 14 章）=====

    /** 跨设备稳定身份（UUID）；本地行入库时即分配，与本地自增 id 职责分离。 */
    @NonNull
    @ColumnInfo(name = "sync_id", defaultValue = "")
    public String syncId = "";

    /** 客户端最后一次从服务器确认的版本；0 = 从未与服务器同步。 */
    @ColumnInfo(name = "version", defaultValue = "0")
    public long version;

    /** 服务器最后一次确认该行的时间（epoch millis）；0 = 从未同步。 */
    @ColumnInfo(name = "server_received_at", defaultValue = "0")
    public long serverReceivedAt;

    /** Soft Delete 标记（基线第 17 章）：删除 = 置位 + 版本递增，作为可同步事件传播。 */
    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    public boolean isDeleted;

    public AccountEntity() {
    }

    @Ignore
    public AccountEntity(@NonNull String name, int type, long initialBalance, boolean isCredit,
                         int sortOrder) {
        this.name = name;
        this.type = type;
        this.initialBalance = initialBalance;
        this.balance = initialBalance;
        this.isCredit = isCredit;
        this.sortOrder = sortOrder;
        this.isArchived = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AccountEntity)) {
            return false;
        }
        AccountEntity other = (AccountEntity) o;
        return id == other.id
                && type == other.type
                && initialBalance == other.initialBalance
                && balance == other.balance
                && isCredit == other.isCredit
                && sortOrder == other.sortOrder
                && isArchived == other.isArchived
                && Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, type, initialBalance, balance, isCredit, sortOrder,
                isArchived);
    }
}
