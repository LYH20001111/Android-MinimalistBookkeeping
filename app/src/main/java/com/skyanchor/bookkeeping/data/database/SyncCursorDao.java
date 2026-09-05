package com.skyanchor.bookkeeping.data.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.skyanchor.bookkeeping.data.entity.SyncCursorEntity;

/** 同步游标 DAO：按账号隔离（开发计划备注 8），行存在 = 该账号已完成首次同步。 */
@Dao
public interface SyncCursorDao {

    @Query("SELECT * FROM sync_cursor WHERE account_email = :email LIMIT 1")
    SyncCursorEntity find(String email);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(SyncCursorEntity entity);

    @Query("DELETE FROM sync_cursor")
    void clearAll();
}
