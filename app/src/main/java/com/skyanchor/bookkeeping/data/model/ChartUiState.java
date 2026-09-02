package com.skyanchor.bookkeeping.data.model;

import java.util.Collections;
import java.util.List;

/**
 * 图表页的一次性快照，按文档 7.1 的四层信息优先级组织：
 * 核心数字 → 趋势 → 分类 → 周期对比。
 *
 * <p>本类只产出与语言无关的数据，文案由界面层结合 string 资源拼装。
 */
public final class ChartUiState {

    /** 无法计算环比（上一周期支出为 0）。 */
    public static final int CHANGE_NONE = 0;
    /** 支出持平。 */
    public static final int CHANGE_FLAT = 1;
    /** 支出上升，Danger 语义。 */
    public static final int CHANGE_UP = 2;
    /** 支出下降，Success 语义。 */
    public static final int CHANGE_DOWN = 3;

    /** 当前周期区间。 */
    public final DateRange range;

    /** 周期标题，例如「2024年5月」。 */
    public final String label;

    /** 本周期核心数字。 */
    public final PeriodSummary summary;

    /** 上一相同周期的核心数字，用于环比。 */
    public final PeriodSummary previousSummary;

    /** 支出环比变化的绝对值，千分比整数：125 表示 12.5%。 */
    public final int changeAbsX10;

    /** {@link #CHANGE_NONE} / {@link #CHANGE_FLAT} / {@link #CHANGE_UP} / {@link #CHANGE_DOWN}。 */
    public final int changeDirection;

    /** 趋势数据：周/月按天，年按月。 */
    public final List<TrendPoint> trend;

    /** 消费分类（支出），按金额降序。 */
    public final List<CategoryStat> categoryStats;

    /** 月预算状态，仅月视图有效，其余周期为 {@link BudgetState#NOT_SET}。 */
    public final BudgetState budgetState;

    public ChartUiState(DateRange range, String label, PeriodSummary summary,
                        PeriodSummary previousSummary, int changeAbsX10, int changeDirection,
                        List<TrendPoint> trend, List<CategoryStat> categoryStats,
                        BudgetState budgetState) {
        this.range = range;
        this.label = label;
        this.summary = summary;
        this.previousSummary = previousSummary;
        this.changeAbsX10 = changeAbsX10;
        this.changeDirection = changeDirection;
        this.trend = trend == null ? Collections.<TrendPoint>emptyList() : trend;
        this.categoryStats =
                categoryStats == null ? Collections.<CategoryStat>emptyList() : categoryStats;
        this.budgetState = budgetState == null ? BudgetState.NOT_SET : budgetState;
    }

    /** 本周期没有任何账单时展示引导，不允许图表出现「空白块」。 */
    public boolean isEmpty() {
        return summary.isEmpty();
    }

    /** 环比百分比文案，例如「12.5%」；整数时省略小数位。 */
    public String changeValueText() {
        int whole = changeAbsX10 / 10;
        int fraction = changeAbsX10 % 10;
        if (fraction == 0) {
            return whole + "%";
        }
        return whole + "." + fraction + "%";
    }
}
