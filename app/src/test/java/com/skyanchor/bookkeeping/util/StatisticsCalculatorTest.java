package com.skyanchor.bookkeeping.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.data.model.BudgetState;
import com.skyanchor.bookkeeping.data.model.CategoryStat;
import com.skyanchor.bookkeeping.data.model.ChartUiState;
import com.skyanchor.bookkeeping.data.model.DateRange;
import com.skyanchor.bookkeeping.data.model.PeriodSummary;
import com.skyanchor.bookkeeping.data.model.RecordListItem;
import com.skyanchor.bookkeeping.data.model.TrendPoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 统计计算单元测试（V1 基线第 7、8、11 章）。
 *
 * <p>记录页概览、图表页四层信息、预算卡片都复用这里的纯函数，因此「列表 / 概览 / 图表 /
 * 预算来自同一数据源」这条一致性要求，等价于这些函数在不同页面上给出同一结果。
 */
public class StatisticsCalculatorTest {

    private static final long MAY_1 = DateUtil.dayMillisOf(2024, 5, 1);
    private static final long MAY_13 = DateUtil.dayMillisOf(2024, 5, 13);
    private static final long MAY_14 = DateUtil.dayMillisOf(2024, 5, 14);
    private static final long MAY_15 = DateUtil.dayMillisOf(2024, 5, 15);
    private static final long MAY_16 = DateUtil.dayMillisOf(2024, 5, 16);
    private static final long MAY_19 = DateUtil.dayMillisOf(2024, 5, 19);
    private static final long MAY_31 = DateUtil.dayMillisOf(2024, 5, 31);

    private static final long CAT_FOOD = 1L;
    private static final long CAT_TRAFFIC = 2L;
    private static final long CAT_SHOPPING = 3L;
    private static final long CAT_SALARY = 11L;

    private static TransactionItem expense(long id, long amount, long date, long categoryId,
                                           String name) {
        return item(id, CategoryEntity.TYPE_EXPENSE, amount, date, categoryId, name, "💰");
    }

    private static TransactionItem income(long id, long amount, long date, long categoryId,
                                          String name) {
        return item(id, CategoryEntity.TYPE_INCOME, amount, date, categoryId, name, "🧧");
    }

    private static TransactionItem item(long id, int type, long amount, long date, long categoryId,
                                        String name, String icon) {
        TransactionItem item = new TransactionItem();
        item.id = id;
        item.type = type;
        item.amount = amount;
        item.date = date;
        item.time = "12:00";
        item.categoryId = categoryId;
        item.categoryName = name;
        item.categoryIcon = icon;
        return item;
    }

    // ------------------------------------------------------------------
    // 核心数字：收入 / 支出 / 结余
    // ------------------------------------------------------------------

    @Test
    public void summary_separatesIncomeAndExpense() {
        List<TransactionItem> items = Arrays.asList(
                expense(1L, 3580L, MAY_13, CAT_FOOD, "餐饮"),
                expense(2L, 1200L, MAY_15, CAT_TRAFFIC, "交通"),
                income(3L, 100000L, MAY_15, CAT_SALARY, "工资"));

        PeriodSummary summary = StatisticsCalculator.summary(items, MAY_13, MAY_19);

        assertEquals(100000L, summary.income);
        assertEquals(4780L, summary.expense);
        assertEquals(3, summary.count);
        // 结余 = 收入 - 支出
        assertEquals(95220L, summary.balance());
        assertFalse(summary.isEmpty());
    }

    @Test
    public void summary_ignoresItemsOutsideRange() {
        List<TransactionItem> items = Arrays.asList(
                expense(1L, 1000L, DateUtil.dayMillisOf(2024, 4, 30), CAT_FOOD, "餐饮"),
                expense(2L, 2000L, MAY_1, CAT_FOOD, "餐饮"),
                expense(3L, 4000L, DateUtil.dayMillisOf(2024, 6, 1), CAT_FOOD, "餐饮"));

        DateRange range = DateUtil.ofMonth(2024, 5);
        PeriodSummary summary = StatisticsCalculator.summary(items, range.start, range.end);

        assertEquals(2000L, summary.expense);
        assertEquals(1, summary.count);
    }

    @Test
    public void summary_isEmptyWithoutAnyBill() {
        PeriodSummary summary = StatisticsCalculator.summary(
                Collections.<TransactionItem>emptyList(), MAY_1, MAY_31);
        assertTrue(summary.isEmpty());
        assertEquals(0L, summary.balance());

        assertTrue(StatisticsCalculator.summary(null, MAY_1, MAY_31).isEmpty());
        assertEquals(0L, PeriodSummary.EMPTY.income);
        assertEquals(0L, PeriodSummary.EMPTY.expense);
    }

    // ------------------------------------------------------------------
    // 趋势：周 7 点、月为当月天数、年 12 点
    // ------------------------------------------------------------------

    @Test
    public void dailyTrend_weekHasSevenPoints() {
        List<TransactionItem> items = Arrays.asList(
                expense(1L, 1000L, MAY_13, CAT_FOOD, "餐饮"),
                expense(2L, 2500L, MAY_15, CAT_FOOD, "餐饮"),
                expense(3L, 500L, MAY_15, CAT_TRAFFIC, "交通"),
                // 收入不计入支出趋势
                income(4L, 99999L, MAY_15, CAT_SALARY, "工资"));

        List<TrendPoint> points =
                StatisticsCalculator.dailyTrend(items, DateUtil.ofWeek(MAY_15));

        assertEquals(7, points.size());
        assertEquals("13", points.get(0).label);
        assertEquals(MAY_13, points.get(0).key);
        assertEquals(1000L, points.get(0).value);
        // 同一天的多笔支出合并到一个点
        assertEquals(3000L, points.get(2).value);
        assertEquals("19", points.get(6).label);
        assertEquals(0L, points.get(6).value);
    }

    @Test
    public void dailyTrend_monthHasOnePointPerDay() {
        List<TrendPoint> points =
                StatisticsCalculator.dailyTrend(Collections.<TransactionItem>emptyList(),
                        DateUtil.ofMonth(2024, 5));
        assertEquals(31, points.size());
        assertEquals(0L, totalOf(points));
        assertEquals("1", points.get(0).label);
        assertEquals("31", points.get(30).label);

        // 闰年二月 29 个点
        assertEquals(29, StatisticsCalculator
                .dailyTrend(Collections.<TransactionItem>emptyList(), DateUtil.ofMonth(2024, 2))
                .size());
    }

    @Test
    public void monthlyTrend_yearHasTwelvePoints() {
        List<TransactionItem> items = Arrays.asList(
                expense(1L, 1000L, DateUtil.dayMillisOf(2024, 1, 20), CAT_FOOD, "餐饮"),
                expense(2L, 2000L, DateUtil.dayMillisOf(2024, 5, 1), CAT_FOOD, "餐饮"),
                expense(3L, 4000L, DateUtil.dayMillisOf(2024, 5, 20), CAT_TRAFFIC, "交通"),
                expense(4L, 8000L, DateUtil.dayMillisOf(2024, 12, 31), CAT_SHOPPING, "购物"),
                // 跨年的账单不计入本年度
                expense(5L, 99999L, DateUtil.dayMillisOf(2023, 12, 31), CAT_FOOD, "餐饮"));

        List<TrendPoint> points = StatisticsCalculator.monthlyTrend(items, 2024);

        assertEquals(12, points.size());
        assertEquals("1", points.get(0).label);
        assertEquals(1L, points.get(0).key);
        assertEquals(1000L, points.get(0).value);
        assertEquals(6000L, points.get(4).value);
        assertEquals(8000L, points.get(11).value);
        assertEquals(15000L, totalOf(points));
    }

    private static long totalOf(List<TrendPoint> points) {
        long total = 0L;
        for (TrendPoint point : points) {
            total += point.value;
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 分类占比：降序 + 千分比整数
    // ------------------------------------------------------------------

    @Test
    public void categoryBreakdown_sortsByAmountDescending() {
        List<TransactionItem> items = Arrays.asList(
                expense(1L, 1200L, MAY_13, CAT_TRAFFIC, "交通"),
                expense(2L, 8000L, MAY_14, CAT_FOOD, "餐饮"),
                expense(3L, 3000L, MAY_15, CAT_SHOPPING, "购物"),
                expense(4L, 500L, MAY_16, CAT_FOOD, "餐饮"),
                income(5L, 100000L, MAY_16, CAT_SALARY, "工资"));

        List<CategoryStat> stats = StatisticsCalculator.categoryBreakdown(
                items, CategoryEntity.TYPE_EXPENSE, MAY_1, MAY_31);

        assertEquals(3, stats.size());
        assertEquals(CAT_FOOD, stats.get(0).categoryId);
        assertEquals(8500L, stats.get(0).amount);
        assertEquals(CAT_SHOPPING, stats.get(1).categoryId);
        assertEquals(CAT_TRAFFIC, stats.get(2).categoryId);
        for (int i = 1; i < stats.size(); i++) {
            assertTrue(stats.get(i - 1).amount >= stats.get(i).amount);
        }
    }

    /**
     * 占比用最大余额法分配，合计必须恰好 100.0%，而不是 99.9% 或 100.1%。
     * 三等分是最容易暴露截断误差的场景。
     */
    @Test
    public void categoryBreakdown_percentsSumToExactlyOneHundred() {
        List<TransactionItem> items = Arrays.asList(
                expense(1L, 1000L, MAY_13, CAT_FOOD, "餐饮"),
                expense(2L, 1000L, MAY_14, CAT_TRAFFIC, "交通"),
                expense(3L, 1000L, MAY_15, CAT_SHOPPING, "购物"));

        List<CategoryStat> stats = StatisticsCalculator.categoryBreakdown(
                items, CategoryEntity.TYPE_EXPENSE, MAY_1, MAY_31);

        assertEquals(1000, percentSum(stats));
        assertEquals("33.4%", stats.get(0).percentText());
        assertEquals("33.3%", stats.get(1).percentText());
        assertEquals("33.3%", stats.get(2).percentText());
    }

    @Test
    public void categoryBreakdown_percentsSumToOneHundredForOddTotals() {
        List<TransactionItem> items = Arrays.asList(
                expense(1L, 1234L, MAY_13, CAT_FOOD, "餐饮"),
                expense(2L, 5678L, MAY_14, CAT_TRAFFIC, "交通"),
                expense(3L, 901L, MAY_15, CAT_SHOPPING, "购物"),
                expense(4L, 7L, MAY_16, CAT_SALARY, "其他"));

        List<CategoryStat> stats = StatisticsCalculator.categoryBreakdown(
                items, CategoryEntity.TYPE_EXPENSE, MAY_1, MAY_31);

        assertEquals(1000, percentSum(stats));
        assertEquals(7820L, amountSum(stats));
        // 占比文案由千分比整数还原，不经过浮点：5678/7820 = 72.6%
        assertEquals("72.6%", stats.get(0).percentText());
    }

    @Test
    public void categoryBreakdown_isEmptyWithoutMatchingBills() {
        assertTrue(StatisticsCalculator.categoryBreakdown(null, CategoryEntity.TYPE_EXPENSE,
                MAY_1, MAY_31).isEmpty());
        assertTrue(StatisticsCalculator.categoryBreakdown(
                Collections.<TransactionItem>emptyList(), CategoryEntity.TYPE_EXPENSE,
                MAY_1, MAY_31).isEmpty());

        // 只有收入时，支出占比为空，图表页据此走空状态而不是画空白环
        List<TransactionItem> incomeOnly =
                Collections.singletonList(income(1L, 5000L, MAY_13, CAT_SALARY, "工资"));
        assertTrue(StatisticsCalculator.categoryBreakdown(incomeOnly,
                CategoryEntity.TYPE_EXPENSE, MAY_1, MAY_31).isEmpty());
        assertEquals(1, StatisticsCalculator.categoryBreakdown(incomeOnly,
                CategoryEntity.TYPE_INCOME, MAY_1, MAY_31).size());
    }

    @Test
    public void categoryBreakdown_sameCategoryKeepsStableColor() {
        List<TransactionItem> items = Collections.singletonList(
                expense(1L, 1000L, MAY_13, CAT_FOOD, "餐饮"));
        CategoryStat first = StatisticsCalculator
                .categoryBreakdown(items, CategoryEntity.TYPE_EXPENSE, MAY_1, MAY_31).get(0);
        CategoryStat second = StatisticsCalculator
                .categoryBreakdown(items, CategoryEntity.TYPE_EXPENSE, MAY_1, MAY_31).get(0);
        assertEquals(first.color, second.color);
        assertEquals(CategoryColors.colorOf(CAT_FOOD), first.color);
    }

    private static int percentSum(List<CategoryStat> stats) {
        int sum = 0;
        for (CategoryStat stat : stats) {
            sum += stat.percentX10;
        }
        return sum;
    }

    private static long amountSum(List<CategoryStat> stats) {
        long sum = 0L;
        for (CategoryStat stat : stats) {
            sum += stat.amount;
        }
        return sum;
    }

    // ------------------------------------------------------------------
    // 预算三档状态（V1 基线第 8.2 节）
    // ------------------------------------------------------------------

    @Test
    public void budgetState_normalBelowEightyPercent() {
        BudgetState state = BudgetState.of(100000L, 73600L);
        assertTrue(state.hasBudget);
        assertEquals(BudgetState.STATUS_NORMAL, state.status);
        assertEquals(736, state.percentX10);
        assertEquals("73.6%", state.percentText());
        assertEquals(26400L, state.remaining);
        assertEquals(0L, state.overAmount);
    }

    @Test
    public void budgetState_warningBetweenEightyAndHundredPercent() {
        BudgetState state = BudgetState.of(100000L, 85000L);
        assertEquals(BudgetState.STATUS_WARNING, state.status);
        assertEquals(850, state.percentX10);
        assertEquals("85%", state.percentText());
        assertEquals(15000L, state.remaining);
        assertEquals(0L, state.overAmount);

        // 80% 是 warning 的下边界（含）
        assertEquals(BudgetState.STATUS_WARNING, BudgetState.of(100000L, 80000L).status);
        // 100% 仍未超支，属于 warning 上边界
        assertEquals(BudgetState.STATUS_WARNING, BudgetState.of(100000L, 100000L).status);
        assertEquals(0L, BudgetState.of(100000L, 100000L).remaining);
    }

    @Test
    public void budgetState_overAboveHundredPercent() {
        BudgetState state = BudgetState.of(100000L, 120000L);
        assertEquals(BudgetState.STATUS_OVER, state.status);
        assertEquals(1200, state.percentX10);
        assertEquals("120%", state.percentText());
        assertEquals(-20000L, state.remaining);
        // 超出金额取绝对值，界面直接展示「超出 ¥200.00」
        assertEquals(20000L, state.overAmount);
        assertEquals("¥200.00", AmountUtil.format(state.overAmount));
        // 进度条封顶为 1，不能被 120% 撑破布局
        assertEquals(1f, state.progressRatio(), 0.0001f);
    }

    @Test
    public void budgetState_notSetWhenBudgetMissing() {
        assertSame(BudgetState.NOT_SET, BudgetState.of(0L, 5000L));
        assertSame(BudgetState.NOT_SET, BudgetState.of(-1L, 5000L));

        BudgetState state = BudgetState.of(0L, 5000L);
        assertFalse(state.hasBudget);
        assertEquals(BudgetState.STATUS_NORMAL, state.status);
        assertEquals(0f, state.progressRatio(), 0.0001f);
    }

    // ------------------------------------------------------------------
    // 环比（V1 基线第 7.1 节第二层）
    // ------------------------------------------------------------------

    @Test
    public void change_computesDirectionAndMagnitudeWithoutFloat() {
        assertEquals(ChartUiState.CHANGE_UP, StatisticsCalculator.changeDirection(112500L, 100000L));
        assertEquals(125, StatisticsCalculator.changeAbsX10(112500L, 100000L));

        assertEquals(ChartUiState.CHANGE_DOWN, StatisticsCalculator.changeDirection(91500L, 100000L));
        assertEquals(85, StatisticsCalculator.changeAbsX10(91500L, 100000L));

        assertEquals(ChartUiState.CHANGE_FLAT, StatisticsCalculator.changeDirection(100000L, 100000L));
        assertEquals(0, StatisticsCalculator.changeAbsX10(100000L, 100000L));
    }

    /** 上一周期支出为 0 时无法计算环比，必须显示「—」而不是除零或 ∞。 */
    @Test
    public void change_isNoneWhenPreviousPeriodHasNoExpense() {
        assertEquals(ChartUiState.CHANGE_NONE, StatisticsCalculator.changeDirection(5000L, 0L));
        assertEquals(0, StatisticsCalculator.changeAbsX10(5000L, 0L));
        assertEquals(ChartUiState.CHANGE_NONE, StatisticsCalculator.changeDirection(0L, 0L));
    }

    @Test
    public void chartUiState_rendersChangeTextAndDefaultsNulls() {
        DateRange range = DateUtil.ofMonth(2024, 5);
        PeriodSummary current = new PeriodSummary(0L, 112500L, 5);
        PeriodSummary previous = new PeriodSummary(0L, 100000L, 4);

        ChartUiState state = new ChartUiState(range, "2024年5月", current, previous,
                StatisticsCalculator.changeAbsX10(current.expense, previous.expense),
                StatisticsCalculator.changeDirection(current.expense, previous.expense),
                null, null, null);

        assertEquals("12.5%", state.changeValueText());
        assertFalse(state.isEmpty());
        // 构造器对 null 做兜底，界面层不需要再判空
        assertTrue(state.trend.isEmpty());
        assertTrue(state.categoryStats.isEmpty());
        assertSame(BudgetState.NOT_SET, state.budgetState);

        ChartUiState empty = new ChartUiState(range, "", PeriodSummary.EMPTY, PeriodSummary.EMPTY,
                0, ChartUiState.CHANGE_NONE, null, null, null);
        assertTrue(empty.isEmpty());
    }

    // ------------------------------------------------------------------
    // 记录页的日期分组
    // ------------------------------------------------------------------

    /**
     * 分组必须保持传入的倒序，且每组的合计只统计本组账单——
     * 记录页头部「当日支出合计」与列表内容来自同一次遍历，才不会互相矛盾。
     */
    @Test
    public void groupByDay_emitsHeaderThenRowsPerDay() {
        List<TransactionItem> items = new ArrayList<>(Arrays.asList(
                expense(1L, 1000L, MAY_15, CAT_FOOD, "餐饮"),
                income(2L, 5000L, MAY_15, CAT_SALARY, "工资"),
                expense(3L, 2000L, MAY_13, CAT_TRAFFIC, "交通")));

        List<RecordListItem> rows = StatisticsCalculator.groupByDay(items, null);

        assertEquals(5, rows.size());

        RecordListItem.Header first = (RecordListItem.Header) rows.get(0);
        assertEquals(RecordListItem.VIEW_TYPE_HEADER, first.viewType());
        assertEquals(MAY_15, first.dayMillis);
        assertEquals(1000L, first.expense);
        assertEquals(5000L, first.income);
        assertEquals(2, first.count);
        assertEquals("", first.label);

        assertEquals(RecordListItem.VIEW_TYPE_TRANSACTION, rows.get(1).viewType());
        assertEquals(1L, ((RecordListItem.Row) rows.get(1)).item.id);

        RecordListItem.Header second = (RecordListItem.Header) rows.get(3);
        assertEquals(MAY_13, second.dayMillis);
        assertEquals(2000L, second.expense);
        assertEquals(0L, second.income);
        assertEquals(1, second.count);
    }

    @Test
    public void groupByDay_usesInjectedLabelProvider() {
        List<TransactionItem> items =
                Collections.singletonList(expense(1L, 1000L, MAY_15, CAT_FOOD, "餐饮"));
        List<RecordListItem> rows = StatisticsCalculator.groupByDay(items,
                dayMillis -> dayMillis == MAY_15 ? "今天" : "其它");

        assertEquals("今天", ((RecordListItem.Header) rows.get(0)).label);
    }

    @Test
    public void groupByDay_isEmptyWithoutBills() {
        assertTrue(StatisticsCalculator.groupByDay(null, null).isEmpty());
        assertTrue(StatisticsCalculator.groupByDay(
                Collections.<TransactionItem>emptyList(), null).isEmpty());
    }
}
