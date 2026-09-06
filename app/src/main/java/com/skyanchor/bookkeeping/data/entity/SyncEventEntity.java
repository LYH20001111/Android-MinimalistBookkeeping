package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 同步事件历史（V3.1 基线第 25 章）：最近 N 轮同步的摘要，供高级诊断排障。
 * 只存诊断信息（时间 / 结果 / 计数 / 耗时 / 错误摘要），绝不存 Token 与密码。
 */
@Entity(tableName = "sync_events")
public class SyncEventEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 轮次开始时间（epoch millis）。 */
    @ColumnInfo(name = "started_at")
    public long startedAt;

    /** 轮次结束时间（epoch millis）。 */
    @ColumnInfo(name = "finished_at")
    public long finishedAt;

    /** 结果，取值见 {@link com.skyanchor.bookkeeping.sync.SyncCoordinator.Status}。
     *  非空约束与 MIGRATION_5_6 的 DDL（result TEXT NOT NULL）保持一致。 */
    @NonNull
    @ColumnInfo(name = "result")
    public String result = "";

    /** 本轮 Push 条数。 */
    @ColumnInfo(name = "push_count")
    public int pushCount;

    /** 本轮 Pull 应用条数。 */
    @ColumnInfo(name = "pull_count")
    public int pullCount;

    /** 本轮冲突条数。 */
    @ColumnInfo(name = "conflict_count")
    public int conflictCount;

    /** 轮次总耗时（毫秒）。 */
    @ColumnInfo(name = "duration_ms")
    public long durationMs;

    /** 失败时的错误摘要（人类可读，非原始异常）。 */
    @ColumnInfo(name = "error_message")
    public String errorMessage;
}
