package com.skyanchor.bookkeeping.data.model;

/**
 * Room 投影：某一天的收支摘要，用于日历选择器中显示每日流水。
 *
 * <p>金额单位统一为「分」，禁止使用 double/float（V1 基线第 11、12 章）。
 * {@link #day} 是当天 00:00 的 epoch millis，与 {@code TransactionEntity.date} 口径一致。
 */
public class DailySummary {

    /** 当天 00:00 的 epoch millis。 */
    public long day;

    /** 当日支出合计，单位：分。 */
    public long expense;

    /** 当日收入合计，单位：分。 */
    public long income;

    /** 当日账单笔数。 */
    public int transactionCount;
}
