package com.skyanchor.bookkeeping.server.sync.repo;

import com.skyanchor.bookkeeping.server.sync.domain.AccountRow;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRowRepository extends SyncRowRepositoryBase<AccountRow> {

    /** 同用户下同名账户（多设备重名合并判定，见 SyncService）。 */
    java.util.Optional<AccountRow> findByUserIdAndNameIgnoreCase(Long userId, String name);


    @Modifying
    @Query("DELETE FROM AccountRow r WHERE r.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
