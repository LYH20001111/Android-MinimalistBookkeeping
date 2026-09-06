package com.skyanchor.bookkeeping.server.sync.repo;

import com.skyanchor.bookkeeping.server.sync.domain.AccountRow;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRowRepository extends SyncRowRepositoryBase<AccountRow> {

    /** 同账本下同名账户（多设备/多成员重名合并判定，见 SyncService）。 */
    java.util.Optional<AccountRow> findByLedgerIdAndNameIgnoreCase(Long ledgerId, String name);

    /** 同用户下同名账户（v1 备份恢复补建默认账本时使用）。 */
    java.util.Optional<AccountRow> findByUserIdAndNameIgnoreCase(Long userId, String name);


    @Modifying
    @Query("DELETE FROM AccountRow r WHERE r.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM AccountRow r WHERE r.ledgerId = :ledgerId")
    void deleteAllByLedgerId(@Param("ledgerId") Long ledgerId);
}
