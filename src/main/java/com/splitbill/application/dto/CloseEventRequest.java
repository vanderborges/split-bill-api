package com.splitbill.application.dto;

import java.util.UUID;

public record CloseEventRequest(
        UUID consolidateToEventId
) {
}
