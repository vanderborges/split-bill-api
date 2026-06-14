package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.CreateExpenseRequest;
import com.splitbill.application.dto.ExpenseResponse;
import com.splitbill.application.usecase.ExpenseUseCase;
import com.splitbill.domain.exception.DomainException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/expenses")
public class ExpenseController {

    private final ExpenseUseCase expenses;

    public ExpenseController(ExpenseUseCase expenses) {
        this.expenses = expenses;
    }

    @GetMapping
    public List<ExpenseResponse> listByMonth(
            @RequestParam(required = false) UUID monthId,
            @RequestParam(required = false) UUID eventId
    ) {
        if (eventId != null) {
            return expenses.listByEvent(eventId);
        }
        if (monthId == null) {
            throw new DomainException("monthId or eventId is required");
        }
        return expenses.listByMonth(monthId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseResponse create(@Valid @RequestBody CreateExpenseRequest request) {
        return expenses.create(request);
    }

    @PutMapping("/{id}")
    public ExpenseResponse update(@PathVariable UUID id, @Valid @RequestBody CreateExpenseRequest request) {
        return expenses.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id, @RequestParam UUID adminUserId) {
        expenses.delete(id, adminUserId);
    }
}
