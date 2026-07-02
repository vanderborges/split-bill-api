package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.infrastructure.persistence.entity.ExpenseJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ExpenseJpaRepository extends JpaRepository<ExpenseJpaEntity, UUID> {

    List<ExpenseJpaEntity> findByMonthIdAndDeletedAtIsNull(UUID monthId);

    List<ExpenseJpaEntity> findByEventIdAndDeletedAtIsNull(UUID eventId);

    List<ExpenseJpaEntity> findByEventIdInAndDeletedAtIsNull(List<UUID> eventIds);

    List<ExpenseJpaEntity> findByExpenseDateBetweenAndDeletedAtIsNull(LocalDate from, LocalDate to);

    List<ExpenseJpaEntity> findByInstallmentGroupIdAndDeletedAtIsNull(UUID installmentGroupId);

    @Query("""
            select distinct expense
            from ExpenseJpaEntity expense
            join fetch expense.payer
            join fetch expense.createdBy
            left join fetch expense.month
            join fetch expense.event event
            left join fetch expense.sourceEvent
            left join fetch expense.installmentGroup
            where expense.deletedAt is null
              and event.group.id = :groupId
              and expense.expenseDate between :from and :to
            order by expense.expenseDate asc, expense.description asc
            """)
    List<ExpenseJpaEntity> findSummaryCandidatesByGroup(
            @Param("groupId") UUID groupId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    @Query("""
            select distinct expense
            from ExpenseJpaEntity expense
            join fetch expense.payer
            join fetch expense.createdBy
            left join fetch expense.month
            join fetch expense.event event
            left join fetch expense.sourceEvent
            left join fetch expense.installmentGroup
            where expense.deletedAt is null
              and event.group.id = :groupId
              and event.id = :eventId
              and expense.expenseDate between :from and :to
            order by expense.expenseDate asc, expense.description asc
            """)
    List<ExpenseJpaEntity> findSummaryCandidatesByGroupAndEvent(
            @Param("groupId") UUID groupId,
            @Param("eventId") UUID eventId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );
}
