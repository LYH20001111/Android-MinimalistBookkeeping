package com.skyanchor.bookkeeping.server.sync.repo;

import com.skyanchor.bookkeeping.server.sync.domain.RefundRow;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRowRepository extends SyncRowRepositoryBase<RefundRow> {

    @Modifying
    @Query("DELETE FROM RefundRow r WHERE r.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM RefundRow r WHERE r.ledgerId = :ledgerId")
    void deleteAllByLedgerId(@Param("ledgerId") Long ledgerId);
}
