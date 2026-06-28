package com.splitbill.domain.valueobject;

import java.util.UUID;

public record ParticipantSplit(UUID userId, int shareCount, String shareDescription) {
}
