package com.skyanchor.bookkeeping.domain.recurring;

import androidx.annotation.NonNull;

import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.util.DateUtil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 周期账单生成用例（V2 新增，开发计划 Phase 8）。
 *
 * <p>只负责「日期调度」这一纯计算部分，不碰数据库：
 * <ul>
 *   <li>{@link #nextAfter}：从某个发生日推进到下一期——日按 +interval 天，
 *       周按 +interval 周（同一 weekday），月 / 年按 day-of-month 平移并夹到当月
 *       最后一天（如 1 月 31 日 → 2 月 28 日）；</li>
 *   <li>{@link #collectDueDates}：枚举 {@code next_run_date <= today 且 is_enabled} 的
 *       全部到期 occurrence，覆盖 App 长期未打开累积的多期，带安全上限防病态数据。</li>
 * </ul>
 *
 * <p>生成「待确认列表」后不做任何静默写入：用户确认后由仓库层在单事务内
 * 插入交易（date = occurrence 日）并幂等推进 {@code next_run_date}，到期规则不重复生成；
 * 停用即不再生成。
 */
public final class GenerateRecurringTransactionsUseCase {

    /** 单条规则一次最多补生成的期数，防止极端间隔 / 日期组合拖垮确认流程。 */
    public static final int MAX_OCCURRENCES_PER_RULE = 366;

    private GenerateRecurringTransactionsUseCase() {
    }

    /**
     * 从 {@code anchorDay}（当天 00:00）推进到下一期，返回下一期当天 00:00。
     *
     * @param frequency {@link RecurringTransactionEntity#FREQUENCY_DAILY} 等常量
     * @param interval  间隔，至少为 1（非法时按 1 处理）
     */
    public static long nextAfter(long anchorDay, int frequency, int interval) {
        int safeInterval = Math.max(1, interval);
        switch (frequency) {
            case RecurringTransactionEntity.FREQUENCY_WEEKLY:
                return DateUtil.addDays(anchorDay, 7 * safeInterval);
            case RecurringTransactionEntity.FREQUENCY_MONTHLY:
                return addCalendar(anchorDay, Calendar.MONTH, safeInterval);
            case RecurringTransactionEntity.FREQUENCY_YEARLY:
                return addCalendar(anchorDay, Calendar.YEAR, safeInterval);
            case RecurringTransactionEntity.FREQUENCY_DAILY:
            default:
                return DateUtil.addDays(anchorDay, safeInterval);
        }
    }

    /**
     * 枚举一条规则的全部到期 occurrence 日（升序，不含超出 {@code endDate} 的期）。
     *
     * <p>结束约定：{@code endDate = 0} 表示无结束日期；occurrence 日晚于 endDate 的期
     * 不再生成（规则被视为已结束）。最多返回 {@link #MAX_OCCURRENCES_PER_RULE} 期。
     *
     * @param nextRunDate 当前 next_run_date（下一次应记账日）
     * @param today       今天 00:00（含）
     */
    @NonNull
    public static List<Long> collectDueDates(long nextRunDate, long today, long endDate,
                                             int frequency, int interval) {
        List<Long> dates = new ArrayList<>();
        long cursor = nextRunDate;
        while (cursor <= today
                && (endDate == 0L || cursor <= endDate)
                && dates.size() < MAX_OCCURRENCES_PER_RULE) {
            dates.add(cursor);
            cursor = nextAfter(cursor, frequency, interval);
        }
        return dates;
    }

    /**
     * 规则是否已越过结束日期（{@code endDate != 0} 且下一期晚于它）。
     * 到这种状态的规则应在确认后停用，避免永远停留在「待确认」。
     */
    public static boolean isBeyondEndDate(long nextRunDate, long endDate) {
        return endDate != 0L && nextRunDate > endDate;
    }

    /** 走 Calendar 平移月 / 年：day-of-month 越界时自动夹到当月最后一天（含闰年）。 */
    private static long addCalendar(long dayMillis, int field, int amount) {
        Calendar calendar = DateUtil.calendar(dayMillis);
        calendar.add(field, amount);
        return DateUtil.startOfDay(calendar.getTimeInMillis());
    }
}
