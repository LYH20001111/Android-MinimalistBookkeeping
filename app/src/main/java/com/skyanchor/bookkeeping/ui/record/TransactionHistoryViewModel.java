package com.skyanchor.bookkeeping.ui.record;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.entity.TransactionEditLogEntity;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;

import java.util.List;

/**
 * 编辑记录页 ViewModel：按账单 id 读取本机编辑日志（创建 + 每次修改的字段级变更）。
 *
 * <p>日志只在本机生成（见 {@link BookkeepingRepository} 的编辑日志一节），
 * 功能上线前已存在的账单查无日志行，界面以空态「暂无编辑记录」兼容。
 */
public class TransactionHistoryViewModel extends AndroidViewModel {

    private final BookkeepingRepository repository;
    private final MutableLiveData<List<TransactionEditLogEntity>> logs = new MutableLiveData<>();

    public TransactionHistoryViewModel(@NonNull Application application) {
        super(application);
        repository = BookkeepingApp.get(application).getRepository();
    }

    public LiveData<List<TransactionEditLogEntity>> logs() {
        return logs;
    }

    public void load(long transactionId) {
        repository.loadEditLogs(transactionId, logs::setValue);
    }
}
