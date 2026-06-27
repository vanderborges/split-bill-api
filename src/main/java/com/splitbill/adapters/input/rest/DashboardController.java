package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.DashboardGroupBalanceResponse;
import com.splitbill.application.usecase.DashboardUseCase;
import com.splitbill.infrastructure.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private final DashboardUseCase dashboard;
    private final CurrentUser currentUser;

    public DashboardController(DashboardUseCase dashboard, CurrentUser currentUser) {
        this.dashboard = dashboard;
        this.currentUser = currentUser;
    }

    @GetMapping("/group-balances")
    public List<DashboardGroupBalanceResponse> getGroupBalances() {
        return dashboard.getGroupBalances(currentUser.id());
    }
}
