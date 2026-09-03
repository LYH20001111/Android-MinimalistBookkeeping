package com.skyanchor.bookkeeping.data.model;

import androidx.room.ColumnInfo;

import java.util.Objects;

/**
 * 账户余额联表投影（V2 新增）。
 *
 * <p>由 {@code AccountDao.observeAccountBalances()} 用相关子查询从交易实时重算：
 * {@code balance = initial_balance + 收入 - 支出 + 转入 - 转出}。
 * 以 LiveData 暴露，账单或账户变化后账户页 / 图表页「账户资金」卡片自动刷新。
 * 这里重算出的 {@link #balance} 是唯一真值，{@code account.balance} 缓存列与之对齐。
 */
public class AccountBalance {

    @ColumnInfo(name = "id")
    public long id;

    @ColumnInfo(name = "name")
    public String name;

    @ColumnInfo(name = "type")
    public int type;

    @ColumnInfo(name = "is_credit")
    public boolean isCredit;

    @ColumnInfo(name = "sort_order")
    public int sortOrder;

    @ColumnInfo(name = "is_archived")
    public boolean isArchived;

    @ColumnInfo(name = "initial_balance")
    public long initialBalance;

    /** 从交易重算出的当前余额（分），可正可负。 */
    @ColumnInfo(name = "balance")
    public long balance;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AccountBalance)) {
            return false;
        }
        AccountBalance other = (AccountBalance) o;
        return id == other.id
                && type == other.type
                && isCredit == other.isCredit
                && sortOrder == other.sortOrder
                && isArchived == other.isArchived
                && initialBalance == other.initialBalance
                && balance == other.balance
                && Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, type, isCredit, sortOrder, isArchived, initialBalance,
                balance);
    }
}
