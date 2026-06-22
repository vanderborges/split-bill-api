package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.MonthlyReportResponse;
import com.splitbill.application.usecase.MonthlyReportUseCase;
import com.splitbill.infrastructure.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/reports")
public class MonthlyReportController {

    private final MonthlyReportUseCase reports;
    private final CurrentUser currentUser;

    public MonthlyReportController(MonthlyReportUseCase reports, CurrentUser currentUser) {
        this.reports = reports;
        this.currentUser = currentUser;
    }

    @GetMapping("/months/{monthId}")
    public MonthlyReportResponse getMonthlyReport(@PathVariable UUID monthId) {
        return reports.getByMonth(monthId, currentUser.id());
    }

    @GetMapping("/events/{eventId}")
    public MonthlyReportResponse getEventReport(@PathVariable UUID eventId) {
        return reports.getByEvent(eventId, currentUser.id());
    }
}
