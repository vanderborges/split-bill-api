package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.CreateMonthRequest;
import com.splitbill.application.dto.MonthResponse;
import com.splitbill.application.usecase.MonthUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/months")
public class MonthController {

    private final MonthUseCase months;

    public MonthController(MonthUseCase months) {
        this.months = months;
    }

    @GetMapping
    public List<MonthResponse> list() {
        return months.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MonthResponse create(@Valid @RequestBody CreateMonthRequest request) {
        return months.create(request);
    }

    @PutMapping("/{id}/close")
    public MonthResponse close(@PathVariable UUID id) {
        return months.close(id);
    }

    @PutMapping("/{id}/reopen")
    public MonthResponse reopen(@PathVariable UUID id) {
        return months.reopen(id);
    }
}
