package com.skyanchor.bookkeeping.data.model;

/**
 * 待确认的周期账单（V2 新增，开发计划 Phase 8）。
 *
 * <p>一条启用的规则在 {@code next_run_date <= today} 时进入待确认状态；App 关闭期间
 * 累积的多期会用 {@link #occurrenceCount} 表达。生成永远需要用户确认，不做后台静默写入。
 */
public final class RecurringDue {

    /** 规则 id（recurring_transaction.id）。 */
    public final long ruleId;

    /** 规则名，如「房租」。 */
    public final String name;

    /** 1=支出，2=收入（周期账单不含转账）。 */
    public final int type;

    /** 单期金额，单位：分。 */
    public final long amount;

    /** 最近的到期日（原 next_run_date），当天 00:00 的 epoch millis。 */
    public final long dueDate;

    /** 累积到期期数（含 App 关闭期间的补生成），恒 &gt;= 1。 */
    public final int occurrenceCount;

    public RecurringDue(long ruleId, String name, int type, long amount, long dueDate,
                        int occurrenceCount) {
        this.ruleId = ruleId;
        this.name = name;
        this.type = type;
        this.amount = amount;
        this.dueDate = dueDate;
        this.occurrenceCount = occurrenceCount;
    }
}
