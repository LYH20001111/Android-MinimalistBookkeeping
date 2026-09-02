package com.skyanchor.bookkeeping.data.model;

/**
 * 一个周期内的核心数字：收入 / 支出 / 结余（V1 基线第 7.1 节第一层）。
 *
 * <p>金额单位统一为「分」。结余 = 收入 - 支出。
 */
public final class PeriodSummary {

    public static final PeriodSummary EMPTY = new PeriodSummary(0L, 0L, 0);

    /** 收入合计，单位：分。 */
    public final long income;

    /** 支出合计，单位：分。 */
    public final long expense;

    /** 账单笔数。 */
    public final int count;

    public PeriodSummary(long income, long expense, int count) {
        this.income = income;
        this.expense = expense;
        this.count = count;
    }

    /** 结余 = 收入 - 支出，可为负数。 */
    public long balance() {
        return income - expense;
    }

    public boolean isEmpty() {
        return count == 0;
    }
}
