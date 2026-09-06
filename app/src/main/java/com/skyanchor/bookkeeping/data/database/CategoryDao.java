package com.skyanchor.bookkeeping.data.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;

import java.util.List;

/**
 * 分类 DAO。支持新增、编辑、删除、排序，支出与收入分类分离（V1 基线第 6 章）。
 */
@Dao
public interface CategoryDao {

    /** V3：普通列表过滤软删行（基线第 17.2 章）。 */
    @Query("SELECT * FROM category WHERE is_deleted = 0 AND type = :type AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) "
            + "ORDER BY sort_order ASC, id ASC")
    LiveData<List<CategoryEntity>> observeByType(int type);

    @Query("SELECT * FROM category WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) "
            + "ORDER BY type ASC, sort_order ASC, id ASC")
    LiveData<List<CategoryEntity>> observeAll();

    @Query("SELECT * FROM category WHERE id = :id")
    CategoryEntity getById(long id);

    /** V3：跨设备身份定位（同步 Pull 应用用）。 */
    @Query("SELECT * FROM category WHERE sync_id = :syncId LIMIT 1")
    CategoryEntity getBySyncId(String syncId);

    @Query("SELECT * FROM category WHERE is_deleted = 0 AND type = :type AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) "
            + "ORDER BY sort_order ASC, id ASC")
    List<CategoryEntity> getByType(int type);

    /** 全量有效分类（含支出与收入），供 CSV 导入时按「类型 + 名称」解析分类 id。 */
    @Query("SELECT * FROM category WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) "
            + "ORDER BY type ASC, sort_order ASC, id ASC")
    List<CategoryEntity> getAll();

    /** 全量分类（含软删），供首次同步统计与全量推送。仅在 IO 线程调用。 */
    @Query("SELECT * FROM category ORDER BY type ASC, sort_order ASC, id ASC")
    List<CategoryEntity> getAllIncludingDeleted();

    @Insert
    long insert(CategoryEntity entity);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAll(List<CategoryEntity> entities);

    @Update
    void update(CategoryEntity entity);

    @Update
    void updateAll(List<CategoryEntity> entities);

    @Query("DELETE FROM category WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM category")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM category WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) AND type = :type")
    int countByType(int type);

    @Query("SELECT COUNT(*) FROM category WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1)")
    LiveData<Integer> observeCount();

    @Query("SELECT COUNT(*) FROM category WHERE is_deleted = 0 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1)")
    int countAll();

    /** 当前类型下最大的 sortOrder，用于新增分类时追加到末尾。 */
    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM category WHERE type = :type AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1)")
    int maxSortOrder(int type);

    // ===== V3.1 回收站 =====

    /** 回收站：全部软删分类，删除时间新→旧。 */
    @Query("SELECT * FROM category WHERE is_deleted = 1 AND ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1) "
            + "ORDER BY COALESCE(deleted_at, sort_order) DESC")
    LiveData<List<CategoryEntity>> observeRecycleBin();

    /** V3.2：仅清空当前账本的业务数据（「清空数据」按账本作用域，其他账本不受影响）。 */
    @Query("DELETE FROM category WHERE ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1)")
    void clearCurrentLedger();

    /** V3.2：默认账本 claim 合并后，把本账本全部业务行迁移到合并目标账本。 */
    @Query("UPDATE category SET ledger_id = :toLedgerId WHERE ledger_id = :fromLedgerId")
    int repointLedger(long fromLedgerId, long toLedgerId);
}
