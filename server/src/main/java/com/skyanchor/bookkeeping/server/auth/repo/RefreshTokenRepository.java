package com.skyanchor.bookkeeping.server.auth.repo;

import com.skyanchor.bookkeeping.server.auth.domain.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    List<RefreshTokenEntity> findAllByUserId(Long userId);

    List<RefreshTokenEntity> findAllByDeviceRowId(Long deviceRowId);
}
