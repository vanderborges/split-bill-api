package com.splitbill.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CreateMonthRequest(
        @Min(1) @Max(12) int month,
        @Min(2000) int year
) {
}
