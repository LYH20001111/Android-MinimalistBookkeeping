package com.skyanchor.bookkeeping.ui.importexport;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.model.BackupResult;
import com.skyanchor.bookkeeping.domain.importexport.BackupUseCase;

/**
 * 本地备份页 ViewModel（V2 新增，开发计划 Phase 7）。
 *
 * <p>与数据导出页同范式：账单总数来自仓库 LiveData；备份委托 {@link BackupUseCase}，
 * {@link #busy} 表达进行态、{@link #result} 承载一次性结果并被界面消费后清空，
 * 备份过程中转屏不丢回调、不重复弹窗。
 */
public class BackupViewModel extends AndroidViewModel {

    private final BackupUseCase backupUseCase;
    private final LiveData<Integer> transactionCount;
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    private final MutableLiveData<BackupResult> result = new MutableLiveData<>();

    public BackupViewModel(@NonNull Application application) {
        super(application);
        BookkeepingApp app = BookkeepingApp.get(application);
        this.backupUseCase = app.getBackupUseCase();
        this.transactionCount = app.getRepository().observeTransactionCount();
    }

    /** 当前账单总数，供「共 N 笔」展示，并在为 0 时提示无可备份内容。 */
    public LiveData<Integer> getTransactionCount() {
        return transactionCount;
    }

    public LiveData<Boolean> isBusy() {
        return busy;
    }

    public LiveData<BackupResult> getResult() {
        return result;
    }

    /** 备份全部本地数据到 SAF Uri；进行中忽略重复触发。 */
    public void backup(@NonNull Uri uri) {
        if (Boolean.TRUE.equals(busy.getValue())) {
            return;
        }
        busy.setValue(true);
        backupUseCase.backup(uri, backed -> {
            busy.setValue(false);
            result.setValue(backed);
        });
    }

    /** 结果被界面消费后清空，避免旋转重建时重复弹窗。 */
    public void consumeResult() {
        result.setValue(null);
    }
}
