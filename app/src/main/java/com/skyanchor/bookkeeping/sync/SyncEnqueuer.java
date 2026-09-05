package com.skyanchor.bookkeeping.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.database.AppDatabase;
import com.skyanchor.bookkeeping.data.entity.SyncChangeQueueEntity;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;

/**
 * 同步入队器（基线第 23 章）。
 *
 * <p>业务写成功后（同一 DB 事务内）调用 {@link #enqueue} 标记「该 syncId 有待同步变更」。
 * 队列行只是一个脏标记：真正的推送载荷在同步时按实体**当前状态**重建，
 * 因此同一实体连续修改合并为最终状态、永不丢删除事件。
 *
 * <p>合并语义：非同步运行期间同键覆盖（保留最早 created_at 与重试计数）；
 * 同步运行期间的写入也走同一入口——ack 双重护栏（版本 + 内容一致）保证
 * 在途批次不会覆盖新修改（开发计划备注 6）。
 */
public class SyncEnqueuer {

    /** 队列变化回调：由 SyncScheduler 转成 3 秒防抖同步（基线第 9.3 章）。 */
    public interface Listener {
        void onPendingChangesEnqueued();
    }

    private final AppDatabase database;
    private final ExecutorService ioExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile Listener listener;

    public SyncEnqueuer(@NonNull AppDatabase database, @NonNull ExecutorService ioExecutor) {
        this.database = database;
        this.ioExecutor = ioExecutor;
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /**
     * 标记一条待同步变更。必须在数据库事务内调用（与业务写同事务，保证原子性）。
     *
     * @param operation UPSERT / DELETE（诊断信息，真实操作以推送时实体状态为准）
     */
    public void enqueue(@NonNull String entityType, @NonNull String syncId,
                        @NonNull String operation, long baseVersion) {
        if (syncId == null || syncId.isEmpty()) {
            // 无身份的行不进队列（同步前的修复流程会先补 syncId）
            return;
        }
        long now = System.currentTimeMillis();
        SyncChangeQueueEntity existing =
                database.syncChangeQueueDao().find(entityType, syncId);
        if (existing == null) {
            SyncChangeQueueEntity entity = new SyncChangeQueueEntity();
            entity.entityType = entityType;
            entity.syncId = syncId;
            entity.operation = operation;
            entity.baseVersion = baseVersion;
            entity.createdAt = now;
            entity.retryCount = 0;
            entity.nextRetryAt = 0;
            database.syncChangeQueueDao().upsert(entity);
        } else {
            // 同键合并：覆盖操作与版本，保留创建时间与退避计数
            existing.operation = operation;
            existing.baseVersion = baseVersion;
            existing.nextRetryAt = 0;
            database.syncChangeQueueDao().upsert(existing);
        }
    }

    /** 事务提交后调用：通知调度器「有新变更待同步」（触发 3 秒防抖）。 */
    public void notifyPendingChanges() {
        Listener current = listener;
        if (current == null) {
            return;
        }
        mainHandler.post(current::onPendingChangesEnqueued);
    }

    /** 便于在 IO 线程直接投递（同步流程内部使用）。 */
    public void postToIo(@NonNull Runnable task) {
        ioExecutor.execute(task);
    }
}
