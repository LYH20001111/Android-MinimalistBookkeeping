package com.skyanchor.bookkeeping.server.sync.repo;

import com.skyanchor.bookkeeping.server.sync.domain.RecurringRow;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecurringRowRepository extends SyncRowRepositoryBase<RecurringRow> {

    @Modifying
    @Query("DELETE FROM RecurringRow r WHERE r.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM RecurringRow r WHERE r.ledgerId = :ledgerId")
    void deleteAllByLedgerId(@Param("ledgerId") Long ledgerId);
}
