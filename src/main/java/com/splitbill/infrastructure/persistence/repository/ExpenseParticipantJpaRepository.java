package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.infrastructure.persistence.entity.ExpenseParticipantJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ExpenseParticipantJpaRepository extends JpaRepository<ExpenseParticipantJpaEntity, UUID> {

    @Query("""
            select participant
            from ExpenseParticipantJpaEntity participant
            join fetch participant.user
            where participant.expense.id in :expenseIds
            """)
    List<ExpenseParticipantJpaEntity> findByExpenseIdsWithUser(@Param("expenseIds") List<UUID> expenseIds);
}
