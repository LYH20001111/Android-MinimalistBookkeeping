package com.skyanchor.bookkeeping.data.model;

/**
 * Room 投影：某一天的账单笔数，用于周期选择器在 Java 侧聚合为周/月/年选项。
 *
 * <p>{@link #day} 是当天 00:00 的 epoch millis，与 {@code TransactionEntity.date} 口径一致。
 */
public class DayCount {

    /** 当天 00:00 的 epoch millis。 */
    public long day;

    /** 当日账单笔数。 */
    public int transactionCount;
}
