package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.infrastructure.persistence.entity.ExpensePayerJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ExpensePayerJpaRepository extends JpaRepository<ExpensePayerJpaEntity, UUID> {

    @Query("""
            select payer
            from ExpensePayerJpaEntity payer
            join fetch payer.user
            where payer.expense.id in :expenseIds
            """)
    List<ExpensePayerJpaEntity> findByExpenseIdsWithUser(@Param("expenseIds") List<UUID> expenseIds);
}
