package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.infrastructure.persistence.entity.ExpenseCategoryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExpenseCategoryJpaRepository extends JpaRepository<ExpenseCategoryJpaEntity, UUID> {

    List<ExpenseCategoryJpaEntity> findByActiveTrueOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);
}
