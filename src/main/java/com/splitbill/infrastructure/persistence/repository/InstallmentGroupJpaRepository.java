package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.infrastructure.persistence.entity.InstallmentGroupJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InstallmentGroupJpaRepository extends JpaRepository<InstallmentGroupJpaEntity, UUID> {
}
