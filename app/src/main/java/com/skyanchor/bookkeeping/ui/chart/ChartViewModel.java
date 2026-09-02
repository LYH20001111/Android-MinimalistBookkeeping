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
import com.skyanchor.bookkeeping.data.model.BudgetState;
import com.skyanchor.bookkeeping.data.model.CategoryStat;
import com.skyanchor.bookkeeping.data.model.ChartUiState;
import com.skyanchor.bookkeeping.data.model.DateRange;
import com.skyanchor.bookkeeping.data.model.PeriodSummary;
import com.skyanchor.bookkeeping.data.model.PeriodType;
import com.skyanchor.bookkeeping.data.model.TrendPoint;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.DateLabels;
import com.skyanchor.bookkeeping.util.DateUtil;
import com.skyanchor.bookkeeping.util.StatisticsCalculator;

import java.util.ArrayList;
import java.util.List;

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
    }

    private final BookkeepingRepository repository;
    private final MutableLiveData<PeriodQuery> query;
    private final LiveData<ChartUiState> uiState;

    public ChartViewModel(@NonNull Application application) {
        super(application);
        this.repository = BookkeepingApp.get(application).getRepository();
        // 默认月视图：基线 7.2 中月视图信息最全（含预算），是最常用的落脚点
        this.query = new MutableLiveData<>(new PeriodQuery(PeriodType.MONTH, DateUtil.today()));
        this.uiState = Transformations.switchMap(query, this::observe);
    }

    public LiveData<ChartUiState> getUiState() {
        return uiState;
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

        return new ChartUiState(range, DateLabels.periodTitle(context, range), summary,
                previousSummary,
                StatisticsCalculator.changeAbsX10(summary.expense, previousSummary.expense),
                StatisticsCalculator.changeDirection(summary.expense, previousSummary.expense),
                trend, categoryStats, budgetState);
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
}
