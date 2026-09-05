package com.skyanchor.bookkeeping.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * WorkManager 重试入口（基线第 28 章）：进程被杀后退避到期仍能续跑。
 * 同步真值与队列在 Room，这里只是触发器，不携带任何业务数据。
 */
public class SyncWorker extends Worker {

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        SyncScheduler.requestSyncNow();
        return Result.success();
    }
}
