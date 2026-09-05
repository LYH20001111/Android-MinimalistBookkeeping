package com.skyanchor.bookkeeping.server.sync.repo;

import com.skyanchor.bookkeeping.server.sync.domain.SyncChangeRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SyncChangeRepository extends JpaRepository<SyncChangeRow, Long> {

    /**
     * 游标增量拉取：只取每个 (syncId, entityType) 的最新一条变更。
     * 业务行当前状态即最终胜出版本，Pull 按它组装载荷，客户端按 version 高低决定是否应用。
     */
    @Query("""
            SELECT sc FROM SyncChangeRow sc WHERE sc.id IN (
                SELECT MAX(sc2.id) FROM SyncChangeRow sc2
                WHERE sc2.userId = :userId AND sc2.id > :cursor
                GROUP BY sc2.syncId, sc2.entityType
            ) ORDER BY sc.id ASC
            """)
    List<SyncChangeRow> findLatestChangesAfter(@Param("userId") Long userId,
                                               @Param("cursor") long cursor);

    @Modifying
    @Query("DELETE FROM SyncChangeRow r WHERE r.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
