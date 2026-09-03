package com.skyanchor.bookkeeping.data.model;

/**
 * 周期选择器的一个选项（纯 UI 模型，不入库）。
 *
 * <p>由 {@code ChartViewModel} 从 {@link DayCount} 聚合生成，供
 * {@code PeriodPickerDialog} 渲染周/月/年卡片列表。
 *
 * <p>周期的唯一标识是 {@link #start}（周一 00:00 / 月首 00:00 / 年首 00:00 的 epoch millis），
 * {@link #title} 只用于 UI 定位，不作为数据库主键（V1.1 基线第 34 章）。
 */
public class PeriodOption {

    /** 周期类型。 */
    public PeriodType type;

    /** 周期首日 00:00 的 epoch millis，也是该周期的唯一标识。 */
    public long start;

    /** 周期末日 00:00 的 epoch millis。 */
    public long end;

    /** 该周期内的账单笔数；无流水时为 0（周期选择器允许显示空周期）。 */
    public int transactionCount;

    /** 主标题：「Week 5」/「2026年8月」/「2026年」。 */
    public String title;

    /** 副标题日期范围：「08.30-09.05」/「08.01-08.31」/「01.01-12.31」。 */
    public String subtitle;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeriodOption)) return false;
        PeriodOption other = (PeriodOption) o;
        return start == other.start && type == other.type;
    }

    @Override
    public int hashCode() {
        return 31 * (type != null ? type.hashCode() : 0) + (int) (start ^ (start >>> 32));
    }
}
