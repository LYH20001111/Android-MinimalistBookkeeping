package com.skyanchor.bookkeeping.data.model;

import com.skyanchor.bookkeeping.util.DateUtil;

/**
 * 一个统计周期的日期区间。
 *
 * <p>{@link #start} 与 {@link #end} 都是「当天 00:00」的 epoch millis，与
 * {@code TransactionEntity.date} 的存储口径一致，因此可以直接用
 * {@code BETWEEN start AND end} 做闭区间查询。
 */
public final class DateRange {

    /** 周期类型。 */
    public final PeriodType type;

    /** 用于重建该周期的任意一天（毫秒），周期平移时以此为锚点。 */
    public final long anchor;

    /** 周期首日 00:00 的 epoch millis。 */
    public final long start;

    /** 周期末日 00:00 的 epoch millis。 */
    public final long end;

    /** 周期所属年份。 */
    public final int year;

    /** 周期所属月份，取值 1-12；仅 {@link PeriodType#MONTH} 有意义。 */
    public final int month;

    public DateRange(PeriodType type, long anchor, long start, long end, int year, int month) {
        this.type = type;
        this.anchor = anchor;
        this.start = start;
        this.end = end;
        this.year = year;
        this.month = month;
    }

    /** 上一个相同周期：周对周、月对月、年对年。 */
    public DateRange previous() {
        return DateUtil.shift(this, -1);
    }

    /** 下一个相同周期。 */
    public DateRange next() {
        return DateUtil.shift(this, 1);
    }

    /** 周期天数，闭区间。 */
    public int dayCount() {
        return DateUtil.dayCountInclusive(start, end);
    }

    /** 该周期是否包含今天，用于禁用「下一周期」按钮。 */
    public boolean containsToday() {
        long today = DateUtil.startOfDay(System.currentTimeMillis());
        return today >= start && today <= end;
    }

    /** 覆盖当前周期与上一周期的查询起点，用于一次性取出环比所需数据。 */
    public long compareStart() {
        return previous().start;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DateRange)) {
            return false;
        }
        DateRange other = (DateRange) o;
        return start == other.start && end == other.end && type == other.type;
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + (int) (start ^ (start >>> 32));
        result = 31 * result + (int) (end ^ (end >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "DateRange{" + type + ", " + start + ".." + end + "}";
    }
}
