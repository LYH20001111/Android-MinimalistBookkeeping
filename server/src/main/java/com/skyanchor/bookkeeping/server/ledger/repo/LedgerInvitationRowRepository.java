package com.skyanchor.bookkeeping.server.ledger.repo;

import com.skyanchor.bookkeeping.server.ledger.domain.LedgerInvitationRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LedgerInvitationRowRepository extends JpaRepository<LedgerInvitationRow, Long> {

    Optional<LedgerInvitationRow> findByInvitationId(String invitationId);

    /** 我的待处理邀请（被邀请视角）。 */
    List<LedgerInvitationRow> findByInviteeEmailIgnoreCaseAndStatusOrderByCreatedAtDesc(
            String inviteeEmail, String status);

    /** 同一账本同一邮箱的历史邀请（重新邀请时作废旧 pending）。 */
    List<LedgerInvitationRow> findByLedgerIdAndInviteeEmailIgnoreCaseAndStatus(
            Long ledgerId, String inviteeEmail, String status);
}
