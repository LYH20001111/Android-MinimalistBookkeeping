package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 账单编辑日志：记录每笔账单的创建与每次保存发生的字段级变更，供编辑页「编辑记录」查看。
 *
 * <p>{@link #detail} 是写入时刻生成的人类可读多行文本（每行一条「字段：旧值 → 新值」，
 * 分类 / 账户记录当时的名称），之后改名不影响历史行的可读性。创建行则是完整的初始值清单。
 *
 * <p>仅存本机、不参与同步：编辑历史是设备侧辅助信息，同一账单在其他设备上的
 * 修改无法在本机还原，跨设备一致性以账单本身的同步为准。
 *
 * <p>本表出现之前已存在的账单没有日志行，历史页对这类账单显示「暂无编辑记录」。
 */
@Entity(
        tableName = "transaction_edit_log",
        indices = {@Index(value = "transaction_id")})
public class TransactionEditLogEntity {

    /** 操作类型：创建（账单首次入库，含周期账单生成）。 */
    public static final String OP_CREATE = "CREATE";

    /** 操作类型：修改（保存时至少一个字段发生变化）。 */
    public static final String OP_UPDATE = "UPDATE";

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 关联的本地账单 id（transactions.id）。 */
    @ColumnInfo(name = "transaction_id")
    public long transactionId;

    /** {@link #OP_CREATE} 或 {@link #OP_UPDATE}。 */
    @NonNull
    @ColumnInfo(name = "operation")
    public String operation = OP_CREATE;

    /** 变更明细，多行文本；创建行为初始值清单，修改行为「字段：旧值 → 新值」清单。 */
    @NonNull
    @ColumnInfo(name = "detail")
    public String detail = "";

    /** 该次操作发生时间（epoch millis）。 */
    @ColumnInfo(name = "changed_at")
    public long changedAt;
}
