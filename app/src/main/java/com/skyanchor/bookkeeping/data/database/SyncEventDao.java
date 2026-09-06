package com.skyanchor.bookkeeping.data.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.skyanchor.bookkeeping.data.entity.SyncEventEntity;

import java.util.List;

/** 同步事件历史 DAO（V3.1 基线第 25 章）：保留最近 50 条摘要。 */
@Dao
public interface SyncEventDao {

    @Insert
    void insert(SyncEventEntity entity);

    /** 事件历史，新→旧。 */
    @Query("SELECT * FROM sync_events ORDER BY id DESC LIMIT 50")
    LiveData<List<SyncEventEntity>> observeRecent();

    @Query("SELECT * FROM sync_events ORDER BY id DESC LIMIT 50")
    List<SyncEventEntity> getRecent();

    @Query("SELECT COUNT(*) FROM sync_events")
    int count();

    /** 裁剪：只保留最近 50 条（按 id 保留新事件）。 */
    @Query("DELETE FROM sync_events WHERE id NOT IN "
            + "(SELECT id FROM sync_events ORDER BY id DESC LIMIT 50)")
    void trimToLimit();
}
