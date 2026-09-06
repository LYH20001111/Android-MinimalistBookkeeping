package com.skyanchor.bookkeeping.data.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.skyanchor.bookkeeping.data.entity.SyncCursorEntity;

/** 同步游标 DAO：按 账号 + 账本 隔离（V3.2 基线第 10.2 章），行存在 = 该账本已完成首次同步。 */
@Dao
public interface SyncCursorDao {

    @Query("SELECT * FROM sync_cursor WHERE account_email = :email AND ledger_sync_id = :ledgerSyncId LIMIT 1")
    SyncCursorEntity find(String email, String ledgerSyncId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(SyncCursorEntity entity);

    @Query("DELETE FROM sync_cursor WHERE account_email = :email AND ledger_sync_id = :ledgerSyncId")
    void clear(String email, String ledgerSyncId);

    /** 账本身份合并（默认账本 claim 被 mergedInto）后迁移游标键。 */
    @Query("UPDATE sync_cursor SET ledger_sync_id = :toLedgerSyncId, updated_at = :updatedAt "
            + "WHERE account_email = :email AND ledger_sync_id = :fromLedgerSyncId")
    void renameKey(String email, String fromLedgerSyncId, String toLedgerSyncId, long updatedAt);

    @Query("DELETE FROM sync_cursor")
    void clearAll();

    @Query("SELECT COUNT(*) FROM sync_cursor WHERE account_email = :email")
    int countForEmail(String email);
}
