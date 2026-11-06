package com.splitbill.application.usecase;

import com.splitbill.application.dto.CreateExpenseCategoryRequest;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.infrastructure.persistence.entity.ExpenseCategoryJpaEntity;
import com.splitbill.infrastructure.persistence.repository.ExpenseCategoryJpaRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpenseCategoryUseCaseTest {

    private final ExpenseCategoryJpaRepository categories = mock(ExpenseCategoryJpaRepository.class);
    private final ExpenseCategoryUseCase useCase = new ExpenseCategoryUseCase(categories);

    @Test
    void onlyGeneralAdminCreates() {
        assertThatThrownBy(() -> useCase.create(new CreateExpenseCategoryRequest("Pedágio"), false))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("administrador geral");
        verify(categories, never()).save(any());
    }

    @Test
    void adminCreates() {
        when(categories.save(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(useCase.create(new CreateExpenseCategoryRequest(" Pedágio "), true).name()).isEqualTo("Pedágio");
    }

    @Test
    void creatingADeletedTypeBringsItBack() {
        ExpenseCategoryJpaEntity deleted = category("Pedágio", false);
        when(categories.findFirstByNameIgnoreCase("pedágio")).thenReturn(Optional.of(deleted));

        useCase.create(new CreateExpenseCategoryRequest("pedágio"), true);

        assertThat(deleted.isActive()).isTrue();
        verify(categories, never()).save(any());
    }

    @Test
    void onlyGeneralAdminDeletes() {
        ExpenseCategoryJpaEntity toll = category("Pedágio", true);
        when(categories.findById(toll.getId())).thenReturn(Optional.of(toll));

        assertThatThrownBy(() -> useCase.delete(toll.getId(), false)).isInstanceOf(DomainException.class);
        assertThat(toll.isActive()).isTrue();

        useCase.delete(toll.getId(), true);
        assertThat(toll.isActive()).isFalse();
    }

    @Test
    void generalCannotBeDeleted() {
        ExpenseCategoryJpaEntity general = category("Geral", true);
        when(categories.findById(general.getId())).thenReturn(Optional.of(general));

        assertThatThrownBy(() -> useCase.delete(general.getId(), true)).isInstanceOf(DomainException.class);
        assertThat(general.isActive()).isTrue();
    }

    private static ExpenseCategoryJpaEntity category(String name, boolean active) {
        ExpenseCategoryJpaEntity category = new ExpenseCategoryJpaEntity();
        category.setId(UUID.randomUUID());
        category.setName(name);
        category.setActive(active);
        return category;
    }
}
