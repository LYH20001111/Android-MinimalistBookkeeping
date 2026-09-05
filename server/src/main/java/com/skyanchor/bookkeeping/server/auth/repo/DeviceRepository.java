package com.skyanchor.bookkeeping.server.auth.repo;

import com.skyanchor.bookkeeping.server.auth.domain.DeviceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<DeviceEntity, Long> {

    Optional<DeviceEntity> findByUserIdAndDeviceId(Long userId, String deviceId);

    List<DeviceEntity> findAllByUserIdOrderByCreatedAtAsc(Long userId);
}
