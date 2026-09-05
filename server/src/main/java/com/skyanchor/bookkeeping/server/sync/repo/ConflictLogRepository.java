package com.skyanchor.bookkeeping.server.sync.repo;

import com.skyanchor.bookkeeping.server.sync.domain.ConflictLogRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConflictLogRepository extends JpaRepository<ConflictLogRow, Long> {

    long countByUserId(Long userId);

    @Modifying
    @Query("DELETE FROM ConflictLogRow r WHERE r.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
