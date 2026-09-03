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
 */
@Entity(
        tableName = "transactions",
        foreignKeys = @ForeignKey(
                entity = CategoryEntity.class,
                parentColumns = "id",
                childColumns = "category_id",
                onDelete = ForeignKey.RESTRICT),
        indices = {
                @Index(value = "category_id"),
                @Index(value = "date")
        })
public class TransactionEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 1=支出，2=收入。 */
    @ColumnInfo(name = "type")
    public int type = CategoryEntity.TYPE_EXPENSE;

    /** 金额，单位：分。 */
    @ColumnInfo(name = "amount")
    public long amount;

    @ColumnInfo(name = "category_id")
    public long categoryId;

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
}
