package com.skyanchor.bookkeeping.data.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;

import java.util.List;

/**
 * 周期账单 DAO（V2 新增，schema 本轮建好，生成逻辑见 Phase 8）。
 *
 * <p>{@link #observeDue(long)} 返回 {@code next_run_date <= today 且 is_enabled} 的到期模板，
 * 用于 App 启动时提示「有 N 笔周期账单待记账」，由用户一键确认后写入交易。
 */
@Dao
public interface RecurringTransactionDao {

    /** V3：业务查询排除软删行（基线第 17.2 章）。 */
    @Query("SELECT * FROM recurring_transaction WHERE is_deleted = 0 "
            + "ORDER BY is_enabled DESC, next_run_date ASC, id ASC")
    LiveData<List<RecurringTransactionEntity>> observeAll();

    @Query("SELECT * FROM recurring_transaction "
            + "WHERE is_deleted = 0 AND is_enabled = 1 AND next_run_date <= :today "
            + "ORDER BY next_run_date ASC, id ASC")
    LiveData<List<RecurringTransactionEntity>> observeDue(long today);

    @Query("SELECT * FROM recurring_transaction WHERE is_deleted = 0 "
            + "AND is_enabled = 1 AND next_run_date <= :today "
            + "ORDER BY next_run_date ASC, id ASC")
    List<RecurringTransactionEntity> getDue(long today);

    @Query("SELECT COUNT(*) FROM recurring_transaction WHERE is_deleted = 0 "
            + "AND is_enabled = 1 AND next_run_date <= :today")
    LiveData<Integer> observeDueCount(long today);

    @Query("SELECT * FROM recurring_transaction WHERE id = :id")
    RecurringTransactionEntity getById(long id);

    /** V3：跨设备身份定位（同步 Pull 应用用）。 */
    @Query("SELECT * FROM recurring_transaction WHERE sync_id = :syncId LIMIT 1")
    RecurringTransactionEntity getBySyncId(String syncId);

    /** V3：重名分类合并——周期账单分类引用改指向。 */
    @Query("UPDATE recurring_transaction SET category_id = :toId, updated_at = :updatedAt "
            + "WHERE is_deleted = 0 AND category_id = :fromId")
    int repointCategory(long fromId, long toId, long updatedAt);

    /** V3：重名账户合并——周期账单账户引用改指向。 */
    @Query("UPDATE recurring_transaction SET account_id = :toId, updated_at = :updatedAt "
            + "WHERE is_deleted = 0 AND account_id = :fromId")
    int repointAccount(long fromId, long toId, long updatedAt);

    /** 有效规则（供备份序列化）。 */
    @Query("SELECT * FROM recurring_transaction WHERE is_deleted = 0 "
            + "ORDER BY next_run_date ASC, id ASC")
    List<RecurringTransactionEntity> getAll();

    /** 全量规则（含软删），供首次同步统计与全量推送。仅在 IO 线程调用。 */
    @Query("SELECT * FROM recurring_transaction ORDER BY next_run_date ASC, id ASC")
    List<RecurringTransactionEntity> getAllIncludingDeleted();

    @Query("SELECT COUNT(*) FROM recurring_transaction WHERE is_deleted = 0")
    LiveData<Integer> observeCount();

    @Query("SELECT COUNT(*) FROM recurring_transaction WHERE is_deleted = 0")
    int countAll();

    @Insert
    long insert(RecurringTransactionEntity entity);

    @Insert
    void insertAll(List<RecurringTransactionEntity> entities);

    @Update
    void update(RecurringTransactionEntity entity);

    @Query("DELETE FROM recurring_transaction WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM recurring_transaction")
    void deleteAll();

    // ===== V3.1 回收站 =====

    /** 回收站：全部软删周期规则，删除时间新→旧。 */
    @Query("SELECT * FROM recurring_transaction WHERE is_deleted = 1 "
            + "ORDER BY COALESCE(deleted_at, updated_at) DESC")
    LiveData<List<RecurringTransactionEntity>> observeRecycleBin();
}
