package com.skyanchor.bookkeeping.domain.recurring;

import androidx.annotation.NonNull;

import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.util.DateUtil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 周期账单生成用例（V2 新增，V2.1 Phase 3 重写月 / 年推进逻辑）。
 *
 * <p>只负责「日期调度」这一纯计算部分，不碰数据库：
 * <ul>
 *   <li>{@link #nextAfter}：从某个发生日推进到下一期——日按 +interval 天，
 *       周按 +interval 周（同一 weekday）；月 / 年自 V2.1 起按「原始锚点日」重推：
 *       目标月 = 当前发生日所在月 + interval，日 = min(anchorDay, 当月最大天数)。
 *       例如锚点 31：1 月 31 → 2 月 28 → 3 月 31，不再从被夹取的 28 继续漂移；
 *       年周期的锚点月固定不变（夹取只改日、不改月），2 月 29 在非闰年自动落 2 月 28；</li>
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
     * 从 {@code fromDay}（当天 00:00）推进到下一期，返回下一期当天 00:00。
     *
     * @param anchorDayOfMonth 月 / 年周期的原始锚点日（1–31，取自规则 start_date 的日）；
     *                         日 / 周频率忽略该参数
     * @param frequency        {@link RecurringTransactionEntity#FREQUENCY_DAILY} 等常量
     * @param interval         间隔，至少为 1（非法时按 1 处理）
     */
    public static long nextAfter(int anchorDayOfMonth, long fromDay, int frequency, int interval) {
        int safeInterval = Math.max(1, interval);
        int safeAnchor = normalizeAnchor(anchorDayOfMonth, fromDay);
        switch (frequency) {
            case RecurringTransactionEntity.FREQUENCY_WEEKLY:
                return DateUtil.addDays(fromDay, 7 * safeInterval);
            case RecurringTransactionEntity.FREQUENCY_MONTHLY:
                return clampedCalendarShift(fromDay, Calendar.MONTH, safeInterval, safeAnchor);
            case RecurringTransactionEntity.FREQUENCY_YEARLY:
                return clampedCalendarShift(fromDay, Calendar.YEAR, safeInterval, safeAnchor);
            case RecurringTransactionEntity.FREQUENCY_DAILY:
            default:
                return DateUtil.addDays(fromDay, safeInterval);
        }
    }

    /**
     * 枚举一条规则的全部到期 occurrence 日（升序，不含超出 {@code endDate} 的期）。
     *
     * <p>结束约定：{@code endDate = 0} 表示无结束日期；occurrence 日晚于 endDate 的期
     * 不再生成（规则被视为已结束）。最多返回 {@link #MAX_OCCURRENCES_PER_RULE} 期。
     *
     * @param anchorDayOfMonth 月 / 年周期的原始锚点日（每次推进都从它重推）
     * @param nextRunDate      当前 next_run_date（下一次应记账日）
     * @param today            今天 00:00（含）
     */
    @NonNull
    public static List<Long> collectDueDates(int anchorDayOfMonth, long nextRunDate, long today,
                                             long endDate, int frequency, int interval) {
        List<Long> dates = new ArrayList<>();
        long cursor = nextRunDate;
        while (cursor <= today
                && (endDate == 0L || cursor <= endDate)
                && dates.size() < MAX_OCCURRENCES_PER_RULE) {
            dates.add(cursor);
            cursor = nextAfter(anchorDayOfMonth, cursor, frequency, interval);
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

    /**
     * 走 Calendar 平移月 / 年，然后把日重设为 min(锚点日, 当月最大天数)。
     * {@code Calendar.add} 本身会把越界的日夹到目标月最后一天，重设锚点日即可实现
     * 「每次从原始锚点重推」；闰年由 {@code getActualMaximum} 自然覆盖（2 月 29 → 平年 28）。
     */
    private static long clampedCalendarShift(long dayMillis, int field, int amount, int anchorDay) {
        Calendar calendar = DateUtil.calendar(dayMillis);
        calendar.add(field, amount);
        int maxDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
        calendar.set(Calendar.DAY_OF_MONTH, Math.min(anchorDay, maxDay));
        return DateUtil.startOfDay(calendar.getTimeInMillis());
    }

    /** 非法锚点（0 等，如旧数据未回填）回落为 fromDay 的日，保证推进仍然合法。 */
    private static int normalizeAnchor(int anchorDayOfMonth, long fromDay) {
        if (anchorDayOfMonth >= 1 && anchorDayOfMonth <= 31) {
            return anchorDayOfMonth;
        }
        return DateUtil.dayOfMonthOf(fromDay);
    }
}
