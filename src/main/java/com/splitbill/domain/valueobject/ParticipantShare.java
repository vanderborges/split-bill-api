package com.splitbill.domain.valueobject;

import java.math.BigDecimal;
import java.util.UUID;

public record ParticipantShare(UUID userId, BigDecimal amount, int shareCount, String shareDescription) {
    public ParticipantShare(UUID userId, BigDecimal amount) {
        this(userId, amount, 1, null);
    }
}
