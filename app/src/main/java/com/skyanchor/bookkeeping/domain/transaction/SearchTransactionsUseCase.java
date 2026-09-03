package com.skyanchor.bookkeeping.domain.transaction;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.skyanchor.bookkeeping.data.model.SearchFilter;
import com.skyanchor.bookkeeping.data.model.SearchResult;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;

/**
 * 搜索交易用例（V2 新增，开发计划 Phase 4）。
 *
 * <p>把 {@link SearchFilter} 交给仓库的 SQL 查询，再把命中列表与其合计打包成 {@link SearchResult}，
 * 保证「顶部合计与下方列表同源」。合计复用 {@code StatisticsCalculator}，转账不计收支、仅计笔数。
 *
 * <p>返回的是 LiveData，随交易表变化自动刷新；{@link Transformations#map} 只在有观察者时才
 * 触发上游查询，符合 Room LiveData 的惰性约定。
 */
public class SearchTransactionsUseCase {

    private final BookkeepingRepository repository;

    public SearchTransactionsUseCase(@NonNull BookkeepingRepository repository) {
        this.repository = repository;
    }

    /** 按筛选条件搜索，返回随数据变化自动刷新的结果（列表 + 合计）。 */
    @NonNull
    public LiveData<SearchResult> search(@NonNull SearchFilter filter) {
        return Transformations.map(repository.searchTransactions(filter), SearchResult::of);
    }
}
