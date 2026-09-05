package com.skyanchor.bookkeeping.data.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.skyanchor.bookkeeping.data.entity.SyncStateEntity;

/** 同步状态单例 DAO：UI 恢复用的持久化快照。 */
@Dao
public interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE id = 1 LIMIT 1")
    SyncStateEntity get();

    @Query("SELECT * FROM sync_state WHERE id = 1 LIMIT 1")
    LiveData<SyncStateEntity> observe();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(SyncStateEntity entity);
}
