package com.skyanchor.bookkeeping.server.ledger.repo;

import com.skyanchor.bookkeeping.server.ledger.domain.LedgerMemberRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LedgerMemberRowRepository extends JpaRepository<LedgerMemberRow, Long> {

    Optional<LedgerMemberRow> findByLedgerIdAndUserId(Long ledgerId, Long userId);

    List<LedgerMemberRow> findByLedgerIdOrderByCreatedAtAsc(Long ledgerId);

    List<LedgerMemberRow> findAllByUserId(Long userId);

    default boolean isRoleAtLeast(Long ledgerId, Long userId, String requiredRole) {
        return findByLedgerIdAndUserId(ledgerId, userId)
                .filter(m -> LedgerMemberRow.STATUS_ACTIVE.equals(m.getStatus()))
                .map(m -> m.atLeast(requiredRole))
                .orElse(false);
    }
}
