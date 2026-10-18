package com.splitbill.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenamePlaceholderRequest(
        @NotBlank @Size(max = 80) String nickname
) {
}
