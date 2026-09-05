package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 同步游标（基线第 22 章）：记录该账号在服务器 change log 中的拉取水位。
 * 以账号 email 为主键——退出登录不清数据、换账号登录不串游标（见开发计划备注 8）。
 * 行存在即代表该账号已完成首次同步确认（bootstrap）。
 */
@Entity(tableName = "sync_cursor")
public class SyncCursorEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "account_email")
    public String accountEmail;

    @ColumnInfo(name = "last_change_id")
    public long lastChangeId;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
