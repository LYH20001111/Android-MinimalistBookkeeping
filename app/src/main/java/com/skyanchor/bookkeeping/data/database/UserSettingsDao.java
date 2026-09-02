package com.skyanchor.bookkeeping.data.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.skyanchor.bookkeeping.data.entity.UserSettingsEntity;

/**
 * 本地设置 DAO，单例记录。
 */
@Dao
public interface UserSettingsDao {

    @Query("SELECT * FROM user_settings WHERE id = 1")
    LiveData<UserSettingsEntity> observe();

    @Query("SELECT * FROM user_settings WHERE id = 1")
    UserSettingsEntity get();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(UserSettingsEntity entity);

    @Query("DELETE FROM user_settings")
    void deleteAll();
}
