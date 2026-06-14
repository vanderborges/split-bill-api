package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.infrastructure.persistence.entity.ExpenseJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ExpenseJpaRepository extends JpaRepository<ExpenseJpaEntity, UUID> {

    List<ExpenseJpaEntity> findByMonthIdAndDeletedAtIsNull(UUID monthId);

    List<ExpenseJpaEntity> findByEventIdAndDeletedAtIsNull(UUID eventId);

    List<ExpenseJpaEntity> findByEventIdInAndDeletedAtIsNull(List<UUID> eventIds);

    List<ExpenseJpaEntity> findByExpenseDateBetweenAndDeletedAtIsNull(LocalDate from, LocalDate to);

    List<ExpenseJpaEntity> findByInstallmentGroupIdAndDeletedAtIsNull(UUID installmentGroupId);
}
