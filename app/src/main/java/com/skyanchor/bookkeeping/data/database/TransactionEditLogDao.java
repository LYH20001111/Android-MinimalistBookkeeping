package com.skyanchor.bookkeeping.data.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.skyanchor.bookkeeping.data.entity.TransactionEditLogEntity;

import java.util.List;

/** 账单编辑日志读写。不参与云同步（V4.1 起随本地备份 / 恢复走）。 */
@Dao
public interface TransactionEditLogDao {

    @Insert
    void insert(TransactionEditLogEntity entity);

    /** 某账单的全部编辑日志，最新在前。 */
    @Query("SELECT * FROM transaction_edit_log WHERE transaction_id = :transactionId "
            + "ORDER BY changed_at DESC, id DESC")
    List<TransactionEditLogEntity> getByTransaction(long transactionId);

    /** 清空全部日志。数据被整体覆盖恢复（旧日志与恢复后的内容不再对应）时使用。 */
    @Query("DELETE FROM transaction_edit_log")
    void deleteAll();

    /** 备份用：当前账本全部有效账单的编辑日志，V4.1 起随备份走。仅在 IO 线程调用。 */
    @Query("SELECT l.* FROM transaction_edit_log l "
            + "JOIN transactions t ON t.id = l.transaction_id "
            + "WHERE t.is_deleted = 0 "
            + "AND t.ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) "
            + "ORDER BY l.transaction_id, l.id")
    List<TransactionEditLogEntity> getBackupRows();
}
