package com.skyanchor.bookkeeping.data.model;

/**
 * 趋势图上的一个数据点。周/月视图按天，年视图按月。
 */
public final class TrendPoint {

    /** X 轴标签，例如「一」「15」「6月」。 */
    public final String label;

    /** 该点的支出金额，单位：分。 */
    public final long value;

    /**
     * 点的键：按天时为当天 00:00 的 epoch millis，按月时为月份（1-12）。
     * 用于把账单归入正确的点。
     */
    public final long key;

    public TrendPoint(String label, long value, long key) {
        this.label = label;
        this.value = value;
        this.key = key;
    }

    public TrendPoint withValue(long newValue) {
        return new TrendPoint(label, newValue, key);
    }

    /** 替换 X 轴标签，用于把中性的数字标签本地化（例如周视图换成「一」「二」）。 */
    public TrendPoint withLabel(String newLabel) {
        return new TrendPoint(newLabel, value, key);
    }
}
