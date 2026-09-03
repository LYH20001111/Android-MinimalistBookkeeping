package com.skyanchor.bookkeeping.domain.recurring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.util.DateUtil;

import org.junit.Test;

import java.util.List;

/**
 * 周期账单日期调度单元测试（V2 开发计划 Phase 8）。
 *
 * <p>覆盖：四种频率的推进、跨月 / 闰年的 day-of-month 夹取、App 关闭期间累积的
 * 多期补生成、结束日期截断与「到期规则停用」、以及确认推进后的幂等（同一天不会重复出期）。
 * 数据库写入与 {@code next_run_date} 推进的编排由仓库层完成，这里验证其依赖的纯函数。
 */
public class RecurringTransactionCalculatorTest {

    private static long day(int year, int month, int dayOfMonth) {
        return DateUtil.dayMillisOf(year, month, dayOfMonth);
    }

    // ------------------------------------------------------------------
    // nextAfter：四种频率
    // ------------------------------------------------------------------

    @Test
    public void daily_advancesByIntervalDays() {
        assertEquals(day(2026, 9, 5), GenerateRecurringTransactionsUseCase.nextAfter(
                day(2026, 9, 4), RecurringTransactionEntity.FREQUENCY_DAILY, 1));
        assertEquals(day(2026, 9, 11), GenerateRecurringTransactionsUseCase.nextAfter(
                day(2026, 9, 4), RecurringTransactionEntity.FREQUENCY_DAILY, 7));
    }

    /** 周期账单的「每周」= 同一 weekday 下周同一日，即 +7 * interval 天。 */
    @Test
    public void weekly_advancesByWeeks() {
        // 2026-09-04 是周五 → 下一期 2026-09-11 仍是周五
        assertEquals(DateUtil.addDays(day(2026, 9, 4), 7),
                GenerateRecurringTransactionsUseCase.nextAfter(
                        day(2026, 9, 4), RecurringTransactionEntity.FREQUENCY_WEEKLY, 1));
        assertEquals(DateUtil.addDays(day(2026, 9, 4), 28),
                GenerateRecurringTransactionsUseCase.nextAfter(
                        day(2026, 9, 4), RecurringTransactionEntity.FREQUENCY_WEEKLY, 4));
    }

    @Test
    public void yearly_advancesByYears() {
        assertEquals(day(2026, 9, 4), GenerateRecurringTransactionsUseCase.nextAfter(
                day(2025, 9, 4), RecurringTransactionEntity.FREQUENCY_YEARLY, 1));
        assertEquals(day(2028, 9, 4), GenerateRecurringTransactionsUseCase.nextAfter(
                day(2026, 9, 4), RecurringTransactionEntity.FREQUENCY_YEARLY, 2));
    }

    /** 间隔非法（0 / 负数）按 1 处理，不允许出现原地踏步的死循环。 */
    @Test
    public void invalidInterval_isTreatedAsOne() {
        assertEquals(day(2026, 9, 5), GenerateRecurringTransactionsUseCase.nextAfter(
                day(2026, 9, 4), RecurringTransactionEntity.FREQUENCY_DAILY, 0));
        assertEquals(day(2026, 10, 4), GenerateRecurringTransactionsUseCase.nextAfter(
                day(2026, 9, 4), RecurringTransactionEntity.FREQUENCY_MONTHLY, -1));
    }

    // ------------------------------------------------------------------
    // 跨月 / 闰年夹取
    // ------------------------------------------------------------------

    /** 1 月 31 日按月推进到 2 月，必须夹到 2 月最后一天（2026 非闰年 → 28 日）。 */
    @Test
    public void monthly_clampsToLastDayOfMonth() {
        assertEquals(day(2026, 2, 28), GenerateRecurringTransactionsUseCase.nextAfter(
                day(2026, 1, 31), RecurringTransactionEntity.FREQUENCY_MONTHLY, 1));
        // 3 月 31 日 + 1 月 → 4 月 30 日
        assertEquals(day(2026, 4, 30), GenerateRecurringTransactionsUseCase.nextAfter(
                day(2026, 3, 31), RecurringTransactionEntity.FREQUENCY_MONTHLY, 1));
        // 普通日期月份平移保持 day-of-month
        assertEquals(day(2026, 10, 1), GenerateRecurringTransactionsUseCase.nextAfter(
                day(2026, 9, 1), RecurringTransactionEntity.FREQUENCY_MONTHLY, 1));
    }

    /** 闰年 2 月 29 日按年推进到非闰年，必须夹到 2 月 28 日。 */
    @Test
    public void yearly_clampsLeapDay() {
        assertEquals(day(2025, 2, 28), GenerateRecurringTransactionsUseCase.nextAfter(
                day(2024, 2, 29), RecurringTransactionEntity.FREQUENCY_YEARLY, 1));
        // 闰年到闰年保持 2 月 29 日
        assertEquals(day(2028, 2, 29), GenerateRecurringTransactionsUseCase.nextAfter(
                day(2024, 2, 29), RecurringTransactionEntity.FREQUENCY_YEARLY, 4));
    }

    // ------------------------------------------------------------------
    // collectDueDates：多期补生成、结束截断、幂等
    // ------------------------------------------------------------------

    /** App 关闭 10 天：每天一条的规则累积 10 期，从 next_run_date 起逐日补齐。 */
    @Test
    public void collectDueDates_catchesUpMultipleOccurrences() {
        List<Long> dates = GenerateRecurringTransactionsUseCase.collectDueDates(
                day(2026, 9, 1), day(2026, 9, 10), 0L,
                RecurringTransactionEntity.FREQUENCY_DAILY, 1);

        assertEquals(10, dates.size());
        assertEquals(Long.valueOf(day(2026, 9, 1)), dates.get(0));
        assertEquals(Long.valueOf(day(2026, 9, 10)), dates.get(dates.size() - 1));

        // 幂等：确认后 next_run_date 推进到 9 月 11 日，同一天内再次收集为空，不重复生成
        long advanced = GenerateRecurringTransactionsUseCase.nextAfter(
                dates.get(dates.size() - 1), RecurringTransactionEntity.FREQUENCY_DAILY, 1);
        assertTrue(GenerateRecurringTransactionsUseCase.collectDueDates(
                advanced, day(2026, 9, 10), 0L,
                RecurringTransactionEntity.FREQUENCY_DAILY, 1).isEmpty());
    }

    /** 未到期（next_run_date 晚于今天）的规则不出期。 */
    @Test
    public void collectDueDates_emptyWhenNotDue() {
        assertTrue(GenerateRecurringTransactionsUseCase.collectDueDates(
                day(2026, 9, 11), day(2026, 9, 10), 0L,
                RecurringTransactionEntity.FREQUENCY_MONTHLY, 1).isEmpty());
    }

    /** 有结束日期的规则：晚于结束日期的期不再生成，推进后越过结束日期即应停用。 */
    @Test
    public void collectDueDates_stopsAtEndDateAndMarksRuleFinished() {
        List<Long> dates = GenerateRecurringTransactionsUseCase.collectDueDates(
                day(2026, 8, 1), day(2026, 12, 10), day(2026, 10, 1),
                RecurringTransactionEntity.FREQUENCY_MONTHLY, 1);

        // 只到 8-1、9-1、10-1 三期，11 月起越过结束日期
        assertEquals(3, dates.size());
        assertEquals(Long.valueOf(day(2026, 10, 1)), dates.get(dates.size() - 1));

        long advanced = GenerateRecurringTransactionsUseCase.nextAfter(
                dates.get(dates.size() - 1), RecurringTransactionEntity.FREQUENCY_MONTHLY, 1);
        assertTrue(GenerateRecurringTransactionsUseCase.isBeyondEndDate(advanced, day(2026, 10, 1)));
        // 无结束日期（0）的规则永远不算越过
        assertTrue(!GenerateRecurringTransactionsUseCase.isBeyondEndDate(advanced, 0L));
    }

    /** 病态输入（超长区间 + 极小间隔）受安全上限保护，不会拖垮确认流程。 */
    @Test
    public void collectDueDates_isBoundedForPathologicalInput() {
        List<Long> dates = GenerateRecurringTransactionsUseCase.collectDueDates(
                day(2000, 1, 1), day(2030, 1, 1), 0L,
                RecurringTransactionEntity.FREQUENCY_DAILY, 1);

        assertEquals(GenerateRecurringTransactionsUseCase.MAX_OCCURRENCES_PER_RULE,
                dates.size());
    }
}
