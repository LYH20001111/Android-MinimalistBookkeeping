package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;

import java.util.Objects;

/**
 * 交易 + 分类 + 账户的联表投影。
 *
 * <p>使用单次 JOIN 查询而不是 {@code @Relation}，避免列表渲染时的 N+1 查询；
 * 记录页、图表页、预算页、搜索页共用这一个模型，保证「列表、概览、图表、预算来自同一数据源」。
 *
 * <p>V2：账户以 LEFT JOIN 关联，历史账单 {@code accountId} 为 null；转账（type=3）
 * {@code categoryId} 经 COALESCE 归零、{@code categoryName} 为 null，展示时走转账分支。
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

    /** 分类 id；转账经 COALESCE 归 0。 */
    @ColumnInfo(name = "categoryId")
    public long categoryId;

    @Nullable
    @ColumnInfo(name = "categoryName")
    public String categoryName;

    @Nullable
    @ColumnInfo(name = "categoryIcon")
    public String categoryIcon;

    /** 账户 id：支出=付款、收入=收款、转账=转出；历史账单为 null。 */
    @Nullable
    @ColumnInfo(name = "accountId")
    public Long accountId;

    @Nullable
    @ColumnInfo(name = "accountName")
    public String accountName;

    /** 转入账户 id，仅转账非空。 */
    @Nullable
    @ColumnInfo(name = "transferAccountId")
    public Long transferAccountId;

    @Nullable
    @ColumnInfo(name = "transferAccountName")
    public String transferAccountName;

    public boolean isExpense() {
        return type == CategoryEntity.TYPE_EXPENSE;
    }

    public boolean isIncome() {
        return type == CategoryEntity.TYPE_INCOME;
    }

    /** 是否为转账：既不计收入也不计支出。 */
    public boolean isTransfer() {
        return type == CategoryEntity.TYPE_TRANSFER;
    }

    @NonNull
    public String displayName() {
        return categoryName == null ? "" : categoryName;
    }

    @NonNull
    public String displayIcon() {
        return categoryIcon == null ? "" : categoryIcon;
    }

    @NonNull
    public String displayAccountName() {
        return accountName == null ? "" : accountName;
    }

    @NonNull
    public String displayTransferAccountName() {
        return transferAccountName == null ? "" : transferAccountName;
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
                && Objects.equals(categoryIcon, other.categoryIcon)
                && Objects.equals(accountId, other.accountId)
                && Objects.equals(accountName, other.accountName)
                && Objects.equals(transferAccountId, other.transferAccountId)
                && Objects.equals(transferAccountName, other.transferAccountName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, amount, date, time, note, categoryId, categoryName,
                categoryIcon, accountId, accountName, transferAccountId, transferAccountName);
    }
}
