package com.splitbill.application.dto;

import java.util.UUID;

/**
 * Corpo opcional do "entrar pelo convite": {@code placeholderUserId} e o
 * nome pre-cadastrado que a pessoa disse ser ela. Null = entra como pessoa
 * nova.
 */
public record JoinInviteRequest(
        UUID placeholderUserId
) {
}
