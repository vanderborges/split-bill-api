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
              and (:eventId is null or event.id = :eventId)
              and (:category is null or lower(expense.category) = lower(:category))
              and (
                    :userId is null
                    or exists (
                        select participant.id
                        from ExpenseParticipantJpaEntity participant
                        where participant.expense = expense
                          and participant.user.id = :userId
                    )
                    or exists (
                        select payer.id
                        from ExpensePayerJpaEntity payer
                        where payer.expense = expense
                          and payer.user.id = :userId
                    )
              )
            order by expense.expenseDate asc, expense.description asc
            """)
    List<ExpenseJpaEntity> findSummaryCandidates(
            @Param("groupId") UUID groupId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("category") String category,
            @Param("eventId") UUID eventId,
            @Param("userId") UUID userId
    );
}
