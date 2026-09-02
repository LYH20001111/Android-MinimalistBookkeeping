package com.skyanchor.bookkeeping.data.model;

/**
 * 统计周期类型（V1 基线第 7.2 节）。
 */
public enum PeriodType {
    /** 周：趋势按天，对比本周 vs 上周。 */
    WEEK,
    /** 月：趋势按天，对比本月 vs 上月，附带预算。 */
    MONTH,
    /** 年：趋势按月，对比本年 vs 上年。 */
    YEAR
}
