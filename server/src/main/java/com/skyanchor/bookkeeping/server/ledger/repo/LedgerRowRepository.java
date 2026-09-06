package com.skyanchor.bookkeeping.server.ledger.repo;

import com.skyanchor.bookkeeping.server.ledger.domain.LedgerRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LedgerRowRepository extends JpaRepository<LedgerRow, Long> {

    /** 账本 syncId 全局唯一：成员设备凭它寻址，不要求是 OWNER。 */
    Optional<LedgerRow> findBySyncId(String syncId);

    /** 用户被迁移回填 / 首次 claim 的默认账本（最多一个）。 */
    Optional<LedgerRow> findByUserIdAndDefaultLedgerTrueAndDeletedFalse(Long userId);

    List<LedgerRow> findByUserIdAndDeletedFalse(Long userId);

    List<LedgerRow> findAllByUserId(Long userId);

    long countByUserId(Long userId);
}
