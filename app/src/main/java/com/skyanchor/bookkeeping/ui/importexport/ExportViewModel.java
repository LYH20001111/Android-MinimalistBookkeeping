package com.skyanchor.bookkeeping.ui.importexport;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.model.ExportResult;
import com.skyanchor.bookkeeping.domain.importexport.ExportTransactionsUseCase;

/**
 * 数据导出页 ViewModel（V2 新增，开发计划 Phase 5）。
 *
 * <p>只做状态编排：账单总数来自仓库 LiveData；导出委托 {@link ExportTransactionsUseCase}，
 * 用 {@link #exporting} 表达进行态、{@link #result} 承载一次性结果。ViewModel 跨旋转存活，
 * 因此导出过程中转屏不会丢失回调；结果被界面消费后调用 {@link #consumeResult} 清空，
 * 避免重建时重复弹窗。
 */
public class ExportViewModel extends AndroidViewModel {

    private final ExportTransactionsUseCase exportUseCase;
    private final LiveData<Integer> transactionCount;
    private final MutableLiveData<Boolean> exporting = new MutableLiveData<>(false);
    private final MutableLiveData<ExportResult> result = new MutableLiveData<>();

    public ExportViewModel(@NonNull Application application) {
        super(application);
        BookkeepingApp app = BookkeepingApp.get(application);
        this.exportUseCase = app.getExportTransactionsUseCase();
        this.transactionCount = app.getRepository().observeTransactionCount();
    }

    /** 当前账单总数，供「共 N 笔可导出」展示，并在为空时禁用导出。 */
    public LiveData<Integer> getTransactionCount() {
        return transactionCount;
    }

    public LiveData<Boolean> isExporting() {
        return exporting;
    }

    public LiveData<ExportResult> getResult() {
        return result;
    }

    /** 导出全量账单到 SAF Uri；进行中忽略重复触发。 */
    public void export(@NonNull Uri uri) {
        if (Boolean.TRUE.equals(exporting.getValue())) {
            return;
        }
        exporting.setValue(true);
        exportUseCase.export(uri, exported -> {
            exporting.setValue(false);
            result.setValue(exported);
        });
    }

    /** 结果被界面消费后清空，避免旋转重建时重复弹窗。 */
    public void consumeResult() {
        result.setValue(null);
    }
}
