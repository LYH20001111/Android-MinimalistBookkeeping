package com.skyanchor.bookkeeping.ui.importexport;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.model.BackupResult;
import com.skyanchor.bookkeeping.data.model.RestoreResult;
import com.skyanchor.bookkeeping.domain.importexport.BackupUseCase;
import com.skyanchor.bookkeeping.domain.importexport.RestoreUseCase;

/**
 * 本地恢复页 ViewModel（V2 新增，开发计划 Phase 7）。
 *
 * <p>编排「覆盖恢复」流程，并承担「恢复前可选先自动备份当前数据」：
 * <ol>
 *   <li>用户选择备份文件 → 界面弹覆盖确认；</li>
 *   <li>用户可选「先备份当前数据」→ {@link #setPendingRestore} 记下待恢复文件，
 *       备份完成后界面再确认一次，{@link #restore} 才真正执行；</li>
 *   <li>直接「仍要恢复」→ 立即 {@link #restore}。</li>
 * </ol>
 * {@link #busy} 统一表达备份 / 恢复进行态；结果被消费后清空，旋转重建不重复弹窗。
 * 待恢复 Uri 存在 ViewModel 字段里，短链路转屏不丢失。
 */
public class RestoreViewModel extends AndroidViewModel {

    private final BackupUseCase backupUseCase;
    private final RestoreUseCase restoreUseCase;
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    private final MutableLiveData<BackupResult> backupResult = new MutableLiveData<>();
    private final MutableLiveData<RestoreResult> restoreResult = new MutableLiveData<>();

    @Nullable
    private Uri pendingRestoreUri;

    public RestoreViewModel(@NonNull Application application) {
        super(application);
        BookkeepingApp app = BookkeepingApp.get(application);
        this.backupUseCase = app.getBackupUseCase();
        this.restoreUseCase = app.getRestoreUseCase();
    }

    public LiveData<Boolean> isBusy() {
        return busy;
    }

    public LiveData<BackupResult> getBackupResult() {
        return backupResult;
    }

    public LiveData<RestoreResult> getRestoreResult() {
        return restoreResult;
    }

    /** 是否有待执行的恢复（用户在确认弹窗里选了「先备份当前数据」）。 */
    public boolean hasPendingRestore() {
        return pendingRestoreUri != null;
    }

    @Nullable
    public Uri getPendingRestoreUri() {
        return pendingRestoreUri;
    }

    public void setPendingRestore(@NonNull Uri uri) {
        pendingRestoreUri = uri;
    }

    public void clearPendingRestore() {
        pendingRestoreUri = null;
    }

    /** 恢复前先把当前数据备份到 SAF Uri；进行中忽略重复触发。 */
    public void backupFirst(@NonNull Uri uri) {
        if (Boolean.TRUE.equals(busy.getValue())) {
            return;
        }
        busy.setValue(true);
        backupUseCase.backup(uri, backed -> {
            busy.setValue(false);
            backupResult.setValue(backed);
        });
    }

    /** 覆盖恢复所选备份文件；进行中忽略重复触发。 */
    public void restore(@NonNull Uri uri) {
        if (Boolean.TRUE.equals(busy.getValue())) {
            return;
        }
        busy.setValue(true);
        restoreUseCase.restore(uri, restored -> {
            busy.setValue(false);
            restoreResult.setValue(restored);
        });
    }

    /** 备份结果被界面消费后清空，避免旋转重建时重复弹窗。 */
    public void consumeBackupResult() {
        backupResult.setValue(null);
    }

    /** 恢复结果被界面消费后清空，避免旋转重建时重复弹窗。 */
    public void consumeRestoreResult() {
        restoreResult.setValue(null);
    }
}
