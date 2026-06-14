package com.splitbill.application.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateExpenseCategoryRequest(@NotBlank String name) {
}
