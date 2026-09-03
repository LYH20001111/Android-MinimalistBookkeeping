package com.skyanchor.bookkeeping.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 月预算表。
 *
 * <p>V1 只做「月总预算」，按 year + month 唯一。V2 扩展为「总预算 + 分类预算」：
 * 新增 {@link #categoryId}，唯一索引改为 (year, month, category_id)。
 *
 * <p>{@code categoryId = 0} 是总预算哨兵。之所以用 0 而非 NULL：SQLite 唯一索引把 NULL
 * 视为互不相等，用哨兵 0 才能保证 {@code (year, month, category_id)} 唯一、每月总预算仅一条。
 * budget 表无外键，categoryId 恒 &gt;= 1 时为分类预算。
 */
@Entity(
        tableName = "budget",
        indices = {@Index(value = {"year", "month", "category_id"}, unique = true)})
public class BudgetEntity {

    /** 总预算哨兵：category_id = 0 表示该月总预算。 */
    public static final int CATEGORY_TOTAL = 0;

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "year")
    public int year;

    /** 月份，取值 1-12。 */
    @ColumnInfo(name = "month")
    public int month;

    /** 预算归属分类 id；0 = 总预算，>= 1 = 分类预算。 */
    @ColumnInfo(name = "category_id", defaultValue = "0")
    public int categoryId = CATEGORY_TOTAL;

    /** 预算金额，单位：分。 */
    @ColumnInfo(name = "amount")
    public long amount;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
