package com.skyanchor.bookkeeping.ui.importexport;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.model.ImportCommitResult;
import com.skyanchor.bookkeeping.data.model.ImportPreview;
import com.skyanchor.bookkeeping.domain.importexport.ImportTransactionsUseCase;

/**
 * 数据导入页 ViewModel（V2 新增，开发计划 Phase 5）。
 *
 * <p>编排两段式导入：{@link #loadPreview} 解析文件生成预览，{@link #commit} 在用户确认后批量写入。
 * {@link #busy} 统一表达「解析中 / 导入中」进行态；{@link #preview} 与 {@link #commitResult}
 * 承载结果。ViewModel 跨旋转存活，解析大文件或导入过程中转屏不丢回调；提交结果被界面消费后
 * 调用 {@link #consumeCommitResult} 清空，避免重建时重复弹窗。
 */
public class ImportViewModel extends AndroidViewModel {

    private final ImportTransactionsUseCase importUseCase;
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    private final MutableLiveData<ImportPreview> preview = new MutableLiveData<>();
    private final MutableLiveData<ImportCommitResult> commitResult = new MutableLiveData<>();

    public ImportViewModel(@NonNull Application application) {
        super(application);
        this.importUseCase = BookkeepingApp.get(application).getImportTransactionsUseCase();
    }

    public LiveData<Boolean> isBusy() {
        return busy;
    }

    public LiveData<ImportPreview> getPreview() {
        return preview;
    }

    public LiveData<ImportCommitResult> getCommitResult() {
        return commitResult;
    }

    @Nullable
    public ImportPreview currentPreview() {
        return preview.getValue();
    }

    /** 解析 SAF Uri 的 CSV 生成预览；进行中忽略重复触发。 */
    public void loadPreview(@NonNull Uri uri) {
        if (Boolean.TRUE.equals(busy.getValue())) {
            return;
        }
        busy.setValue(true);
        importUseCase.preview(uri, parsed -> {
            busy.setValue(false);
            preview.setValue(parsed);
        });
    }

    /** 提交当前预览的有效行；无有效行或进行中则忽略。 */
    public void commit() {
        ImportPreview current = preview.getValue();
        if (current == null || !current.hasValid() || Boolean.TRUE.equals(busy.getValue())) {
            return;
        }
        busy.setValue(true);
        importUseCase.commit(current, result -> {
            busy.setValue(false);
            commitResult.setValue(result);
        });
    }

    /** 提交结果被界面消费后清空，避免旋转重建时重复弹窗。 */
    public void consumeCommitResult() {
        commitResult.setValue(null);
    }
}
