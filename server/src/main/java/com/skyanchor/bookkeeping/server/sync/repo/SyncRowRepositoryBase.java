package com.skyanchor.bookkeeping.server.sync.repo;

import com.skyanchor.bookkeeping.server.sync.domain.SyncRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.Optional;

/**
 * 业务行 Repository 公共契约：所有查询都以 (userId, syncId) 定位，
 * 保证「一个用户一个数据空间」（基线第 31 章）。
 */
@NoRepositoryBean
public interface SyncRowRepositoryBase<T extends SyncRow> extends JpaRepository<T, Long> {

    Optional<T> findByUserIdAndSyncId(Long userId, String syncId);

    long countByUserId(Long userId);

    long countByUserIdAndDeletedFalse(Long userId);
}
