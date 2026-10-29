package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.domain.valueobject.EventStatus;
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

    // Inclui as apagadas: parcela apagada pelo usuario encerra a serie
    // (ver InstallmentRolloverService).
    List<ExpenseJpaEntity> findByInstallmentGroupId(UUID installmentGroupId);

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

    @Query("""
            select count(expense) > 0
            from ExpenseJpaEntity expense
            left join expense.payers payer
            left join expense.participants participant
            where expense.deletedAt is null
              and expense.event.status <> :excludedStatus
              and (expense.payer.id = :userId or payer.user.id = :userId or participant.user.id = :userId)
            """)
    boolean existsByUserInvolvementAndEventStatusNot(
            @Param("userId") UUID userId,
            @Param("excludedStatus") EventStatus excludedStatus
    );

    /**
     * Status dos eventos (nao apagados) do grupo em que a pessoa tem
     * despesa - usado pra saber se um temporario do grupo ja "expirou"
     * (todas as despesas dele em eventos fechados).
     */
    @Query("""
            select distinct event.status
            from ExpenseJpaEntity expense
            join expense.event event
            left join expense.payers payer
            left join expense.participants participant
            where expense.deletedAt is null
              and event.deletedAt is null
              and event.group.id = :groupId
              and (expense.payer.id = :userId or payer.user.id = :userId or participant.user.id = :userId)
            """)
    List<EventStatus> findInvolvedEventStatuses(
            @Param("groupId") UUID groupId,
            @Param("userId") UUID userId
    );

    /**
     * Eventos (nao apagados) em que a pessoa tem despesa, pagando ou
     * consumindo - o que ela continua enxergando depois de sair do grupo
     * (e o que o temporario enxerga alem dos eventos abertos).
     */
    @Query("""
            select distinct event.id
            from ExpenseJpaEntity expense
            join expense.event event
            left join expense.payers payer
            left join expense.participants participant
            where expense.deletedAt is null
              and event.deletedAt is null
              and (expense.payer.id = :userId or payer.user.id = :userId or participant.user.id = :userId)
            """)
    List<UUID> findInvolvedEventIds(@Param("userId") UUID userId);
}
