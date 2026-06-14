package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.UserExpenseSummaryResponse;
import com.splitbill.application.usecase.UserExpenseSummaryUseCase;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/users/{userId}/expense-summary")
public class UserExpenseSummaryController {

    private final UserExpenseSummaryUseCase summaries;

    public UserExpenseSummaryController(UserExpenseSummaryUseCase summaries) {
        this.summaries = summaries;
    }

    @GetMapping
    public UserExpenseSummaryResponse get(
            @PathVariable UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) UUID eventId
    ) {
        return summaries.get(userId, from, to, category, eventId);
    }
}
