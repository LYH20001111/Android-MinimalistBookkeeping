package com.skyanchor.bookkeeping.ui.account;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.model.RecordListItem;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.DateLabels;
import com.skyanchor.bookkeeping.util.DateUtil;
import com.skyanchor.bookkeeping.util.StatisticsCalculator;

import java.util.List;

/**
 * 账户流水详情页 ViewModel（V2 新增，开发计划 Phase 9）。
 *
 * <p>账户信息与该账户的流水（含转出 / 转入）都派生自仓库 LiveData，账单增删改、
 * 转账或编辑账户后余额与列表同源自动刷新。流水行复用记录页的
 * {@code StatisticsCalculator.groupByDay} 分组，标题文案同样走 {@link DateLabels}。
 */
public class AccountDetailViewModel extends AndroidViewModel {

    private final BookkeepingRepository repository;
    private final MutableLiveData<Long> accountId = new MutableLiveData<>();
    private final LiveData<AccountEntity> account;
    private final LiveData<List<RecordListItem>> rows;

    public AccountDetailViewModel(@NonNull Application application) {
        super(application);
        this.repository = BookkeepingApp.get(application).getRepository();
        this.account = Transformations.switchMap(accountId, repository::observeAccount);
        this.rows = Transformations.switchMap(accountId, id ->
                Transformations.map(repository.observeAccountTransactions(id), items ->
                        StatisticsCalculator.groupByDay(items,
                                DateLabels.dayLabels(application, DateUtil.today()))));
    }

    /** 装载指定账户；进入页面时调用一次。 */
    public void load(long id) {
        accountId.setValue(id);
    }

    public LiveData<AccountEntity> getAccount() {
        return account;
    }

    /** 日期分组后的流水行，空账户为空列表。 */
    public LiveData<List<RecordListItem>> getRows() {
        return rows;
    }
}
