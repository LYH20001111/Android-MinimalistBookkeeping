package com.skyanchor.bookkeeping.data.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.skyanchor.bookkeeping.data.entity.LedgerEntity;

import java.util.List;

/**
 * 账本 DAO（V3.2）。
 *
 * <p>业务隔离的锚点：所有业务 DAO 的查询都以
 * {@code ledger_id = (SELECT id FROM ledger WHERE is_current = 1 LIMIT 1)} 过滤当前账本，
 * 因此 {@link #setCurrent} 翻转标志后，Room 会让全部在订 LiveData 自动重载，
 * 天然满足「切换账本无残留」（基线第 6.3 章）。
 */
@Dao
public interface LedgerDao {

    /** 全部未删除账本（当前账本排最前，其余按创建顺序），供切换器与账本管理页。 */
    @Query("SELECT * FROM ledger WHERE is_deleted = 0 AND role != 'REMOVED' "
            + "ORDER BY is_current DESC, is_default DESC, id ASC")
    LiveData<List<LedgerEntity>> observeActive();

    /** 当前账本（业务查询的子查询同源）。 */
    @Query("SELECT * FROM ledger WHERE is_current = 1 LIMIT 1")
    LiveData<LedgerEntity> observeCurrent();

    @Query("SELECT * FROM ledger WHERE is_current = 1 LIMIT 1")
    LedgerEntity getCurrent();

    @Query("SELECT id FROM ledger WHERE is_current = 1 LIMIT 1")
    Long getCurrentId();

    @Query("SELECT * FROM ledger WHERE id = :id")
    LedgerEntity getById(long id);

    @Query("SELECT * FROM ledger WHERE sync_id = :syncId LIMIT 1")
    LedgerEntity getBySyncId(String syncId);

    /** 全部账本（含软删与被移出），供同步引擎遍历与对账。仅在 IO 线程调用。 */
    @Query("SELECT * FROM ledger ORDER BY id ASC")
    List<LedgerEntity> getAllIncludingDeleted();

    /** 未删除且仍为成员的账本（同步引擎逐账本拉取用）。 */
    @Query("SELECT * FROM ledger WHERE is_deleted = 0 AND role != 'REMOVED' ORDER BY id ASC")
    List<LedgerEntity> getActive();

    /** 账本回收站：软删账本，删除时间新→旧（仅 OWNER 本机有恢复入口）。 */
    @Query("SELECT * FROM ledger WHERE is_deleted = 1 ORDER BY COALESCE(deleted_at, updated_at) DESC")
    LiveData<List<LedgerEntity>> observeRecycleBin();

    @Insert
    long insert(LedgerEntity entity);

    @Update
    void update(LedgerEntity entity);

    /** 切换账本：唯一置位目标，其余清零；同一事务内完成（基线第 6.3 章显式状态变更）。 */
    @Query("UPDATE ledger SET is_current = CASE WHEN id = :id THEN 1 ELSE 0 END")
    void setCurrent(long id);

    /** 已被移出的成员标记为只读隐藏（同步对账感知）。 */
    @Query("UPDATE ledger SET role = 'REMOVED', updated_at = :updatedAt WHERE sync_id = :syncId")
    void markRemoved(String syncId, long updatedAt);

    @Query("UPDATE ledger SET is_deleted = 1, deleted_at = :deletedAt, updated_at = :updatedAt "
            + "WHERE id = :id")
    void softDelete(long id, long deletedAt, long updatedAt);

    @Query("UPDATE ledger SET is_deleted = 0, deleted_at = NULL, updated_at = :updatedAt "
            + "WHERE id = :id")
    void restore(long id, long updatedAt);

    @Query("SELECT COUNT(*) FROM ledger WHERE is_deleted = 0 AND role != 'REMOVED'")
    int countActive();

    /** mergedInto 合并后物理删除被合并的本地空壳行（业务行已迁走）。 */
    @Query("DELETE FROM ledger WHERE id = :id")
    void deleteById(long id);

    /** 迁移回填 / claim 的默认账本（最多一个；游标键迁移用）。 */
    @Query("SELECT * FROM ledger WHERE is_default = 1 AND is_deleted = 0 LIMIT 1")
    LedgerEntity getDefaultLedger();
}
