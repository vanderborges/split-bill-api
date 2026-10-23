package com.splitbill.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterDeviceRequest(
        @NotBlank @Size(max = 512) String token,
        @NotBlank @Pattern(regexp = "ANDROID|IOS") String platform
) {
}
