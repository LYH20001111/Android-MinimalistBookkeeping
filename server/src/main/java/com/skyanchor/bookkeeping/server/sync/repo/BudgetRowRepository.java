package com.skyanchor.bookkeeping.server.sync.repo;

import com.skyanchor.bookkeeping.server.sync.domain.BudgetRow;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetRowRepository extends SyncRowRepositoryBase<BudgetRow> {

    /** 同账本同（年，月，分类）的预算（多设备/多成员重名合并判定，见 SyncService）。 */
    java.util.Optional<BudgetRow> findByLedgerIdAndYearAndMonthAndCategorySyncId(
            Long ledgerId, Integer year, Integer month, String categorySyncId);

    /** 同用户同（年，月，分类）的预算（v1 备份恢复补建默认账本时使用）。 */
    java.util.Optional<BudgetRow> findByUserIdAndYearAndMonthAndCategorySyncId(
            Long userId, Integer year, Integer month, String categorySyncId);


    @Modifying
    @Query("DELETE FROM BudgetRow r WHERE r.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM BudgetRow r WHERE r.ledgerId = :ledgerId")
    void deleteAllByLedgerId(@Param("ledgerId") Long ledgerId);
}
