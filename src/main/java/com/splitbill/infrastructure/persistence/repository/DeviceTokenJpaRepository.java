package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.infrastructure.persistence.entity.DeviceTokenJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceTokenJpaRepository extends JpaRepository<DeviceTokenJpaEntity, UUID> {

    Optional<DeviceTokenJpaEntity> findByToken(String token);

    List<DeviceTokenJpaEntity> findByUserId(UUID userId);

    @Transactional
    void deleteByTokenIn(Collection<String> tokens);
}
