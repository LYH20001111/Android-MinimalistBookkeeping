package com.skyanchor.bookkeeping.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.skyanchor.bookkeeping.data.model.DateRange;
import com.skyanchor.bookkeeping.data.model.PeriodType;

import org.junit.Test;

/**
 * 日期与周期单元测试（V1 基线第 7、8、11 章）。
 *
 * <p>重点验证三条约定：日粒度时间戳恒为当天 00:00、周以**周一**为第一天、
 * 周期平移严格「周对周、月对月、年对年」。全部断言基于固定日期，与运行当天无关。
 */
public class DateUtilTest {

    /** 2024-05-15 是周三，所在周的周一为 2024-05-13。 */
    private static final long WED = DateUtil.dayMillisOf(2024, 5, 15);
    private static final long MONDAY = DateUtil.dayMillisOf(2024, 5, 13);
    private static final long SUNDAY = DateUtil.dayMillisOf(2024, 5, 19);

    // ------------------------------------------------------------------
    // 日粒度
    // ------------------------------------------------------------------

    @Test
    public void startOfDay_truncatesTimePart() {
        long noon = WED + 13L * 3_600_000L + 45L * 60_000L;
        assertEquals(WED, DateUtil.startOfDay(noon));
        assertEquals(WED, DateUtil.startOfDay(WED));
    }

    @Test
    public void dayMillisOf_extractsYearMonthDay() {
        assertEquals(2024, DateUtil.yearOf(WED));
        assertEquals(5, DateUtil.monthOf(WED));
        assertEquals(15, DateUtil.dayOfMonthOf(WED));
    }

    @Test
    public void startOfWeek_usesMondayAsFirstDay() {
        assertEquals(MONDAY, DateUtil.startOfWeek(WED));
        // 周一自身就是一周的开始
        assertEquals(MONDAY, DateUtil.startOfWeek(MONDAY));
        // 周日属于本周最后一天，不能被当成新一周的开始
        assertEquals(MONDAY, DateUtil.startOfWeek(SUNDAY));
    }

    @Test
    public void mondayFirstIndex_mapsSundayToSix() {
        assertEquals(0, DateUtil.mondayFirstIndex(DateUtil.calendar(MONDAY)));
        assertEquals(2, DateUtil.mondayFirstIndex(DateUtil.calendar(WED)));
        assertEquals(6, DateUtil.mondayFirstIndex(DateUtil.calendar(SUNDAY)));
    }

    @Test
    public void addDays_crossesMonthAndYearBoundary() {
        assertEquals(DateUtil.dayMillisOf(2024, 2, 1),
                DateUtil.addDays(DateUtil.dayMillisOf(2024, 1, 31), 1));
        assertEquals(DateUtil.dayMillisOf(2025, 1, 1),
                DateUtil.addDays(DateUtil.dayMillisOf(2024, 12, 31), 1));
        // 2024 是闰年，2月28日的次日是 2月29日；2023 不是闰年，次日直接进 3月
        assertEquals(DateUtil.dayMillisOf(2024, 2, 29),
                DateUtil.addDays(DateUtil.dayMillisOf(2024, 2, 28), 1));
        assertEquals(DateUtil.dayMillisOf(2023, 3, 1),
                DateUtil.addDays(DateUtil.dayMillisOf(2023, 2, 28), 1));
    }

    @Test
    public void daysInMonth_accountsForLeapYear() {
        assertEquals(29, DateUtil.daysInMonth(2024, 2));
        assertEquals(28, DateUtil.daysInMonth(2023, 2));
        assertEquals(31, DateUtil.daysInMonth(2024, 5));
        assertEquals(30, DateUtil.daysInMonth(2024, 4));
    }

    @Test
    public void isSameDay_ignoresTimePart() {
        assertTrue(DateUtil.isSameDay(WED, WED + 3_600_000L));
        assertFalse(DateUtil.isSameDay(WED, DateUtil.addDays(WED, 1)));
    }

    // ------------------------------------------------------------------
    // 周期区间
    // ------------------------------------------------------------------

    @Test
    public void ofWeek_coversMondayToSunday() {
        DateRange range = DateUtil.ofWeek(WED);
        assertEquals(PeriodType.WEEK, range.type);
        assertEquals(MONDAY, range.start);
        assertEquals(SUNDAY, range.end);
        assertEquals(7, range.dayCount());
        assertEquals(2024, range.year);
        assertEquals(5, range.month);
    }

    @Test
    public void ofMonth_coversWholeMonth() {
        DateRange may = DateUtil.ofMonth(2024, 5);
        assertEquals(DateUtil.dayMillisOf(2024, 5, 1), may.start);
        assertEquals(DateUtil.dayMillisOf(2024, 5, 31), may.end);
        assertEquals(31, may.dayCount());

        DateRange leapFebruary = DateUtil.ofMonth(2024, 2);
        assertEquals(DateUtil.dayMillisOf(2024, 2, 29), leapFebruary.end);
        assertEquals(29, leapFebruary.dayCount());
    }

    @Test
    public void ofYear_coversJanuaryToDecember() {
        DateRange range = DateUtil.ofYear(2024);
        assertEquals(DateUtil.dayMillisOf(2024, 1, 1), range.start);
        assertEquals(DateUtil.dayMillisOf(2024, 12, 31), range.end);
        assertEquals(366, range.dayCount());
        assertEquals(365, DateUtil.ofYear(2023).dayCount());
    }

    @Test
    public void rangeOf_dispatchesByPeriodType() {
        assertEquals(DateUtil.ofWeek(WED), DateUtil.rangeOf(PeriodType.WEEK, WED));
        assertEquals(DateUtil.ofMonth(2024, 5), DateUtil.rangeOf(PeriodType.MONTH, WED));
        assertEquals(DateUtil.ofYear(2024), DateUtil.rangeOf(PeriodType.YEAR, WED));
    }

    @Test
    public void shiftMonth_keepsMonthGranularity() {
        DateRange previous = DateUtil.ofMonth(2024, 5).previous();
        assertEquals(2024, previous.year);
        assertEquals(4, previous.month);
        assertEquals(30, previous.dayCount());

        // 跨年：2024年1月 的上一周期是 2023年12月
        DateRange crossYear = DateUtil.ofMonth(2024, 1).previous();
        assertEquals(2023, crossYear.year);
        assertEquals(12, crossYear.month);

        DateRange nextCrossYear = DateUtil.ofMonth(2024, 12).next();
        assertEquals(2025, nextCrossYear.year);
        assertEquals(1, nextCrossYear.month);

        // 3月31日 的下一周期是 4月（30 天），不能被天数带偏
        assertEquals(30, DateUtil.ofMonth(2024, 3).next().dayCount());
    }

    @Test
    public void shiftWeek_movesExactlySevenDays() {
        DateRange previous = DateUtil.ofWeek(WED).previous();
        assertEquals(DateUtil.dayMillisOf(2024, 5, 6), previous.start);
        assertEquals(DateUtil.dayMillisOf(2024, 5, 12), previous.end);

        DateRange next = DateUtil.ofWeek(WED).next();
        assertEquals(DateUtil.dayMillisOf(2024, 5, 20), next.start);
    }

    @Test
    public void shiftYear_movesExactlyOneYear() {
        assertEquals(2023, DateUtil.ofYear(2024).previous().year);
        assertEquals(2025, DateUtil.ofYear(2024).next().year);
    }

    @Test
    public void compareStart_reachesPreviousPeriodStart() {
        DateRange range = DateUtil.ofMonth(2024, 5);
        assertEquals(DateUtil.dayMillisOf(2024, 4, 1), range.compareStart());
    }

    // ------------------------------------------------------------------
    // 记录页的日期分组
    // ------------------------------------------------------------------

    @Test
    public void dayHeaderKind_isRelativeToBusinessDate() {
        assertEquals(DateUtil.DAY_TODAY, DateUtil.dayHeaderKind(WED, WED));
        assertEquals(DateUtil.DAY_TODAY, DateUtil.dayHeaderKind(WED + 3_600_000L, WED));
        assertEquals(DateUtil.DAY_YESTERDAY,
                DateUtil.dayHeaderKind(DateUtil.addDays(WED, -1), WED));
        assertEquals(DateUtil.DAY_THIS_YEAR,
                DateUtil.dayHeaderKind(DateUtil.dayMillisOf(2024, 9, 2), WED));
        assertEquals(DateUtil.DAY_OTHER_YEAR,
                DateUtil.dayHeaderKind(DateUtil.dayMillisOf(2023, 12, 3), WED));
        // 昨天与同年判定不能混淆：跨年时即使只差一天也走完整日期
        assertEquals(DateUtil.DAY_YESTERDAY,
                DateUtil.dayHeaderKind(DateUtil.dayMillisOf(2023, 12, 31),
                        DateUtil.dayMillisOf(2024, 1, 1)));
    }

    // ------------------------------------------------------------------
    // 时间与选择器桥接
    // ------------------------------------------------------------------

    @Test
    public void formatHourMinute_alwaysPadsToTwoDigits() {
        assertEquals("09:05", DateUtil.formatHourMinute(9, 5));
        assertEquals("00:00", DateUtil.formatHourMinute(0, 0));
        assertEquals("23:59", DateUtil.formatHourMinute(23, 59));
    }

    @Test
    public void timeTextParsing_fallsBackToZeroOnGarbage() {
        assertEquals(9, DateUtil.hourOfTime("09:05"));
        assertEquals(5, DateUtil.minuteOfTime("09:05"));
        assertEquals(0, DateUtil.hourOfTime(null));
        assertEquals(0, DateUtil.minuteOfTime(null));
        assertEquals(0, DateUtil.hourOfTime("bad"));
        assertEquals(0, DateUtil.minuteOfTime("bad"));
    }

    /**
     * MaterialDatePicker 内部按 UTC 计算，进出各转换一次才能保证东八区不「少一天」。
     */
    @Test
    public void utcBridge_roundTripsWithoutLosingADay() {
        long[] samples = {
                WED,
                MONDAY,
                DateUtil.dayMillisOf(2024, 1, 1),
                DateUtil.dayMillisOf(2024, 2, 29),
                DateUtil.dayMillisOf(2024, 12, 31),
        };
        for (long day : samples) {
            assertEquals(day, DateUtil.fromUtcDayMillis(DateUtil.toUtcDayMillis(day)));
        }
    }

    // ------------------------------------------------------------------
    // 预算页的月份约束
    // ------------------------------------------------------------------

    @Test
    public void futureMonthGuard_matchesCurrentMonth() {
        long now = System.currentTimeMillis();
        int year = DateUtil.yearOf(now);
        int month = DateUtil.monthOf(now);

        assertTrue(DateUtil.isCurrentMonth(year, month));
        assertFalse(DateUtil.isFutureMonth(year, month));
        assertTrue(DateUtil.isFutureMonth(year + 1, 1));
        assertFalse(DateUtil.isCurrentMonth(year + 1, 1));

        // 上一月一定不是未来月
        DateRange previous = DateUtil.ofMonth(year, month).previous();
        assertFalse(DateUtil.isFutureMonth(previous.year, previous.month));
    }
}
