package com.skyanchor.bookkeeping.ui.record;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.data.model.PeriodSummary;
import com.skyanchor.bookkeeping.data.model.RecordListItem;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.Callback;
import com.skyanchor.bookkeeping.util.DateLabels;
import com.skyanchor.bookkeeping.util.DateUtil;
import com.skyanchor.bookkeeping.util.StatisticsCalculator;

import java.util.List;

/**
 * 记录页 ViewModel。
 *
 * <p>业务日期存在这里，因此切 Tab（add/hide/show）或旋转屏幕都不会丢失；
 * 进程被回收后重建时回到今天，符合「默认当前业务日期」的约定。
 *
 * <p>列表与当天概览都由同一份 LiveData 派生，保证新增/编辑/删除后
 * 概览数字与账单列表同时刷新（V1 基线第 11 章统计一致性）。
 */
public class RecordViewModel extends AndroidViewModel {

    private final BookkeepingRepository repository;
    private final MutableLiveData<Long> businessDate;
    private final LiveData<RecordUiState> uiState;

    public RecordViewModel(@NonNull Application application) {
        super(application);
        this.repository = BookkeepingApp.get(application).getRepository();
        this.businessDate = new MutableLiveData<>(DateUtil.today());
        this.uiState = Transformations.switchMap(businessDate,
                date -> Transformations.map(repository.observeTransactionsUpTo(date),
                        items -> buildState(date, items)));
    }

    private RecordUiState buildState(long date, @Nullable List<TransactionItem> items) {
        Context context = getApplication();
        PeriodSummary daySummary = StatisticsCalculator.summary(items, date, date);
        List<RecordListItem> rows =
                StatisticsCalculator.groupByDay(items, DateLabels.dayLabels(context, date));
        return new RecordUiState(date, DateLabels.businessDateLabel(context, date), daySummary, rows);
    }

    public LiveData<RecordUiState> getUiState() {
        return uiState;
    }

    public LiveData<Long> getBusinessDate() {
        return businessDate;
    }

    /** 设置业务日期，传入任意时刻都会归一到当天 00:00。 */
    public void setBusinessDate(long dayMillis) {
        long day = DateUtil.startOfDay(dayMillis);
        Long current = businessDate.getValue();
        if (current == null || current != day) {
            businessDate.setValue(day);
        }
    }

    public void deleteTransaction(long id, @Nullable Callback<Boolean> callback) {
        repository.deleteTransaction(id, callback);
    }
}
