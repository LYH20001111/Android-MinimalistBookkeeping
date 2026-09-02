package com.skyanchor.bookkeeping.util;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.data.model.CategoryStat;
import com.skyanchor.bookkeeping.data.model.ChartUiState;
import com.skyanchor.bookkeeping.data.model.DateRange;
import com.skyanchor.bookkeeping.data.model.PeriodSummary;
import com.skyanchor.bookkeeping.data.model.RecordListItem;
import com.skyanchor.bookkeeping.data.model.TrendPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计计算。全部为纯函数，不依赖 Android 框架，因此可以在 JVM 单元测试中直接验证。
 *
 * <p>记录页、图表页、预算页都走这里，保证「列表、首页概览、图表、预算来自同一数据源」。
 * 金额与占比一律使用 long / int 千分比运算，不出现浮点误差。
 */
public final class StatisticsCalculator {

    private StatisticsCalculator() {
    }

    /**
     * 汇总区间内的收入、支出与笔数。
     *
     * @param items    账单集合，允许包含区间外的数据（内部会再过滤一次）
     * @param startDay 起始日 00:00 millis（含）
     * @param endDay   结束日 00:00 millis（含）
     */
    public static PeriodSummary summary(List<TransactionItem> items, long startDay, long endDay) {
        long income = 0L;
        long expense = 0L;
        int count = 0;
        if (items != null) {
            for (TransactionItem item : items) {
                if (item == null || item.date < startDay || item.date > endDay) {
                    continue;
                }
                count++;
                if (item.type == CategoryEntity.TYPE_INCOME) {
                    income += item.amount;
                } else {
                    expense += item.amount;
                }
            }
        }
        return new PeriodSummary(income, expense, count);
    }

    /** 按天生成支出趋势：周视图 7 个点，月视图为当月天数个点。 */
    public static List<TrendPoint> dailyTrend(List<TransactionItem> items, DateRange range) {
        int dayCount = range.dayCount();
        long[] values = new long[dayCount];
        Map<Long, Integer> indexByDay = new HashMap<>(dayCount * 2);
        for (int i = 0; i < dayCount; i++) {
            indexByDay.put(DateUtil.addDays(range.start, i), i);
        }
        accumulateExpense(items, range.start, range.end, indexByDay, values);

        List<TrendPoint> points = new ArrayList<>(dayCount);
        for (int i = 0; i < dayCount; i++) {
            long day = DateUtil.addDays(range.start, i);
            String label = Integer.toString(DateUtil.dayOfMonthOf(day));
            points.add(new TrendPoint(label, values[i], day));
        }
        return points;
    }

    /** 按月生成支出趋势：年视图固定 12 个点。 */
    public static List<TrendPoint> monthlyTrend(List<TransactionItem> items, int year) {
        long[] values = new long[12];
        Map<Long, Integer> indexByMonth = new HashMap<>(24);
        for (int month = 1; month <= 12; month++) {
            indexByMonth.put((long) month, month - 1);
        }
        long start = DateUtil.dayMillisOf(year, 1, 1);
        long end = DateUtil.dayMillisOf(year, 12, 31);
        if (items != null) {
            for (TransactionItem item : items) {
                if (item == null || item.date < start || item.date > end) {
                    continue;
                }
                if (item.type != CategoryEntity.TYPE_EXPENSE) {
                    continue;
                }
                int month = DateUtil.monthOf(item.date);
                Integer index = indexByMonth.get((long) month);
                if (index != null) {
                    values[index] += item.amount;
                }
            }
        }
        List<TrendPoint> points = new ArrayList<>(12);
        for (int month = 1; month <= 12; month++) {
            points.add(new TrendPoint(Integer.toString(month), values[month - 1], month));
        }
        return points;
    }

    private static void accumulateExpense(List<TransactionItem> items, long startDay, long endDay,
                                          Map<Long, Integer> indexByKey, long[] values) {
        if (items == null) {
            return;
        }
        for (TransactionItem item : items) {
            if (item == null || item.date < startDay || item.date > endDay) {
                continue;
            }
            if (item.type != CategoryEntity.TYPE_EXPENSE) {
                continue;
            }
            Integer index = indexByKey.get(item.date);
            if (index != null) {
                values[index] += item.amount;
            }
        }
    }

    /**
     * 分类占比，按金额降序。占比采用最大余额法分配，保证合计恰为 100.0%。
     *
     * @param type {@link CategoryEntity#TYPE_EXPENSE} 或 {@link CategoryEntity#TYPE_INCOME}
     */
    public static List<CategoryStat> categoryBreakdown(List<TransactionItem> items, int type,
                                                       long startDay, long endDay) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Aggregate> aggregates = new HashMap<>();
        long total = 0L;
        for (TransactionItem item : items) {
            if (item == null || item.type != type) {
                continue;
            }
            if (item.date < startDay || item.date > endDay) {
                continue;
            }
            Aggregate aggregate = aggregates.get(item.categoryId);
            if (aggregate == null) {
                aggregate = new Aggregate(item.categoryId, item.displayName(), item.displayIcon());
                aggregates.put(item.categoryId, aggregate);
            }
            aggregate.amount += item.amount;
            total += item.amount;
        }
        if (total <= 0L) {
            return Collections.emptyList();
        }

        List<Aggregate> sorted = new ArrayList<>(aggregates.values());
        Collections.sort(sorted, new Comparator<Aggregate>() {
            @Override
            public int compare(Aggregate left, Aggregate right) {
                int byAmount = Long.compare(right.amount, left.amount);
                return byAmount != 0 ? byAmount : Long.compare(left.categoryId, right.categoryId);
            }
        });

        int[] percents = largestRemainder(sorted, total);
        List<CategoryStat> stats = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Aggregate aggregate = sorted.get(i);
            stats.add(new CategoryStat(aggregate.categoryId, aggregate.name, aggregate.icon,
                    aggregate.amount, percents[i],
                    CategoryColors.colorOf(aggregate.categoryId)));
        }
        return stats;
    }

    /** 最大余额法：先取整千分比，再把剩余的份额依次分给余数最大的项。 */
    private static int[] largestRemainder(List<Aggregate> sorted, long total) {
        int size = sorted.size();
        int[] percents = new int[size];
        long[] remainders = new long[size];
        int allocated = 0;
        for (int i = 0; i < size; i++) {
            long scaled = sorted.get(i).amount * 1000L;
            percents[i] = (int) (scaled / total);
            remainders[i] = scaled % total;
            allocated += percents[i];
        }
        int rest = 1000 - allocated;
        if (rest > 0) {
            Integer[] order = new Integer[size];
            for (int i = 0; i < size; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, new Comparator<Integer>() {
                @Override
                public int compare(Integer left, Integer right) {
                    return Long.compare(remainders[right], remainders[left]);
                }
            });
            for (int i = 0; i < rest && i < size; i++) {
                percents[order[i]]++;
            }
        }
        return percents;
    }

    /**
     * 把倒序账单列表转换成「日期分组标题 + 账单行」的扁平列表，供记录页 RecyclerView 使用。
     *
     * @param items   必须已按 date DESC, time DESC 排序
     * @param labels  分组标题文案来源；传 null 时标题为空字符串（仅用于单元测试）
     */
    public static List<RecordListItem> groupByDay(List<TransactionItem> items,
                                                  DayLabelProvider labels) {
        List<RecordListItem> rows = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return rows;
        }
        long currentDay = Long.MIN_VALUE;
        long expense = 0L;
        long income = 0L;
        int count = 0;
        List<RecordListItem> pending = new ArrayList<>();
        for (TransactionItem item : items) {
            if (item == null) {
                continue;
            }
            if (item.date != currentDay) {
                if (currentDay != Long.MIN_VALUE) {
                    rows.add(newHeader(currentDay, labels, expense, income, count));
                    rows.addAll(pending);
                }
                currentDay = item.date;
                expense = 0L;
                income = 0L;
                count = 0;
                pending.clear();
            }
            if (item.type == CategoryEntity.TYPE_INCOME) {
                income += item.amount;
            } else {
                expense += item.amount;
            }
            count++;
            pending.add(new RecordListItem.Row(item));
        }
        if (currentDay != Long.MIN_VALUE) {
            rows.add(newHeader(currentDay, labels, expense, income, count));
            rows.addAll(pending);
        }
        return rows;
    }

    private static RecordListItem.Header newHeader(long dayMillis, DayLabelProvider labels,
                                                   long expense, long income, int count) {
        String label = labels == null ? "" : labels.label(dayMillis);
        return new RecordListItem.Header(dayMillis, label, expense, income, count);
    }

    /**
     * 支出环比方向。上一周期支出为 0 时无法计算，返回 {@link ChartUiState#CHANGE_NONE}。
     */
    public static int changeDirection(long current, long previous) {
        if (previous <= 0L) {
            return ChartUiState.CHANGE_NONE;
        }
        if (current > previous) {
            return ChartUiState.CHANGE_UP;
        }
        if (current < previous) {
            return ChartUiState.CHANGE_DOWN;
        }
        return ChartUiState.CHANGE_FLAT;
    }

    /** 支出环比变化幅度的绝对值，千分比整数。上一周期为 0 时返回 0。 */
    public static int changeAbsX10(long current, long previous) {
        if (previous <= 0L) {
            return 0;
        }
        long diff = Math.abs(current - previous);
        long scaled = diff * 1000L / previous;
        return (int) Math.min(Integer.MAX_VALUE, scaled);
    }

    /** 分类聚合的中间结构。 */
    private static final class Aggregate {
        final long categoryId;
        final String name;
        final String icon;
        long amount;

        Aggregate(long categoryId, String name, String icon) {
            this.categoryId = categoryId;
            this.name = name;
            this.icon = icon;
        }
    }
}
