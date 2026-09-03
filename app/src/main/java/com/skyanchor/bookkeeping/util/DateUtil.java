package com.skyanchor.bookkeeping.util;

import com.skyanchor.bookkeeping.data.model.DateRange;
import com.skyanchor.bookkeeping.data.model.PeriodType;

import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 日期工具：所有周期计算都在这里完成，界面层只负责把结果翻译成文案。
 *
 * <p>约定：所有「日」粒度的时间戳都是当天 00:00 的 epoch millis，与
 * {@code TransactionEntity.date} 的存储口径一致。周的起始日为**周一**。
 */
public final class DateUtil {

    /** 一天的毫秒数，仅用于内部粗算，跨日推算一律走 Calendar。 */
    private static final long MILLIS_PER_HOUR = 3_600_000L;

    /** 分组标题与参考日为同一天。 */
    public static final int DAY_TODAY = 0;
    /** 分组标题是参考日的前一天。 */
    public static final int DAY_YESTERDAY = 1;
    /** 与参考日同年，显示「9月2日」。 */
    public static final int DAY_THIS_YEAR = 2;
    /** 与参考日不同年，显示「2025年12月3日」。 */
    public static final int DAY_OTHER_YEAR = 3;

    private DateUtil() {
    }

    public static Calendar calendar(long millis) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault(), Locale.getDefault());
        calendar.setTimeInMillis(millis);
        return calendar;
    }

    /** 当天 00:00 的 epoch millis。 */
    public static long startOfDay(long millis) {
        Calendar calendar = calendar(millis);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    public static long today() {
        return startOfDay(System.currentTimeMillis());
    }

    /** 所在周的周一 00:00。 */
    public static long startOfWeek(long millis) {
        Calendar calendar = calendar(millis);
        calendar.add(Calendar.DAY_OF_YEAR, -mondayFirstIndex(calendar));
        return startOfDay(calendar.getTimeInMillis());
    }

    /** 周一为 0、周日为 6 的星期序号。 */
    public static int mondayFirstIndex(Calendar calendar) {
        return (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    /**
     * ISO-8601 周序号：周一为一周第一天，含当年首个周四的周为第 1 周。
     *
     * <p>仅用于 UI 显示（「Week N」）；周期的唯一标识仍是 {@link #startOfWeek} 得到的
     * 周一 00:00 millis，跨年时周序号可能重复，因此不能拿它当主键。
     */
    public static int weekOfYear(long millis) {
        Calendar calendar = calendar(millis);
        calendar.setFirstDayOfWeek(Calendar.MONDAY);
        calendar.setMinimalDaysInFirstWeek(4);
        return calendar.get(Calendar.WEEK_OF_YEAR);
    }

    public static int yearOf(long millis) {
        return calendar(millis).get(Calendar.YEAR);
    }

    /** 月份，取值 1-12。 */
    public static int monthOf(long millis) {
        return calendar(millis).get(Calendar.MONTH) + 1;
    }

    public static int dayOfMonthOf(long millis) {
        return calendar(millis).get(Calendar.DAY_OF_MONTH);
    }

    public static int hourOf(long millis) {
        return calendar(millis).get(Calendar.HOUR_OF_DAY);
    }

    public static int minuteOf(long millis) {
        return calendar(millis).get(Calendar.MINUTE);
    }

    /** 由年月日构造当天 00:00 的时间戳，month 取值 1-12。 */
    public static long dayMillisOf(int year, int month, int dayOfMonth) {
        Calendar calendar = calendar(System.currentTimeMillis());
        calendar.clear();
        calendar.set(year, month - 1, dayOfMonth, 0, 0, 0);
        return calendar.getTimeInMillis();
    }

    /** 平移若干天，走 Calendar 以规避夏令时带来的偏差。 */
    public static long addDays(long dayMillis, int days) {
        Calendar calendar = calendar(dayMillis);
        calendar.add(Calendar.DAY_OF_YEAR, days);
        return startOfDay(calendar.getTimeInMillis());
    }

    /** 该月的天数。 */
    public static int daysInMonth(int year, int month) {
        Calendar calendar = calendar(System.currentTimeMillis());
        calendar.clear();
        calendar.set(year, month - 1, 1);
        return calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    /** 闭区间天数，用正午时刻做差以规避夏令时。 */
    public static int dayCountInclusive(long startDay, long endDay) {
        long noonStart = startDay + 12L * MILLIS_PER_HOUR;
        long noonEnd = endDay + 12L * MILLIS_PER_HOUR;
        return (int) ((noonEnd - noonStart) / (24L * MILLIS_PER_HOUR)) + 1;
    }

    public static boolean isSameDay(long left, long right) {
        return startOfDay(left) == startOfDay(right);
    }

    // ------------------------------------------------------------------
    // MaterialDatePicker 桥接：日期选择器内部一律按 UTC 计算，
    // 直接传本地 millis 会在东八区少一天，因此进出各转换一次。
    // ------------------------------------------------------------------

    private static Calendar utcCalendar(long millis) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        calendar.setTimeInMillis(millis);
        return calendar;
    }

    /** 本地某天 00:00 的 millis 转为选择器需要的 UTC millis。 */
    public static long toUtcDayMillis(long localDayMillis) {
        return utcDayMillisOf(yearOf(localDayMillis), monthOf(localDayMillis),
                dayOfMonthOf(localDayMillis));
    }

    /** 用 UTC 时区构造当天 00:00 的 millis。 */
    public static long utcDayMillisOf(int year, int month, int dayOfMonth) {
        Calendar calendar = utcCalendar(System.currentTimeMillis());
        calendar.clear();
        calendar.set(year, month - 1, dayOfMonth, 0, 0, 0);
        return calendar.getTimeInMillis();
    }

    /** 选择器回调的 UTC millis 转回本地当天 00:00 的 millis。 */
    public static long fromUtcDayMillis(long utcDayMillis) {
        Calendar calendar = utcCalendar(utcDayMillis);
        return dayMillisOf(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    /** 指定时间戳所在的周区间。 */
    public static DateRange ofWeek(long anchor) {
        long start = startOfWeek(anchor);
        long end = addDays(start, 6);
        return new DateRange(PeriodType.WEEK, anchor, start, end, yearOf(start), monthOf(start));
    }

    /** 指定年月区间，month 取值 1-12。 */
    public static DateRange ofMonth(int year, int month) {
        long start = dayMillisOf(year, month, 1);
        long end = dayMillisOf(year, month, daysInMonth(year, month));
        return new DateRange(PeriodType.MONTH, start, start, end, year, month);
    }

    public static DateRange ofMonthOf(long anchor) {
        return ofMonth(yearOf(anchor), monthOf(anchor));
    }

    public static DateRange ofYear(int year) {
        long start = dayMillisOf(year, 1, 1);
        long end = dayMillisOf(year, 12, 31);
        return new DateRange(PeriodType.YEAR, start, start, end, year, 1);
    }

    public static DateRange ofYearOf(long anchor) {
        return ofYear(yearOf(anchor));
    }

    /** 按周期类型取锚点所在区间。 */
    public static DateRange rangeOf(PeriodType type, long anchor) {
        switch (type) {
            case WEEK:
                return ofWeek(anchor);
            case YEAR:
                return ofYearOf(anchor);
            case MONTH:
            default:
                return ofMonthOf(anchor);
        }
    }

    /**
     * 周期平移。delta = -1 得到上一周期，delta = 1 得到下一周期，
     * 严格满足「周对周、月对月、年对年」。
     */
    public static DateRange shift(DateRange range, int delta) {
        switch (range.type) {
            case WEEK:
                return ofWeek(addDays(range.anchor, delta * 7));
            case YEAR:
                return ofYear(range.year + delta);
            case MONTH:
            default: {
                int index = range.year * 12 + (range.month - 1) + delta;
                int year = Math.floorDiv(index, 12);
                int month = Math.floorMod(index, 12) + 1;
                return ofMonth(year, month);
            }
        }
    }

    /** 分组标题相对参考日的类型。 */
    public static int dayHeaderKind(long dayMillis, long referenceDayMillis) {
        long day = startOfDay(dayMillis);
        long reference = startOfDay(referenceDayMillis);
        if (day == reference) {
            return DAY_TODAY;
        }
        if (day == addDays(reference, -1)) {
            return DAY_YESTERDAY;
        }
        return yearOf(day) == yearOf(reference) ? DAY_THIS_YEAR : DAY_OTHER_YEAR;
    }

    /** 是否为「当前月」，用于限制预算设置不能选择未来月份。 */
    public static boolean isCurrentMonth(int year, int month) {
        long now = System.currentTimeMillis();
        return yearOf(now) == year && monthOf(now) == month;
    }

    /** 该月是否晚于当前月。 */
    public static boolean isFutureMonth(int year, int month) {
        long now = System.currentTimeMillis();
        int nowIndex = yearOf(now) * 12 + monthOf(now);
        return year * 12 + month > nowIndex;
    }

    /** 业务时间格式化为「HH:mm」，与语言无关。 */
    public static String formatHourMinute(int hour, int minute) {
        return String.format(Locale.US, "%02d:%02d", hour, minute);
    }

    public static String formatHourMinuteOf(long millis) {
        return formatHourMinute(hourOf(millis), minuteOf(millis));
    }

    /** 解析「HH:mm」中的小时，文本非法时返回 0。 */
    public static int hourOfTime(@Nullable String timeText) {
        return partOfTime(timeText, 0, 2);
    }

    /** 解析「HH:mm」中的分钟，文本非法时返回 0。 */
    public static int minuteOfTime(@Nullable String timeText) {
        return partOfTime(timeText, 3, 5);
    }

    private static int partOfTime(@Nullable String timeText, int start, int end) {
        if (timeText == null || timeText.length() < end) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(timeText.substring(start, end)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
