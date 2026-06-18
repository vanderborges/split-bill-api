package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {

    boolean existsByEmailIgnoreCase(String email);

    Optional<UserJpaEntity> findByEmailIgnoreCase(String email);

    long countByDeletedAtIsNull();

    long countByAdminTrueAndActiveTrueAndDeletedAtIsNull();
}
