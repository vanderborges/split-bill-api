package com.splitbill.application.usecase;

import com.splitbill.application.dto.CreateExpenseCategoryRequest;
import com.splitbill.application.dto.ExpenseCategoryResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.infrastructure.persistence.entity.ExpenseCategoryJpaEntity;
import com.splitbill.infrastructure.persistence.repository.ExpenseCategoryJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ExpenseCategoryUseCase {

    private final ExpenseCategoryJpaRepository categories;

    public ExpenseCategoryUseCase(ExpenseCategoryJpaRepository categories) {
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<ExpenseCategoryResponse> listActive() {
        return categories.findByActiveTrueOrderByNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ExpenseCategoryResponse create(CreateExpenseCategoryRequest request) {
        String name = request.name().trim();
        if (categories.existsByNameIgnoreCase(name)) {
            throw new DomainException("Expense category already exists");
        }

        ExpenseCategoryJpaEntity category = new ExpenseCategoryJpaEntity();
        category.setId(UUID.randomUUID());
        category.setName(name);
        category.setActive(true);
        category.setCreatedAt(LocalDateTime.now());
        return toResponse(categories.save(category));
    }

    private ExpenseCategoryResponse toResponse(ExpenseCategoryJpaEntity category) {
        return new ExpenseCategoryResponse(category.getId(), category.getName());
    }
}
