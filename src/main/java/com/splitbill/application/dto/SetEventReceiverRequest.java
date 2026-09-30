package com.splitbill.application.dto;

import java.util.UUID;

public record SetEventReceiverRequest(
        UUID userId
) {
}
