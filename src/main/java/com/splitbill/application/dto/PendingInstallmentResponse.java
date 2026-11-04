package com.splitbill.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Proxima parcela/assinatura que so entra no mes novo se a pessoa quiser. */
public record PendingInstallmentResponse(
        UUID installmentGroupId,
        String description,
        BigDecimal amount,
        int installmentNumber,
        // null = assinatura (repete ate ser finalizada).
        Integer totalInstallments,
        boolean subscription
) {
}
