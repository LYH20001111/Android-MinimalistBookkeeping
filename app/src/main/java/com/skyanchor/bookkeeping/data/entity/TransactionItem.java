package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;

import java.util.Objects;

/**
 * 交易 + 分类的联表投影。
 *
 * <p>使用单次 JOIN 查询而不是 {@code @Relation}，避免列表渲染时的 N+1 查询；
 * 记录页、图表页、预算页共用这一个模型，保证「列表、概览、图表、预算来自同一数据源」。
 */
public class TransactionItem {

    @ColumnInfo(name = "id")
    public long id;

    @ColumnInfo(name = "type")
    public int type;

    /** 金额，单位：分。 */
    @ColumnInfo(name = "amount")
    public long amount;

    /** 业务日期，当天 00:00 的 epoch millis。 */
    @ColumnInfo(name = "date")
    public long date;

    @NonNull
    @ColumnInfo(name = "time")
    public String time = "";

    @Nullable
    @ColumnInfo(name = "note")
    public String note;

    @ColumnInfo(name = "categoryId")
    public long categoryId;

    @Nullable
    @ColumnInfo(name = "categoryName")
    public String categoryName;

    @Nullable
    @ColumnInfo(name = "categoryIcon")
    public String categoryIcon;

    public boolean isExpense() {
        return type == CategoryEntity.TYPE_EXPENSE;
    }

    public boolean isIncome() {
        return type == CategoryEntity.TYPE_INCOME;
    }

    @NonNull
    public String displayName() {
        return categoryName == null ? "" : categoryName;
    }

    @NonNull
    public String displayIcon() {
        return categoryIcon == null ? "" : categoryIcon;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionItem)) {
            return false;
        }
        TransactionItem other = (TransactionItem) o;
        return id == other.id
                && type == other.type
                && amount == other.amount
                && date == other.date
                && categoryId == other.categoryId
                && Objects.equals(time, other.time)
                && Objects.equals(note, other.note)
                && Objects.equals(categoryName, other.categoryName)
                && Objects.equals(categoryIcon, other.categoryIcon);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, amount, date, time, note, categoryId, categoryName,
                categoryIcon);
    }
}
