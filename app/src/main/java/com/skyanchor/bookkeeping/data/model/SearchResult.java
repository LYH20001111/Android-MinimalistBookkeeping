package com.skyanchor.bookkeeping.data.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.util.StatisticsCalculator;

import java.util.Collections;
import java.util.List;

/**
 * 搜索结果（V2 新增，开发计划 Phase 4）：命中列表 + 与列表同源的合计。
 *
 * <p>合计复用 {@link StatisticsCalculator#summary}，转账既不计收入也不计支出、但计入笔数，
 * 因此顶部「共 N 笔 / 支出 / 收入」与下方列表来自同一份数据，绝不互相矛盾
 * （对齐开发计划 Phase 4 验收「结果统计与列表一致」）。
 */
public final class SearchResult {

    /** 空结果：无命中、合计全 0。 */
    public static final SearchResult EMPTY =
            new SearchResult(Collections.<TransactionItem>emptyList(), PeriodSummary.EMPTY);

    /** 命中的账单，已按 date DESC, time DESC 排序，可直接喂给 TransactionListAdapter。 */
    @NonNull
    public final List<TransactionItem> items;

    /** 命中账单的合计：count 含转账，income / expense 不含转账。 */
    @NonNull
    public final PeriodSummary summary;

    public SearchResult(@NonNull List<TransactionItem> items, @NonNull PeriodSummary summary) {
        this.items = items;
        this.summary = summary;
    }

    /**
     * 由命中列表构造，合计覆盖全部条目。
     *
     * <p>列表已被 {@code TransactionDao.search} 按筛选条件（含日期区间）收窄，故这里用
     * 全区间 {@code [Long.MIN_VALUE, Long.MAX_VALUE]} 汇总，避免二次按日期裁剪。
     */
    @NonNull
    public static SearchResult of(@Nullable List<TransactionItem> items) {
        List<TransactionItem> list =
                items == null ? Collections.<TransactionItem>emptyList() : items;
        PeriodSummary summary =
                StatisticsCalculator.summary(list, Long.MIN_VALUE, Long.MAX_VALUE);
        return new SearchResult(list, summary);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
