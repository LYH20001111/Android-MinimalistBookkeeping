package com.skyanchor.bookkeeping.server.sync.repo;

import com.skyanchor.bookkeeping.server.sync.domain.SyncRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.Optional;

/**
 * 业务行 Repository 公共契约。
 *
 * <p>V3.2 起：运行期查询一律以 (ledgerId, syncId) 定位并按账本隔离（基线第 4 章，
 * 禁止只按 user_id 判断业务访问）；(userId, syncId) 保留给备份导出与账号注销等
 * 用户级运维操作。
 */
@NoRepositoryBean
public interface SyncRowRepositoryBase<T extends SyncRow> extends JpaRepository<T, Long> {

    Optional<T> findByLedgerIdAndSyncId(Long ledgerId, String syncId);

    Optional<T> findByUserIdAndSyncId(Long userId, String syncId);

    long countByUserId(Long userId);

    long countByUserIdAndDeletedFalse(Long userId);

    long countByLedgerIdAndDeletedFalse(Long ledgerId);
}
