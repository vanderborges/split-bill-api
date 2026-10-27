package com.splitbill.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Pessoa sem cadastro: so o nome. Com {@code eventId} ela entra como
 * temporaria daquele evento; sem, como integrante fixo do grupo.
 */
public record CreatePlaceholderRequest(
        @NotBlank @Size(max = 80) String nickname,
        UUID eventId,
        // true sem eventId = temporario do grupo ("Convidar temporario").
        Boolean temporary
) {
}
