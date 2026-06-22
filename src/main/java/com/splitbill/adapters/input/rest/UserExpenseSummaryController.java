package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.UserExpenseSummaryResponse;
import com.splitbill.application.usecase.UserExpenseSummaryUseCase;
import com.splitbill.infrastructure.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/groups/{groupId}/expense-summary")
public class UserExpenseSummaryController {

    private final UserExpenseSummaryUseCase summaries;
    private final CurrentUser currentUser;

    public UserExpenseSummaryController(UserExpenseSummaryUseCase summaries, CurrentUser currentUser) {
        this.summaries = summaries;
        this.currentUser = currentUser;
    }

    @GetMapping
    public UserExpenseSummaryResponse get(
            @PathVariable UUID groupId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) UUID eventId
    ) {
        return summaries.get(groupId, userId, from, to, category, eventId, currentUser.id());
    }
}
