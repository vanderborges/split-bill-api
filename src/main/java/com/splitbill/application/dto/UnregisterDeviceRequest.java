package com.splitbill.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UnregisterDeviceRequest(@NotBlank @Size(max = 512) String token) {
}
