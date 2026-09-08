package com.skyanchor.bookkeeping.data.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.skyanchor.bookkeeping.data.entity.TransactionEditLogEntity;

import java.util.List;

/** 账单编辑日志读写。仅本机使用，不参与同步。 */
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
}
