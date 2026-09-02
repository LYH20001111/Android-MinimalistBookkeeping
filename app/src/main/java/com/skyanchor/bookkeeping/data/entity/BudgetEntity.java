package com.skyanchor.bookkeeping.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 月预算表。V1 只做「月预算」，按 year + month 唯一（V1 基线第 8、12 章）。
 */
@Entity(
        tableName = "budget",
        indices = {@Index(value = {"year", "month"}, unique = true)})
public class BudgetEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "year")
    public int year;

    /** 月份，取值 1-12。 */
    @ColumnInfo(name = "month")
    public int month;

    /** 预算金额，单位：分。 */
    @ColumnInfo(name = "amount")
    public long amount;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
