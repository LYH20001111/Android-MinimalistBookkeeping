package com.skyanchor.bookkeeping.data.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.skyanchor.bookkeeping.data.entity.RefundRecordEntity;

import java.util.List;

/**
 * 退款流水 DAO（V4.0）。
 *
 * <p>账单上的两个累计金额（{@code refunded_amount} / {@code pending_refund_amount}）
 * 一律由 {@link #sumReceived(long)} 与 {@link #sumPending(long)} 从本表重算得出，
 * 不依赖增量加减，避免异常路径下累计额与流水漂移。
 *
 * <p>退款记录不做物理删除：撤销只改 {@code state}，删除账单只软删，回收站恢复再置回。
 */
@Dao
public interface RefundRecordDao {

    /** 某账单的退款流水，发起时间新→旧，供退款记录页与编辑页使用。 */
    @Query("SELECT * FROM refund_record WHERE is_deleted = 0 AND transaction_id = :transactionId "
            + "ORDER BY COALESCE(received_at, requested_at) DESC, id DESC")
    LiveData<List<RefundRecordEntity>> observeByTransaction(long transactionId);

    @Query("SELECT * FROM refund_record WHERE is_deleted = 0 AND transaction_id = :transactionId "
            + "ORDER BY COALESCE(received_at, requested_at) DESC, id DESC")
    List<RefundRecordEntity> getByTransaction(long transactionId);

    @Query("SELECT * FROM refund_record WHERE id = :id")
    RefundRecordEntity getById(long id);

    /** 含软删行，供同步推送与拉取按身份定位（墓碑判定也走这里）。 */
    @Query("SELECT * FROM refund_record WHERE sync_id = :syncId LIMIT 1")
    RefundRecordEntity getBySyncId(String syncId);

    /** 某账单已到账退款合计（分）——账单 refunded_amount 的重算来源。 */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM refund_record "
            + "WHERE is_deleted = 0 AND state = 'RECEIVED' AND transaction_id = :transactionId")
    long sumReceived(long transactionId);

    /** 某账单待到账退款合计（分）——账单 pending_refund_amount 的重算来源。 */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM refund_record "
            + "WHERE is_deleted = 0 AND state = 'PENDING' AND transaction_id = :transactionId")
    long sumPending(long transactionId);

    @Insert
    long insert(RefundRecordEntity entity);

    @Update
    void update(RefundRecordEntity entity);

    /** 删除账单时级联软删其退款流水；恢复时由 {@link #restoreByTransaction(long)} 置回。 */
    @Query("UPDATE refund_record SET is_deleted = 1, deleted_at = :deletedAt, "
            + "updated_at = :deletedAt WHERE transaction_id = :transactionId AND is_deleted = 0")
    int softDeleteByTransaction(long transactionId, long deletedAt);

    @Query("UPDATE refund_record SET is_deleted = 0, deleted_at = NULL, updated_at = :updatedAt "
            + "WHERE transaction_id = :transactionId AND is_deleted = 1")
    int restoreByTransaction(long transactionId, long updatedAt);

    /** 备份用：当前账本的全部有效退款，按账单聚合排列。仅在 IO 线程调用。 */
    @Query("SELECT * FROM refund_record WHERE is_deleted = 0 "
            + "AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) "
            + "ORDER BY transaction_id, id")
    List<RefundRecordEntity> getBackupRows();

    @Query("SELECT * FROM refund_record")
    List<RefundRecordEntity> getAllEntitiesIncludingDeleted();

    @Query("DELETE FROM refund_record")
    void deleteAll();

    /** V3.2 语义：清空数据按账本作用域。 */
    @Query("DELETE FROM refund_record WHERE ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1)")
    void clearCurrentLedger();

    /** 默认账本 claim 合并后，把退款流水迁到目标账本。 */
    @Query("UPDATE refund_record SET ledger_id = :toLedgerId WHERE ledger_id = :fromLedgerId")
    int repointLedger(long fromLedgerId, long toLedgerId);
}
