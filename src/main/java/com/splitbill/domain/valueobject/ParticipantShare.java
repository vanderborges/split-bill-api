package com.splitbill.domain.valueobject;

import java.math.BigDecimal;
import java.util.UUID;

public record ParticipantShare(UUID userId, BigDecimal amount) {
}
