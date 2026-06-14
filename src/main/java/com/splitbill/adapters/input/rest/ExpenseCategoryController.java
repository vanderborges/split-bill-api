package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.CreateExpenseCategoryRequest;
import com.splitbill.application.dto.ExpenseCategoryResponse;
import com.splitbill.application.usecase.ExpenseCategoryUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/expense-categories")
public class ExpenseCategoryController {

    private final ExpenseCategoryUseCase categories;

    public ExpenseCategoryController(ExpenseCategoryUseCase categories) {
        this.categories = categories;
    }

    @GetMapping
    public List<ExpenseCategoryResponse> listActive() {
        return categories.listActive();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseCategoryResponse create(@Valid @RequestBody CreateExpenseCategoryRequest request) {
        return categories.create(request);
    }
}
