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

    // ===== V3.1 同步诊断（基线第 23/24 章）=====

    /** 最近一轮 Push 完成时间（epoch millis）；0 = 从未。 */
    @ColumnInfo(name = "last_push_at", defaultValue = "0")
    public long lastPushAt;

    /** 最近一轮 Pull 完成时间（epoch millis）；0 = 从未。 */
    @ColumnInfo(name = "last_pull_at", defaultValue = "0")
    public long lastPullAt;

    /** 最近一轮 Push 条数。 */
    @ColumnInfo(name = "last_push_count", defaultValue = "0")
    public int lastPushCount;

    /** 最近一轮 Pull 应用条数。 */
    @ColumnInfo(name = "last_pull_count", defaultValue = "0")
    public int lastPullCount;

    /** 最近一轮同步总耗时（毫秒）。 */
    @ColumnInfo(name = "last_duration_ms", defaultValue = "0")
    public long lastDurationMs;

    /** 已识别的服务器恢复代际；与服务器不一致时触发游标重置重新收敛。 */
    @ColumnInfo(name = "recovery_epoch", defaultValue = "0")
    public long recoveryEpoch;

    /** 本地账本绑定的云同步账号（基线第 30 章）；空 = LOCAL_ONLY。 */
    @ColumnInfo(name = "bound_account_email")
    public String boundAccountEmail;

    /** 服务器恢复被本机识别的时间（epoch millis）；非 0 时同步中心显示“服务器已恢复”横幅，确认后清 0。 */
    @ColumnInfo(name = "recovered_at", defaultValue = "0")
    public long recoveredAt;
}
