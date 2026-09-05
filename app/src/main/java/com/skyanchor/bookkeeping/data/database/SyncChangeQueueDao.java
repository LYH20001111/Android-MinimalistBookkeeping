package com.skyanchor.bookkeeping.data.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.skyanchor.bookkeeping.data.entity.SyncChangeQueueEntity;

import java.util.List;

/** 本地同步队列 DAO（基线第 23 章）：持久化队列，崩溃 / 重启后待上传变更不丢。 */
@Dao
public interface SyncChangeQueueDao {

    @Query("SELECT * FROM sync_change_queue "
            + "WHERE entity_type = :entityType AND sync_id = :syncId LIMIT 1")
    SyncChangeQueueEntity find(String entityType, String syncId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(SyncChangeQueueEntity entity);

    @Query("SELECT * FROM sync_change_queue WHERE next_retry_at <= :now "
            + "ORDER BY created_at ASC LIMIT :limit")
    List<SyncChangeQueueEntity> takeDue(long now, int limit);

    @Query("SELECT COUNT(DISTINCT entity_type || '|' || sync_id) FROM sync_change_queue")
    LiveData<Integer> observePendingCount();

    @Query("SELECT COUNT(DISTINCT entity_type || '|' || sync_id) FROM sync_change_queue")
    int pendingCount();

    @Query("DELETE FROM sync_change_queue "
            + "WHERE entity_type = :entityType AND sync_id = :syncId")
    void clearFor(String entityType, String syncId);

    @Query("UPDATE sync_change_queue SET retry_count = :retryCount, last_error = :lastError, "
            + "next_retry_at = :nextRetryAt WHERE entity_type = :entityType AND sync_id = :syncId")
    void markFailed(String entityType, String syncId, int retryCount, String lastError,
                    long nextRetryAt);

    @Query("DELETE FROM sync_change_queue")
    void clearAll();
}
