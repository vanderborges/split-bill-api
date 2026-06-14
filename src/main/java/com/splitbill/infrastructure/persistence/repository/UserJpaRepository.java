package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {

    boolean existsByEmailIgnoreCase(String email);

    long countByAdminTrueAndActiveTrueAndDeletedAtIsNull();
}
