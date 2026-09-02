package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 本地单例配置表，主键固定为 {@link #SINGLETON_ID}。
 */
@Entity(tableName = "user_settings")
public class UserSettingsEntity {

    /** 单例主键。 */
    public static final long SINGLETON_ID = 1L;

    /** 浅色主题。 */
    public static final String THEME_LIGHT = "light";
    /** 跟随系统。 */
    public static final String THEME_SYSTEM = "system";

    @PrimaryKey
    @ColumnInfo(name = "id")
    public long id = SINGLETON_ID;

    @NonNull
    @ColumnInfo(name = "theme")
    public String theme = THEME_LIGHT;

    @ColumnInfo(name = "first_launch")
    public boolean firstLaunch = true;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
