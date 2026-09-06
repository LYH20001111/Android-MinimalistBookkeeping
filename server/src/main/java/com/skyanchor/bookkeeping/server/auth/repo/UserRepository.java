package com.skyanchor.bookkeeping.server.auth.repo;

import com.skyanchor.bookkeeping.server.auth.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmailIgnoreCase(String email);

    /** 最早注册的有效账号（备份/恢复管理员的兜底判定，见 AdminGuard）。 */
    Optional<UserEntity> findFirstByDeletedAtIsNullOrderByIdAsc();
}
