package com.skyanchor.bookkeeping.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 同步状态单例（基线第 23.1 章）：进程重启后 UI 仍可恢复展示。
 * 实时状态以 {@link com.skyanchor.bookkeeping.sync.SyncCoordinator} 内存值为准，
 * 本表是「最后已知状态」的持久化快照。
 */
@Entity(tableName = "sync_state")
public class SyncStateEntity {

    public static final long SINGLETON_ID = 1;

    @PrimaryKey
    public long id = SINGLETON_ID;

    /** 云端同步开关（基线第 7 章）：关闭 = 暂停本设备同步，不动数据、不退登录。 */
    @ColumnInfo(name = "sync_enabled")
    public boolean syncEnabled;

    /** 最后已知状态，取值见 SyncCoordinator.Status。 */
    @ColumnInfo(name = "status")
    public String status = "IDLE";

    @ColumnInfo(name = "last_sync_at")
    public long lastSyncAt;

    /** 人类可读的最后一次错误摘要（仅用于展示，非 HTTP 原文）。 */
    @ColumnInfo(name = "last_error")
    public String lastError;

    /** 最近一次同步发生的冲突条数。 */
    @ColumnInfo(name = "conflict_count")
    public int conflictCount;
}
