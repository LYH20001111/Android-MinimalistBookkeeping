package com.skyanchor.bookkeeping.server.sync.repo;

import com.skyanchor.bookkeeping.server.sync.domain.CategoryRow;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRowRepository extends SyncRowRepositoryBase<CategoryRow> {

    /** 同用户下同名同类型的分类（多设备重名合并判定，见 SyncService）。 */
    java.util.Optional<CategoryRow> findByUserIdAndTypeAndNameIgnoreCase(
            Long userId, Integer type, String name);


    @Modifying
    @Query("DELETE FROM CategoryRow r WHERE r.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
