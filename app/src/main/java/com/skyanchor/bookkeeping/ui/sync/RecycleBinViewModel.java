package com.skyanchor.bookkeeping.ui.sync;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.Callback;

import java.util.List;

/**
 * 回收站 ViewModel（V3.1 基线第 18-20 章）：分类型展示软删数据，
 * 恢复 = 反转软删位并作为 UPSERT 重新入队同步（跨设备最终一致）。
 */
public class RecycleBinViewModel extends AndroidViewModel {

    private final BookkeepingRepository repository;

    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private final MutableLiveData<Boolean> restored = new MutableLiveData<>();

    public RecycleBinViewModel(@NonNull Application application) {
        super(application);
        repository = BookkeepingApp.get(application).getRepository();
    }

    public LiveData<List<TransactionEntity>> transactions() {
        return repository.observeRecycleBinTransactions();
    }

    public LiveData<List<CategoryEntity>> categories() {
        return repository.observeRecycleBinCategories();
    }

    public LiveData<List<AccountEntity>> accounts() {
        return repository.observeRecycleBinAccounts();
    }

    public LiveData<List<RecurringTransactionEntity>> recurring() {
        return repository.observeRecycleBinRecurring();
    }

    public LiveData<Boolean> busy() {
        return busy;
    }

    public LiveData<String> error() {
        return error;
    }

    public LiveData<Boolean> restored() {
        return restored;
    }

    public void restoreTransaction(long id) {
        busy.setValue(true);
        repository.restoreTransaction(id, new Callback<Boolean>() {
            @Override
            public void onResult(Boolean result) {
                busy.setValue(false);
                restored.setValue(result != null && result);
            }

            @Override
            public void onError(@NonNull Exception e) {
                busy.setValue(false);
                error.setValue(e.getMessage());
            }
        });
    }

    public void restoreCategory(long id) {
        busy.setValue(true);
        repository.restoreCategory(id, new Callback<Boolean>() {
            @Override
            public void onResult(Boolean result) {
                busy.setValue(false);
                restored.setValue(result != null && result);
            }

            @Override
            public void onError(@NonNull Exception e) {
                busy.setValue(false);
                error.setValue(e.getMessage());
            }
        });
    }

    public void restoreAccount(long id) {
        busy.setValue(true);
        repository.restoreAccount(id, new Callback<Boolean>() {
            @Override
            public void onResult(Boolean result) {
                busy.setValue(false);
                restored.setValue(result != null && result);
            }

            @Override
            public void onError(@NonNull Exception e) {
                busy.setValue(false);
                error.setValue(e.getMessage());
            }
        });
    }

    public void restoreRecurring(long id) {
        busy.setValue(true);
        repository.restoreRecurring(id, new Callback<Boolean>() {
            @Override
            public void onResult(Boolean result) {
                busy.setValue(false);
                restored.setValue(result != null && result);
            }

            @Override
            public void onError(@NonNull Exception e) {
                busy.setValue(false);
                error.setValue(e.getMessage());
            }
        });
    }
}
