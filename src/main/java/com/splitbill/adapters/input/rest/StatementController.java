package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.StatementResponse;
import com.splitbill.application.usecase.StatementUseCase;
import com.splitbill.infrastructure.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/statement")
public class StatementController {

    private final StatementUseCase statement;
    private final CurrentUser currentUser;

    public StatementController(StatementUseCase statement, CurrentUser currentUser) {
        this.statement = statement;
        this.currentUser = currentUser;
    }

    /** Extrato com "Todos os eventos" (ver {@link StatementUseCase}). */
    @GetMapping
    public StatementResponse load(@RequestParam(required = false) UUID groupId) {
        return statement.load(currentUser.id(), groupId);
    }
}
