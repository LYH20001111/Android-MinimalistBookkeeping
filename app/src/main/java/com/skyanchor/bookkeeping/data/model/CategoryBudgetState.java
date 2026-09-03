package com.skyanchor.bookkeeping.data.model;

/**
 * 分类预算行（V2 新增，开发计划 Phase 6）：某个支出分类的预算额、本月已用与预算状态。
 *
 * <p>预算状态沿用 {@link BudgetState} 的阈值与语义色（&lt;80% 正常、80%~100% 接近、
 * &gt;100% 超支），与总预算卡片完全同规则；分类预算不反向限制记账，仅提醒 / 分析。
 *
 * <p>{@link #used} 独立成字段的原因：{@link BudgetState#NOT_SET} 的 used 恒为 0，
 * 而未设置预算的分类在预算设置页仍要如实展示本月已消费金额。
 */
public final class CategoryBudgetState {

    /** 分类 id；恒 &gt;= 1，总预算（哨兵 0）不会出现在分类预算行里。 */
    public final long categoryId;

    /** 分类名。 */
    public final String name;

    /** 分类图标（emoji）。 */
    public final String icon;

    /** 预算金额，单位：分；0 表示该分类本月未设置预算。 */
    public final long budgetAmount;

    /** 该分类本月支出，单位：分；未设置预算时也如实展示。 */
    public final long used;

    /** 预算状态；{@code budgetAmount <= 0} 时为 {@link BudgetState#NOT_SET}。 */
    public final BudgetState state;

    public CategoryBudgetState(long categoryId, String name, String icon,
                               long budgetAmount, long used, BudgetState state) {
        this.categoryId = categoryId;
        this.name = name;
        this.icon = icon;
        this.budgetAmount = budgetAmount;
        this.used = used;
        this.state = state == null ? BudgetState.NOT_SET : state;
    }

    /** 是否已设置该分类的预算。 */
    public boolean hasBudget() {
        return budgetAmount > 0L;
    }
}
