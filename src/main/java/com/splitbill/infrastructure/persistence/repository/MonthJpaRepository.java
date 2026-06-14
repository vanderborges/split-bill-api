package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.infrastructure.persistence.entity.MonthJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MonthJpaRepository extends JpaRepository<MonthJpaEntity, UUID> {

    Optional<MonthJpaEntity> findByMonthAndYear(int month, int year);
}
