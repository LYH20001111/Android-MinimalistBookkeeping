package com.skyanchor.bookkeeping.data.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.skyanchor.bookkeeping.data.entity.BudgetEntity;

import java.util.List;

/**
 * 预算 DAO。
 *
 * <p>V1 只有「月总预算」，按 year + month 唯一。V2 唯一索引改为 (year, month, category_id)，
 * category_id = 0 是总预算哨兵。下面 {@link #observe(int, int)} / {@link #get(int, int)} /
 * {@link #delete(int, int)} 仍只针对总预算（category_id = 0），保持 V1 调用方语义不变；
 * 分类预算的读写见 Phase 6 新增方法。
 */
@Dao
public interface BudgetDao {

    /** 观察某月总预算（category_id = 0）；V3 过滤软删行。 */
    @Query("SELECT * FROM budget WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) "
            + "AND year = :year AND month = :month AND category_id = 0")
    LiveData<BudgetEntity> observe(int year, int month);

    /** 读取某月总预算（category_id = 0）。 */
    @Query("SELECT * FROM budget WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) "
            + "AND year = :year AND month = :month AND category_id = 0")
    BudgetEntity get(int year, int month);

    /** 观察某月总预算 + 全部分类预算。 */
    @Query("SELECT * FROM budget WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) AND year = :year AND month = :month "
            + "ORDER BY category_id ASC")
    LiveData<List<BudgetEntity>> observeAllForMonth(int year, int month);

    /** 观察某月全部分类预算（category_id >= 1）。 */
    @Query("SELECT * FROM budget WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) "
            + "AND year = :year AND month = :month AND category_id > 0 "
            + "ORDER BY category_id ASC")
    LiveData<List<BudgetEntity>> observeCategoryBudgets(int year, int month);

    /** 观察某月某分类预算；categoryId = 0 时即总预算。V3 过滤软删行。 */
    @Query("SELECT * FROM budget WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) "
            + "AND year = :year AND month = :month AND category_id = :categoryId")
    LiveData<BudgetEntity> observe(int year, int month, int categoryId);

    /** 按 (year, month, category) 定位行，**含软删**（复用身份重建预算时用）。 */
    @Query("SELECT * FROM budget WHERE ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) AND year = :year AND month = :month AND category_id = :categoryId")
    BudgetEntity get(int year, int month, int categoryId);

    /** 读取有效预算（不含软删），categoryId = 0 时即总预算。 */
    @Query("SELECT * FROM budget WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) "
            + "AND year = :year AND month = :month AND category_id = :categoryId")
    BudgetEntity getActive(int year, int month, int categoryId);

    /** V3：跨设备身份定位（同步 Pull 应用用）。 */
    @Query("SELECT * FROM budget WHERE sync_id = :syncId LIMIT 1")
    BudgetEntity getBySyncId(String syncId);

    /** V3：重名分类合并——预算的分类引用改指向（调用方先做唯一键冲突检查）。 */
    @Query("UPDATE budget SET category_id = :toId, updated_at = :updatedAt "
            + "WHERE is_deleted = 0 AND category_id = :fromId")
    int repointCategory(long fromId, long toId, long updatedAt);

    /** V3：物理删除单条预算（仅用于从未同步的重复行清理）。 */
    @Query("DELETE FROM budget WHERE id = :id")
    void deleteById(long id);

    /**
     * 依赖 (year, month, category_id) 唯一索引做 upsert：已存在则替换，主键由调用方保证。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long upsert(BudgetEntity entity);

    /** 删除某月总预算（category_id = 0）。 */
    @Query("DELETE FROM budget WHERE ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) AND year = :year AND month = :month AND category_id = 0")
    void delete(int year, int month);

    /** 删除某月某分类预算。 */
    @Query("DELETE FROM budget WHERE ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) AND year = :year AND month = :month AND category_id = :categoryId")
    void delete(int year, int month, int categoryId);

    /**
     * 软删某分类在所有月份的预算（V3）：分类删除改为 Soft Delete 后，
     * 其预算同样软删并作为可同步事件传播（开发计划备注 9）。
     */
    @Query("UPDATE budget SET is_deleted = 1, updated_at = :updatedAt "
            + "WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) AND category_id = :categoryId")
    int softDeleteByCategoryId(long categoryId, long updatedAt);

    /** 某分类的有效预算行（软删传播时逐行入队用）。 */
    @Query("SELECT * FROM budget WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) AND category_id = :categoryId")
    List<BudgetEntity> getActiveByCategoryId(long categoryId);

    @Query("DELETE FROM budget")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM budget WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1)")
    LiveData<Integer> observeCount();

    @Query("SELECT COUNT(*) FROM budget WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1)")
    int countAll();

    /** 有效预算（供备份序列化）。 */
    @Query("SELECT * FROM budget WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) "
            + "ORDER BY year ASC, month ASC, category_id ASC")
    List<BudgetEntity> getAll();

    /** 全量预算（含软删），供首次同步统计与全量推送。仅在 IO 线程调用。 */
    @Query("SELECT * FROM budget ORDER BY year ASC, month ASC, category_id ASC")
    List<BudgetEntity> getAllIncludingDeleted();

    /** V3.2：仅清空当前账本的业务数据（「清空数据」按账本作用域，其他账本不受影响）。 */
    @Query("DELETE FROM budget WHERE ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1)")
    void clearCurrentLedger();

    /** V3.2：默认账本 claim 合并后，把本账本全部业务行迁移到合并目标账本。 */
    @Query("UPDATE budget SET ledger_id = :toLedgerId WHERE ledger_id = :fromLedgerId")
    int repointLedger(long fromLedgerId, long toLedgerId);
}
