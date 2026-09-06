package com.skyanchor.bookkeeping.server.sync.repo;

import com.skyanchor.bookkeeping.server.sync.domain.CategoryRow;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRowRepository extends SyncRowRepositoryBase<CategoryRow> {

    /** 同账本下同名同类型的分类（多设备/多成员重名合并判定，见 SyncService）。 */
    java.util.Optional<CategoryRow> findByLedgerIdAndTypeAndNameIgnoreCase(
            Long ledgerId, Integer type, String name);

    /** 同用户下同名同类型的分类（v1 备份恢复补建默认账本时使用）。 */
    java.util.Optional<CategoryRow> findByUserIdAndTypeAndNameIgnoreCase(
            Long userId, Integer type, String name);


    @Modifying
    @Query("DELETE FROM CategoryRow r WHERE r.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM CategoryRow r WHERE r.ledgerId = :ledgerId")
    void deleteAllByLedgerId(@Param("ledgerId") Long ledgerId);
}
