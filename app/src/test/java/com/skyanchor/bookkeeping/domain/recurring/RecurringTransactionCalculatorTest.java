package com.skyanchor.bookkeeping.domain.recurring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.util.DateUtil;

import org.junit.Test;

import java.util.List;

/**
 * 周期账单日期调度单元测试（V2 开发计划 Phase 8，V2.1 Phase 3 重写月 / 年用例）。
 *
 * <p>覆盖：四种频率的推进、App 关闭期间累积的多期补生成、结束日期截断与
 * 「到期规则停用」、确认推进后的幂等；以及 V2.1 锚点日核心契约——
 * 月末 / 年末周期每次从原始锚点重推，1 月 31 → 2 月 28 → 3 月 31，不发生日期漂移。
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
        // 日频不用锚点，传 0 也合法
        assertEquals(day(2026, 9, 5), GenerateRecurringTransactionsUseCase.nextAfter(
                0, day(2026, 9, 4), RecurringTransactionEntity.FREQUENCY_DAILY, 1));
        assertEquals(day(2026, 9, 11), GenerateRecurringTransactionsUseCase.nextAfter(
                0, day(2026, 9, 4), RecurringTransactionEntity.FREQUENCY_DAILY, 7));
    }

    /** 周期账单的「每周」= 同一 weekday 下周同一日，即 +7 * interval 天。 */
    @Test
    public void weekly_advancesByWeeks() {
        // 2026-09-04 是周五 → 下一期 2026-09-11 仍是周五
        assertEquals(DateUtil.addDays(day(2026, 9, 4), 7),
                GenerateRecurringTransactionsUseCase.nextAfter(
                        4, day(2026, 9, 4), RecurringTransactionEntity.FREQUENCY_WEEKLY, 1));
        assertEquals(DateUtil.addDays(day(2026, 9, 4), 28),
                GenerateRecurringTransactionsUseCase.nextAfter(
                        4, day(2026, 9, 4), RecurringTransactionEntity.FREQUENCY_WEEKLY, 4));
    }

    @Test
    public void yearly_advancesByYears() {
        assertEquals(day(2026, 9, 4), GenerateRecurringTransactionsUseCase.nextAfter(
                4, day(2025, 9, 4), RecurringTransactionEntity.FREQUENCY_YEARLY, 1));
        assertEquals(day(2028, 9, 4), GenerateRecurringTransactionsUseCase.nextAfter(
                4, day(2026, 9, 4), RecurringTransactionEntity.FREQUENCY_YEARLY, 2));
    }

    /** 间隔非法（0 / 负数）按 1 处理，不允许出现原地踏步的死循环。 */
    @Test
    public void invalidInterval_isTreatedAsOne() {
        assertEquals(day(2026, 9, 5), GenerateRecurringTransactionsUseCase.nextAfter(
                4, day(2026, 9, 4), RecurringTransactionEntity.FREQUENCY_DAILY, 0));
        assertEquals(day(2026, 10, 4), GenerateRecurringTransactionsUseCase.nextAfter(
                4, day(2026, 9, 4), RecurringTransactionEntity.FREQUENCY_MONTHLY, -1));
    }

    // ------------------------------------------------------------------
    // V2.1 锚点重推：月末 / 年末不漂移（基线 28.3）
    // ------------------------------------------------------------------

    /** 锚点 31：1 月 31 → 2 月 28 → 3 月 31。被夹取的 2 月 28 不得变成新锚点。 */
    @Test
    public void monthly_anchor31_chainDoesNotDrift() {
        long jan31 = day(2026, 1, 31);
        long feb28 = GenerateRecurringTransactionsUseCase.nextAfter(
                31, jan31, RecurringTransactionEntity.FREQUENCY_MONTHLY, 1);
        assertEquals(day(2026, 2, 28), feb28);
        assertEquals(day(2026, 3, 31), GenerateRecurringTransactionsUseCase.nextAfter(
                31, feb28, RecurringTransactionEntity.FREQUENCY_MONTHLY, 1));
    }

    /** 锚点 30：1 月 30 → 2 月 28 → 3 月 30。 */
    @Test
    public void monthly_anchor30_restoresFromFebruary() {
        long feb28 = GenerateRecurringTransactionsUseCase.nextAfter(
                30, day(2026, 1, 30), RecurringTransactionEntity.FREQUENCY_MONTHLY, 1);
        assertEquals(day(2026, 2, 28), feb28);
        assertEquals(day(2026, 3, 30), GenerateRecurringTransactionsUseCase.nextAfter(
                30, feb28, RecurringTransactionEntity.FREQUENCY_MONTHLY, 1));
    }

    /** 闰年链路：锚点 29，2024-2-29 → 2025-2-28（平年）→ 2026-2-28；到闰年回到 29。 */
    @Test
    public void monthlyAndYearly_anchor29_leapRules() {
        long feb2024 = day(2024, 2, 29);
        assertEquals(day(2025, 2, 28), GenerateRecurringTransactionsUseCase.nextAfter(
                29, feb2024, RecurringTransactionEntity.FREQUENCY_MONTHLY, 12));
        // 年周期：闰年 → 非闰年夹到 28；非闰年 → 闰年恢复 29
        assertEquals(day(2025, 2, 28), GenerateRecurringTransactionsUseCase.nextAfter(
                29, feb2024, RecurringTransactionEntity.FREQUENCY_YEARLY, 1));
        assertEquals(day(2028, 2, 29), GenerateRecurringTransactionsUseCase.nextAfter(
                29, day(2025, 2, 28), RecurringTransactionEntity.FREQUENCY_YEARLY, 3));
    }

    /** interval > 1 的月周期同样按锚点重推：每 3 个月的 31 日，中间月不改变锚点。 */
    @Test
    public void monthly_interval3_anchorStays() {
        long start = day(2026, 1, 31);
        long next = GenerateRecurringTransactionsUseCase.nextAfter(
                31, start, RecurringTransactionEntity.FREQUENCY_MONTHLY, 3);
        assertEquals(day(2026, 4, 30), next);
        assertEquals(day(2026, 7, 31), GenerateRecurringTransactionsUseCase.nextAfter(
                31, next, RecurringTransactionEntity.FREQUENCY_MONTHLY, 3));
    }

    /** 非法锚点（0，如旧数据未回填）回落为发生日的日，推进仍然合法。 */
    @Test
    public void monthly_invalidAnchor_fallsBackToFromDay() {
        assertEquals(day(2026, 10, 15), GenerateRecurringTransactionsUseCase.nextAfter(
                0, day(2026, 9, 15), RecurringTransactionEntity.FREQUENCY_MONTHLY, 1));
    }

    // ------------------------------------------------------------------
    // 普通夹取（日期不越界时行为与 V2 一致）
    // ------------------------------------------------------------------

    @Test
    public void monthly_clampsToLastDayOfMonth() {
        // 3 月 31 日 + 1 月 → 4 月 30 日
        assertEquals(day(2026, 4, 30), GenerateRecurringTransactionsUseCase.nextAfter(
                31, day(2026, 3, 31), RecurringTransactionEntity.FREQUENCY_MONTHLY, 1));
        // 普通日期月份平移保持 day-of-month
        assertEquals(day(2026, 10, 1), GenerateRecurringTransactionsUseCase.nextAfter(
                1, day(2026, 9, 1), RecurringTransactionEntity.FREQUENCY_MONTHLY, 1));
    }

    // ------------------------------------------------------------------
    // collectDueDates：多期补生成、结束截断、幂等
    // ------------------------------------------------------------------

    /** App 关闭 10 天：每天一条的规则累积 10 期，从 next_run_date 起逐日补齐。 */
    @Test
    public void collectDueDates_catchesUpMultipleOccurrences() {
        List<Long> dates = GenerateRecurringTransactionsUseCase.collectDueDates(
                1, day(2026, 9, 1), day(2026, 9, 10), 0L,
                RecurringTransactionEntity.FREQUENCY_DAILY, 1);

        assertEquals(10, dates.size());
        assertEquals(Long.valueOf(day(2026, 9, 1)), dates.get(0));
        assertEquals(Long.valueOf(day(2026, 9, 10)), dates.get(dates.size() - 1));

        // 幂等：确认后 next_run_date 推进到 9 月 11 日，同一天内再次收集为空，不重复生成
        long advanced = GenerateRecurringTransactionsUseCase.nextAfter(
                1, dates.get(dates.size() - 1), RecurringTransactionEntity.FREQUENCY_DAILY, 1);
        assertTrue(GenerateRecurringTransactionsUseCase.collectDueDates(
                1, advanced, day(2026, 9, 10), 0L,
                RecurringTransactionEntity.FREQUENCY_DAILY, 1).isEmpty());
    }

    /** 锚点 31 的月规则欠账 3 期：1/31 → 2/28 → 3/31，2 月被夹取但锚点不变。 */
    @Test
    public void collectDueDates_monthlyAnchorNoDrift() {
        List<Long> dates = GenerateRecurringTransactionsUseCase.collectDueDates(
                31, day(2026, 1, 31), day(2026, 4, 10), 0L,
                RecurringTransactionEntity.FREQUENCY_MONTHLY, 1);

        assertEquals(3, dates.size());
        assertEquals(Long.valueOf(day(2026, 1, 31)), dates.get(0));
        assertEquals(Long.valueOf(day(2026, 2, 28)), dates.get(1));
        assertEquals(Long.valueOf(day(2026, 3, 31)), dates.get(2));
    }

    /** 未到期（next_run_date 晚于今天）的规则不出期。 */
    @Test
    public void collectDueDates_emptyWhenNotDue() {
        assertTrue(GenerateRecurringTransactionsUseCase.collectDueDates(
                15, day(2026, 9, 11), day(2026, 9, 10), 0L,
                RecurringTransactionEntity.FREQUENCY_MONTHLY, 1).isEmpty());
    }

    /** 有结束日期的规则：晚于结束日期的期不再生成，推进后越过结束日期即应停用。 */
    @Test
    public void collectDueDates_stopsAtEndDateAndMarksRuleFinished() {
        List<Long> dates = GenerateRecurringTransactionsUseCase.collectDueDates(
                1, day(2026, 8, 1), day(2026, 12, 10), day(2026, 10, 1),
                RecurringTransactionEntity.FREQUENCY_MONTHLY, 1);

        // 只到 8-1、9-1、10-1 三期，11 月起越过结束日期
        assertEquals(3, dates.size());
        assertEquals(Long.valueOf(day(2026, 10, 1)), dates.get(dates.size() - 1));

        long advanced = GenerateRecurringTransactionsUseCase.nextAfter(
                1, dates.get(dates.size() - 1), RecurringTransactionEntity.FREQUENCY_MONTHLY, 1);
        assertTrue(GenerateRecurringTransactionsUseCase.isBeyondEndDate(advanced, day(2026, 10, 1)));
        // 无结束日期（0）的规则永远不算越过
        assertTrue(!GenerateRecurringTransactionsUseCase.isBeyondEndDate(advanced, 0L));
    }

    /** 病态输入（超长区间 + 极小间隔）受安全上限保护，不会拖垮确认流程。 */
    @Test
    public void collectDueDates_isBoundedForPathologicalInput() {
        List<Long> dates = GenerateRecurringTransactionsUseCase.collectDueDates(
                1, day(2000, 1, 1), day(2030, 1, 1), 0L,
                RecurringTransactionEntity.FREQUENCY_DAILY, 1);

        assertEquals(GenerateRecurringTransactionsUseCase.MAX_OCCURRENCES_PER_RULE,
                dates.size());
    }
}
