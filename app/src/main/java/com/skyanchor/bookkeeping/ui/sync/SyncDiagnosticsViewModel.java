package com.skyanchor.bookkeeping.ui.sync;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.skyanchor.bookkeeping.data.database.AppDatabase;
import com.skyanchor.bookkeeping.data.entity.SyncEventEntity;
import com.skyanchor.bookkeeping.data.entity.SyncStateEntity;

import java.util.List;

/**
 * 高级诊断 ViewModel（V3.1 基线第 24/25 章）：持久化快照 + 事件历史。
 * 全部来自本地 sync_state / sync_events，不含任何 Token / 密码（基线第 45 章）。
 */
public class SyncDiagnosticsViewModel extends AndroidViewModel {

    private final AppDatabase database;

    public SyncDiagnosticsViewModel(@NonNull Application application) {
        super(application);
        database = AppDatabase.getInstance(application);
    }

    public LiveData<SyncStateEntity> syncState() {
        return database.syncStateDao().observe();
    }

    public LiveData<List<SyncEventEntity>> events() {
        return database.syncEventDao().observeRecent();
    }
}
