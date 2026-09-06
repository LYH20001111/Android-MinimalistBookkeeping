package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 交易记录表。
 *
 * <p>金额一律使用 long 保存「分」，禁止使用 double/float（V1 基线第 11、12 章）。
 * {@link #date} 保存的是业务日期当天 00:00 的 epoch millis，所有区间查询与按日分组都基于它。
 *
 * <p>V2 升级：
 * <ul>
 *   <li>{@link #categoryId} 改为可空：转账（type=3）不归属任何分类，写入 NULL。
 *       SQLite 对 NULL 子键不做外键校验，因此转账不会触发分类外键约束。</li>
 *   <li>新增 {@link #accountId}（支出=付款账户 / 收入=收款账户 / 转账=转出账户）与
 *       {@link #transferAccountId}（仅转账=转入账户），均可空、外键 RESTRICT，
 *       历史账单迁移后保持 NULL（早于账户体系，不参与任何账户余额）。</li>
 * </ul>
 */
@Entity(
        tableName = "transactions",
        foreignKeys = {
                @ForeignKey(
                        entity = CategoryEntity.class,
                        parentColumns = "id",
                        childColumns = "category_id",
                        onDelete = ForeignKey.RESTRICT),
                @ForeignKey(
                        entity = AccountEntity.class,
                        parentColumns = "id",
                        childColumns = "account_id",
                        onDelete = ForeignKey.RESTRICT),
                @ForeignKey(
                        entity = AccountEntity.class,
                        parentColumns = "id",
                        childColumns = "transfer_account_id",
                        onDelete = ForeignKey.RESTRICT)
        },
        indices = {
                @Index(value = "category_id"),
                @Index(value = "date"),
                @Index(value = "account_id"),
                @Index(value = "transfer_account_id"),
                @Index(value = "ledger_id"),
                @Index(value = "sync_id")
        })
public class TransactionEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 1=支出，2=收入，3=转账。 */
    @ColumnInfo(name = "type")
    public int type = CategoryEntity.TYPE_EXPENSE;

    /** 金额，单位：分。 */
    @ColumnInfo(name = "amount")
    public long amount;

    /** 分类 id；转账时为 NULL。 */
    @Nullable
    @ColumnInfo(name = "category_id")
    public Long categoryId;

    /** 账户 id：支出=付款账户、收入=收款账户、转账=转出账户；历史账单为 NULL。 */
    @Nullable
    @ColumnInfo(name = "account_id")
    public Long accountId;

    /** 转入账户 id，仅转账使用；其余类型为 NULL。 */
    @Nullable
    @ColumnInfo(name = "transfer_account_id")
    public Long transferAccountId;

    /** 业务日期，当天 00:00 的 epoch millis。 */
    @ColumnInfo(name = "date")
    public long date;

    /** 业务时间，格式 HH:mm。 */
    @NonNull
    @ColumnInfo(name = "time")
    public String time = "00:00";

    /** 备注，可为空，最多 100 字。 */
    @Nullable
    @ColumnInfo(name = "note")
    public String note;

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

    /** 软删发生时间（epoch millis，V3.1 回收站排序与展示用）；未删除为 null，随载荷传播。 */
    @Nullable
    @ColumnInfo(name = "deleted_at")
    public Long deletedAt;

    // ===== V3.2 账本归属（基线第 3.2 章）=====

    /** 所属账本的本地行 id；迁移回填数据归默认账本（id=1），写入时由 Repository 对齐当前账本。 */
    @ColumnInfo(name = "ledger_id", defaultValue = "1")
    public long ledgerId = 1;

}

