package com.skyanchor.bookkeeping.ui.search;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.model.SearchFilter;
import com.skyanchor.bookkeeping.data.model.SearchResult;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.domain.transaction.SearchTransactionsUseCase;
import com.skyanchor.bookkeeping.util.Callback;

import java.util.List;

/**
 * 搜索页 ViewModel（V2 新增，开发计划 Phase 4）。
 *
 * <p>筛选条件存在 {@link MutableLiveData} 里，任何一次修改都通过 {@code switchMap} 触发一次新的
 * DAO 查询，结果（列表 + 合计）随底层交易表变化自动刷新——因此在搜索结果里编辑 / 删除账单后，
 * 列表与顶部合计会同步更新。
 *
 * <p>ViewModel 只做状态编排：关键词 / 类型 / 分类 / 账户 / 金额的收窄规则都落在不可变的
 * {@link SearchFilter} 上，业务查询交给 {@link SearchTransactionsUseCase}。
 */
public class SearchViewModel extends AndroidViewModel {

    private final BookkeepingRepository repository;
    private final SearchTransactionsUseCase searchUseCase;

    private final MutableLiveData<SearchFilter> filter =
            new MutableLiveData<>(SearchFilter.all());
    private final LiveData<SearchResult> results;
    private final LiveData<List<CategoryEntity>> categories;
    private final LiveData<List<AccountEntity>> accounts;

    public SearchViewModel(@NonNull Application application) {
        super(application);
        BookkeepingApp app = BookkeepingApp.get(application);
        this.repository = app.getRepository();
        this.searchUseCase = app.getSearchTransactionsUseCase();
        this.results = Transformations.switchMap(filter, searchUseCase::search);
        // 分类候选含支出与收入两类；账户候选含已归档账户（历史账单可能落在归档账户上，仍需可筛）。
        this.categories = repository.observeAllCategories();
        this.accounts = repository.observeAccounts();
    }

    @NonNull
    public LiveData<SearchResult> getResults() {
        return results;
    }

    @NonNull
    public LiveData<SearchFilter> getFilter() {
        return filter;
    }

    @NonNull
    public LiveData<List<CategoryEntity>> getCategories() {
        return categories;
    }

    @NonNull
    public LiveData<List<AccountEntity>> getAccounts() {
        return accounts;
    }

    public void setKeyword(@Nullable String keyword) {
        update(current().toBuilder().keyword(keyword));
    }

    /** 设置包含的交易类型；UI 在「无 chip 选中」时应传全 true 表示不限类型。 */
    public void setTypes(boolean expense, boolean income, boolean transfer) {
        update(current().toBuilder().types(expense, income, transfer));
    }

    public void setCategoryId(long categoryId) {
        update(current().toBuilder().categoryId(categoryId));
    }

    public void setAccountId(long accountId) {
        update(current().toBuilder().accountId(accountId));
    }

    public void setAmountRange(long minCents, long maxCents) {
        update(current().toBuilder().amountRange(minCents, maxCents));
    }

    /** 清空全部筛选，回到「全不限制」。 */
    public void reset() {
        update(SearchFilter.all().toBuilder());
    }

    /** 删除搜索结果里的账单；结果 LiveData 会在删除后自动刷新。 */
    public void deleteTransaction(long id, @Nullable Callback<Boolean> callback) {
        repository.deleteTransaction(id, callback);
    }

    /** 当前筛选条件，永不为 null（构造时已初始化为全不限制）。 */
    @NonNull
    private SearchFilter current() {
        SearchFilter value = filter.getValue();
        return value == null ? SearchFilter.all() : value;
    }

    private void update(@NonNull SearchFilter.Builder builder) {
        SearchFilter next = builder.build();
        if (!next.equals(filter.getValue())) {
            filter.setValue(next);
        }
    }
}
