package com.skyanchor.bookkeeping.data.model;

/**
 * 月预算状态（V1 基线第 8 章）。
 *
 * <pre>
 * 剩余预算 = 月预算 - 本月支出
 * 预算使用率 = 本月支出 / 月预算
 * </pre>
 */
public final class BudgetState {

    /** 使用率 &lt; 80%，保持主色。 */
    public static final int STATUS_NORMAL = 0;
    /** 使用率 80%～100%，Warning 语义。 */
    public static final int STATUS_WARNING = 1;
    /** 使用率 &gt; 100%，Danger 语义并显示超出金额。 */
    public static final int STATUS_OVER = 2;

    /** Warning 阈值，千分比。 */
    private static final int WARNING_THRESHOLD_X10 = 800;
    /** 超支阈值，千分比。 */
    private static final int OVER_THRESHOLD_X10 = 1000;

    /** 未设置预算时的空状态。 */
    public static final BudgetState NOT_SET =
            new BudgetState(false, 0L, 0L, 0L, 0, 0L, STATUS_NORMAL);

    /** 是否已设置本月预算。 */
    public final boolean hasBudget;

    /** 月预算，单位：分。 */
    public final long budgetAmount;

    /** 本月支出，单位：分。 */
    public final long used;

    /** 剩余预算，单位：分，超支时为负数。 */
    public final long remaining;

    /** 使用率，千分比整数，可大于 1000。 */
    public final int percentX10;

    /** 超出金额，单位：分；未超支时为 0。 */
    public final long overAmount;

    /** {@link #STATUS_NORMAL} / {@link #STATUS_WARNING} / {@link #STATUS_OVER}。 */
    public final int status;

    public BudgetState(boolean hasBudget, long budgetAmount, long used, long remaining,
                       int percentX10, long overAmount, int status) {
        this.hasBudget = hasBudget;
        this.budgetAmount = budgetAmount;
        this.used = used;
        this.remaining = remaining;
        this.percentX10 = percentX10;
        this.overAmount = overAmount;
        this.status = status;
    }

    /**
     * 根据月预算与本月支出计算预算状态。
     *
     * @param budgetAmount 月预算（分），小于等于 0 视为未设置
     * @param used         本月支出（分）
     */
    public static BudgetState of(long budgetAmount, long used) {
        if (budgetAmount <= 0L) {
            return NOT_SET;
        }
        long safeUsed = Math.max(0L, used);
        long remaining = budgetAmount - safeUsed;
        int percentX10 = (int) Math.min(Integer.MAX_VALUE, safeUsed * 1000L / budgetAmount);
        long overAmount = remaining < 0L ? -remaining : 0L;
        int status;
        if (percentX10 > OVER_THRESHOLD_X10) {
            status = STATUS_OVER;
        } else if (percentX10 >= WARNING_THRESHOLD_X10) {
            status = STATUS_WARNING;
        } else {
            status = STATUS_NORMAL;
        }
        return new BudgetState(true, budgetAmount, safeUsed, remaining, percentX10, overAmount,
                status);
    }

    /** 格式化为「73.6%」，整数百分比时省略小数位。 */
    public String percentText() {
        int whole = percentX10 / 10;
        int fraction = percentX10 % 10;
        if (fraction == 0) {
            return whole + "%";
        }
        return whole + "." + fraction + "%";
    }

    /** 进度条比例，超支时封顶为 1。仅用于绘制宽度，不参与金额计算。 */
    public float progressRatio() {
        if (!hasBudget) {
            return 0f;
        }
        return Math.min(1f, percentX10 / 1000f);
    }
}
