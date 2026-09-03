package com.skyanchor.bookkeeping.ui.chart;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.data.model.AccountBalance;
import com.skyanchor.bookkeeping.data.model.BudgetState;
import com.skyanchor.bookkeeping.data.model.CategoryBudgetState;
import com.skyanchor.bookkeeping.data.model.CategoryStat;
import com.skyanchor.bookkeeping.data.model.ChartUiState;
import com.skyanchor.bookkeeping.data.model.DateRange;
import com.skyanchor.bookkeeping.data.model.DayCount;
import com.skyanchor.bookkeeping.data.model.PeriodOption;
import com.skyanchor.bookkeeping.data.model.PeriodSummary;
import com.skyanchor.bookkeeping.data.model.PeriodType;
import com.skyanchor.bookkeeping.data.model.TrendPoint;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.domain.budget.CalculateBudgetUseCase;
import com.skyanchor.bookkeeping.util.DateLabels;
import com.skyanchor.bookkeeping.util.DateUtil;
import com.skyanchor.bookkeeping.util.StatisticsCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图表页 ViewModel。
 *
 * <p>周期类型与锚点日期合并成一个 {@link PeriodQuery} 存进 LiveData，经
 * {@code switchMap} 派生出 {@link ChartUiState}，因此切 Tab、旋转屏幕、返回本页
 * 都不会丢失用户已选的周期（V1 基线第 15 章）。
 *
 * <p>查询区间一次性覆盖「当前周期 + 上一相同周期」，环比无需第二次查库；
 * 月视图再合并预算表，两者都变化时重算同一个快照，保证核心数字、趋势、分类、预算同源。
 */
public class ChartViewModel extends AndroidViewModel {

    /** 周期查询条件：类型 + 锚点日期（当天 00:00 的 millis）。 */
    public static final class PeriodQuery {

        @NonNull
        public final PeriodType type;

        public final long anchor;

        public PeriodQuery(@NonNull PeriodType type, long anchor) {
            this.type = type;
            this.anchor = DateUtil.startOfDay(anchor);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PeriodQuery)) {
                return false;
            }
            PeriodQuery other = (PeriodQuery) o;
            return anchor == other.anchor && type == other.type;
        }

        @Override
        public int hashCode() {
            return 31 * type.hashCode() + (int) (anchor ^ (anchor >>> 32));
        }
    }

    /** 两个数据源的当前值，任一变化都用最新组合重算快照。 */
    private static final class Sources {
        @Nullable
        List<TransactionItem> items;
        @Nullable
        BudgetEntity budget;
        @Nullable
        List<BudgetEntity> categoryBudgets;
        @Nullable
        List<CategoryEntity> expenseCategories;
    }

    private final BookkeepingRepository repository;
    private final MutableLiveData<PeriodQuery> query;
    private final LiveData<ChartUiState> uiState;

    /** 周期选择器数据源：按周/月/年分别有界聚合（V2 Risk C，不再全量扫描每日笔数）。 */
    private final LiveData<List<PeriodOption>> weekOptions;
    private final LiveData<List<PeriodOption>> monthOptions;
    private final LiveData<List<PeriodOption>> yearOptions;

    /** 未归档账户余额（联表重算），供「账户资金」卡片；与周期无关，单独观察刷新。 */
    private final LiveData<List<AccountBalance>> accountBalances;

    public ChartViewModel(@NonNull Application application) {
        super(application);
        this.repository = BookkeepingApp.get(application).getRepository();
        // 默认月视图：基线 7.2 中月视图信息最全（含预算），是最常用的落脚点
        this.query = new MutableLiveData<>(new PeriodQuery(PeriodType.MONTH, DateUtil.today()));
        this.uiState = Transformations.switchMap(query, this::observe);

        // V2 Risk C：周/月/年各自一条有界聚合查询（一行 = 一个周期），
        // 避免全量加载每日笔数带来的扫描；Java 侧再做区间补全与标题本地化。
        this.weekOptions = Transformations.map(repository.observeWeekCounts(), this::buildWeekOptions);
        this.monthOptions =
                Transformations.map(repository.observeMonthCounts(), this::buildMonthOptions);
        this.yearOptions = Transformations.map(repository.observeYearCounts(), this::buildYearOptions);
        this.accountBalances = repository.observeActiveAccountBalances();
    }

    public LiveData<ChartUiState> getUiState() {
        return uiState;
    }

    /** 未归档账户余额，按 sort_order 升序；总资产 = 这些账户余额之和。 */
    public LiveData<List<AccountBalance>> getAccountBalances() {
        return accountBalances;
    }

    @Nullable
    public PeriodQuery getQuery() {
        return query.getValue();
    }

    /** 切换周/月/年，保留当前锚点日期，避免来回切换时跳回今天。 */
    public void selectPeriod(@NonNull PeriodType type) {
        PeriodQuery current = query.getValue();
        if (current != null && current.type == type) {
            return;
        }
        long anchor = current == null ? DateUtil.today() : current.anchor;
        query.setValue(new PeriodQuery(type, anchor));
    }

    public void previousPeriod() {
        shift(-1);
    }

    public void nextPeriod() {
        shift(1);
    }

    /** 严格「周对周、月对月、年对年」地平移一个周期。 */
    private void shift(int delta) {
        PeriodQuery current = query.getValue();
        if (current == null) {
            return;
        }
        DateRange range = DateUtil.rangeOf(current.type, current.anchor);
        DateRange target = delta < 0 ? range.previous() : range.next();
        query.setValue(new PeriodQuery(current.type, target.anchor));
    }

    // ------------------------------------------------------------------
    // 周期选择器（V1.1 目标 C：点击周期导航中间区域直接跳转远期周期）
    // ------------------------------------------------------------------

    /** 取指定周期类型的可选项列表，供 {@code PeriodPickerDialog} 渲染。 */
    public LiveData<List<PeriodOption>> getPeriodOptions(@NonNull PeriodType type) {
        switch (type) {
            case WEEK:
                return weekOptions;
            case YEAR:
                return yearOptions;
            case MONTH:
            default:
                return monthOptions;
        }
    }

    /** 周期选择器回调：保留当前周期类型，把锚点跳到选中周期首日。 */
    public void selectAnchorDate(long anchorDate) {
        PeriodQuery current = query.getValue();
        PeriodType type = current == null ? PeriodType.MONTH : current.type;
        query.setValue(new PeriodQuery(type, anchorDate));
    }

    /** 回到当前周/月/年：锚点重置为今天，保留周期类型。 */
    public void backToCurrentPeriod() {
        PeriodQuery current = query.getValue();
        PeriodType type = current == null ? PeriodType.MONTH : current.type;
        query.setValue(new PeriodQuery(type, DateUtil.today()));
    }

    // ------------------------------------------------------------------
    // 数据装配
    // ------------------------------------------------------------------

    private LiveData<ChartUiState> observe(@NonNull PeriodQuery periodQuery) {
        final DateRange range = DateUtil.rangeOf(periodQuery.type, periodQuery.anchor);
        final MediatorLiveData<ChartUiState> result = new MediatorLiveData<>();
        final Sources sources = new Sources();

        result.addSource(repository.observeTransactionsBetween(range.compareStart(), range.end),
                items -> {
                    sources.items = items;
                    result.setValue(toState(periodQuery.type, range, sources));
                });

        if (periodQuery.type == PeriodType.MONTH) {
            result.addSource(repository.observeBudget(range.year, range.month), budget -> {
                sources.budget = budget;
                result.setValue(toState(periodQuery.type, range, sources));
            });
            // 分类预算完成度（V2 Phase 6）：与总预算同源合并，任一变化都重算同一快照
            result.addSource(
                    repository.observeCategoryBudgets(range.year, range.month),
                    categoryBudgets -> {
                        sources.categoryBudgets = categoryBudgets;
                        result.setValue(toState(periodQuery.type, range, sources));
                    });
            result.addSource(repository.observeCategories(CategoryEntity.TYPE_EXPENSE),
                    expenseCategories -> {
                        sources.expenseCategories = expenseCategories;
                        result.setValue(toState(periodQuery.type, range, sources));
                    });
        }
        return result;
    }

    private ChartUiState toState(@NonNull PeriodType type, @NonNull DateRange range,
                                 @NonNull Sources sources) {
        Context context = getApplication();
        List<TransactionItem> items = sources.items;

        PeriodSummary summary = StatisticsCalculator.summary(items, range.start, range.end);
        DateRange previous = range.previous();
        PeriodSummary previousSummary =
                StatisticsCalculator.summary(items, previous.start, previous.end);

        List<TrendPoint> trend = type == PeriodType.YEAR
                ? localize(StatisticsCalculator.monthlyTrend(items, range.year), PeriodType.YEAR)
                : localize(StatisticsCalculator.dailyTrend(items, range), type);

        List<CategoryStat> categoryStats = StatisticsCalculator.categoryBreakdown(items,
                CategoryEntity.TYPE_EXPENSE, range.start, range.end);

        // 预算只按支出计算，且只在月视图出现（V1 基线第 8 章）
        BudgetState budgetState = type == PeriodType.MONTH
                ? BudgetState.of(sources.budget == null ? 0L : sources.budget.amount,
                        summary.expense)
                : BudgetState.NOT_SET;

        // 分类预算完成度：仅保留已设置预算的分类，按严重度排序（V2 Phase 6）
        List<CategoryBudgetState> categoryBudgetStates = type == PeriodType.MONTH
                ? CalculateBudgetUseCase.assembleForOverview(sources.expenseCategories,
                        sources.categoryBudgets, categoryStats)
                : Collections.<CategoryBudgetState>emptyList();

        return new ChartUiState(range, DateLabels.periodTitle(context, range), summary,
                previousSummary,
                StatisticsCalculator.changeAbsX10(summary.expense, previousSummary.expense),
                StatisticsCalculator.changeDirection(summary.expense, previousSummary.expense),
                trend, categoryStats, budgetState, categoryBudgetStates);
    }

    /**
     * 把 {@code StatisticsCalculator} 产出的中性数字标签本地化：
     * 周换成「一…日」，年换成「1月…12月」；月视图保留「日」数字，由折线图自行抽样。
     */
    @NonNull
    private List<TrendPoint> localize(@NonNull List<TrendPoint> points, @NonNull PeriodType type) {
        if (type != PeriodType.WEEK && type != PeriodType.YEAR) {
            return points;
        }
        String[] labels = getApplication().getResources().getStringArray(
                type == PeriodType.WEEK ? R.array.weekday_labels : R.array.month_labels);
        List<TrendPoint> localized = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            TrendPoint point = points.get(i);
            localized.add(i < labels.length ? point.withLabel(labels[i]) : point);
        }
        return localized;
    }

    // ------------------------------------------------------------------
    // 周期选项聚合：从「每天笔数」在 Java 侧汇总为周/月/年，不逐周期查库
    // ------------------------------------------------------------------

    @NonNull
    private List<PeriodOption> buildWeekOptions(@Nullable List<DayCount> dayCounts) {
        Context context = getApplication();
        long today = DateUtil.today();
        Map<Long, Integer> countByStart = new HashMap<>();
        long minDay = today;
        long maxDay = today;
        if (dayCounts != null) {
            for (DayCount dayCount : dayCounts) {
                long weekStart = DateUtil.startOfWeek(dayCount.day);
                Integer previous = countByStart.get(weekStart);
                countByStart.put(weekStart,
                        (previous == null ? 0 : previous) + dayCount.transactionCount);
                if (dayCount.day < minDay) {
                    minDay = dayCount.day;
                }
                if (dayCount.day > maxDay) {
                    maxDay = dayCount.day;
                }
            }
        }
        long firstWeek = DateUtil.startOfWeek(minDay);
        long lastWeek = DateUtil.startOfWeek(maxDay);
        List<PeriodOption> options = new ArrayList<>();
        // 最近周期在前：从当前周向最早周倒序生成，addDays 走 Calendar 规避夏令时。
        for (long week = lastWeek; week >= firstWeek; week = DateUtil.addDays(week, -7)) {
            DateRange range = DateUtil.ofWeek(week);
            Integer count = countByStart.get(range.start);
            options.add(buildOption(PeriodType.WEEK, range, count == null ? 0 : count, context));
        }
        return options;
    }

    @NonNull
    private List<PeriodOption> buildMonthOptions(@Nullable List<DayCount> dayCounts) {
        Context context = getApplication();
        long today = DateUtil.today();
        Map<Integer, Integer> countByIndex = new HashMap<>();
        int minIndex = monthIndexOf(today);
        int maxIndex = monthIndexOf(today);
        if (dayCounts != null) {
            for (DayCount dayCount : dayCounts) {
                int index = monthIndexOf(dayCount.day);
                Integer previous = countByIndex.get(index);
                countByIndex.put(index,
                        (previous == null ? 0 : previous) + dayCount.transactionCount);
                if (index < minIndex) {
                    minIndex = index;
                }
                if (index > maxIndex) {
                    maxIndex = index;
                }
            }
        }
        List<PeriodOption> options = new ArrayList<>();
        for (int index = maxIndex; index >= minIndex; index--) {
            int year = Math.floorDiv(index, 12);
            int month = Math.floorMod(index, 12) + 1;
            DateRange range = DateUtil.ofMonth(year, month);
            Integer count = countByIndex.get(index);
            options.add(buildOption(PeriodType.MONTH, range, count == null ? 0 : count, context));
        }
        return options;
    }

    @NonNull
    private List<PeriodOption> buildYearOptions(@Nullable List<DayCount> dayCounts) {
        Context context = getApplication();
        int currentYear = DateUtil.yearOf(DateUtil.today());
        Map<Integer, Integer> countByYear = new HashMap<>();
        int minYear = currentYear;
        int maxYear = currentYear;
        if (dayCounts != null) {
            for (DayCount dayCount : dayCounts) {
                int year = DateUtil.yearOf(dayCount.day);
                Integer previous = countByYear.get(year);
                countByYear.put(year,
                        (previous == null ? 0 : previous) + dayCount.transactionCount);
                if (year < minYear) {
                    minYear = year;
                }
                if (year > maxYear) {
                    maxYear = year;
                }
            }
        }
        List<PeriodOption> options = new ArrayList<>();
        for (int year = maxYear; year >= minYear; year--) {
            DateRange range = DateUtil.ofYear(year);
            Integer count = countByYear.get(year);
            options.add(buildOption(PeriodType.YEAR, range, count == null ? 0 : count, context));
        }
        return options;
    }

    @NonNull
    private PeriodOption buildOption(@NonNull PeriodType type, @NonNull DateRange range,
                                     int count, @NonNull Context context) {
        PeriodOption option = new PeriodOption();
        option.type = type;
        option.start = range.start;
        option.end = range.end;
        option.transactionCount = count;
        option.title = DateLabels.periodTitle(context, range);
        option.subtitle = DateLabels.periodSubtitle(context, range);
        return option;
    }

    /** 年月的线性序号，便于按月比较与遍历：year * 12 + (month - 1)。 */
    private static int monthIndexOf(long dayMillis) {
        return DateUtil.yearOf(dayMillis) * 12 + (DateUtil.monthOf(dayMillis) - 1);
    }
}
