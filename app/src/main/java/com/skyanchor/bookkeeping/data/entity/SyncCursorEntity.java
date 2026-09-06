package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

/**
 * 同步游标（基线第 22 章；V3.2 升级为 账号 + 账本 + 游标，基线第 10.2 章）。
 *
 * <p>复合主键 (account_email, ledger_sync_id)：每个账本一条游标，按账本隔离拉取；
 * 行存在即代表该账本在该账号下已完成首次同步确认（bootstrap）。
 */
@Entity(tableName = "sync_cursor", primaryKeys = {"account_email", "ledger_sync_id"})
public class SyncCursorEntity {

    @NonNull
    @ColumnInfo(name = "account_email")
    public String accountEmail;

    /** 所属账本的 syncId（基线第 10.2 章：account + ledger + cursor）。 */
    @NonNull
    @ColumnInfo(name = "ledger_sync_id")
    public String ledgerSyncId;

    @ColumnInfo(name = "last_change_id")
    public long lastChangeId;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    public SyncCursorEntity(@NonNull String accountEmail, @NonNull String ledgerSyncId) {
        this.accountEmail = accountEmail;
        this.ledgerSyncId = ledgerSyncId;
    }
}
