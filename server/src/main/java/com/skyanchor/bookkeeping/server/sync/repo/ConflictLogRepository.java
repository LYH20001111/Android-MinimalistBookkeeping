package com.skyanchor.bookkeeping.server.sync.repo;

import com.skyanchor.bookkeeping.server.sync.domain.ConflictLogRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConflictLogRepository extends JpaRepository<ConflictLogRow, Long> {

    long countByUserId(Long userId);

    /** 最近冲突摘要（新→旧），供客户端冲突历史页面（基线第 26 章）。 */
    List<ConflictLogRow> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    @Modifying
    @Query("DELETE FROM ConflictLogRow r WHERE r.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
