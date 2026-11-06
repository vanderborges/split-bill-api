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

/**
 * Tipos de despesa: so o admin geral cria e exclui (06/11/2026). Antes
 * qualquer um criava e o tipo virava descricao (ex.: "Conect Car Outubro").
 * "Geral" e o padrao e nao pode ser excluido.
 */
@Service
public class ExpenseCategoryUseCase {

    static final String DEFAULT_CATEGORY = "Geral";

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
    public ExpenseCategoryResponse create(CreateExpenseCategoryRequest request, boolean requesterIsAdmin) {
        requireAdmin(requesterIsAdmin, "criar");
        String name = request.name().trim();
        ExpenseCategoryJpaEntity existing = categories.findFirstByNameIgnoreCase(name).orElse(null);
        if (existing != null) {
            if (existing.isActive()) {
                throw new DomainException("Esse tipo de despesa já existe.");
            }
            // Excluido antes: volta, em vez de duplicar o nome (coluna unica).
            existing.setActive(true);
            return toResponse(existing);
        }

        ExpenseCategoryJpaEntity category = new ExpenseCategoryJpaEntity();
        category.setId(UUID.randomUUID());
        category.setName(name);
        category.setActive(true);
        category.setCreatedAt(LocalDateTime.now());
        return toResponse(categories.save(category));
    }

    /**
     * Some da lista do form; as despesas que ja usam o tipo continuam com ele
     * (a despesa guarda o nome, nao o tipo).
     */
    @Transactional
    public void delete(UUID id, boolean requesterIsAdmin) {
        requireAdmin(requesterIsAdmin, "excluir");
        ExpenseCategoryJpaEntity category = categories.findById(id)
                .orElseThrow(() -> new DomainException("Tipo de despesa não encontrado."));
        if (DEFAULT_CATEGORY.equalsIgnoreCase(category.getName())) {
            throw new DomainException("O tipo \"Geral\" não pode ser excluído.");
        }
        category.setActive(false);
    }

    private static void requireAdmin(boolean requesterIsAdmin, String action) {
        if (!requesterIsAdmin) {
            throw new DomainException("Apenas o administrador geral pode " + action + " tipos de despesa.");
        }
    }

    private ExpenseCategoryResponse toResponse(ExpenseCategoryJpaEntity category) {
        return new ExpenseCategoryResponse(category.getId(), category.getName());
    }
}
