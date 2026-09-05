package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 本地同步队列（基线第 23 章）：业务写成功后同事务入队，持久化、崩溃不丢。
 *
 * <p>队列行的语义是「该 syncId 存在待同步变更」；同一实体连续修改在非同步运行期
 * 会被 {@link com.skyanchor.bookkeeping.sync.SyncEnqueuer} 按 (entity_type, sync_id)
 * 合并成最终状态，但不丢失删除事件。Push 时以本地实体的**当前状态**构建载荷
 * （快照在发送前重读），因此队列行内不冗余业务字段。
 */
@Entity(
        tableName = "sync_change_queue",
        indices = {
                @Index(value = {"entity_type", "sync_id"}),
                @Index(value = "next_retry_at")
        })
public class SyncChangeQueueEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 实体类型常量，见 {@link SyncEntityTypes}。 */
    @NonNull
    @ColumnInfo(name = "entity_type")
    public String entityType;

    @NonNull
    @ColumnInfo(name = "sync_id")
    public String syncId;

    /** UPSERT / DELETE，见 {@link SyncEntityTypes}。 */
    @NonNull
    @ColumnInfo(name = "operation")
    public String operation;

    /** 入队时该实体的本地版本（即推送将携带的 baseVersion 下限，发送前重读校准）。 */
    @ColumnInfo(name = "base_version")
    public long baseVersion;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "retry_count")
    public int retryCount;

    @Nullable
    @ColumnInfo(name = "last_error")
    public String lastError;

    /** 早于该时间戳的队列项才会被本轮同步取出（指数退避落点）。 */
    @ColumnInfo(name = "next_retry_at")
    public long nextRetryAt;
}
