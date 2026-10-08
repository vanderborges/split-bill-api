package com.splitbill.application.dto;

import com.splitbill.domain.valueobject.GroupMemberRole;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;

import java.util.UUID;

public record GroupMemberResponse(
        UUID id,
        UUID groupId,
        UUID userId,
        String nickname,
        GroupMemberRole role,
        boolean active,
        // Pessoa temporaria: so participa do evento indicado.
        boolean temporary,
        UUID temporaryEventId,
        String temporaryEventName,
        String temporaryEventStatus
) {
    public static GroupMemberResponse from(GroupMemberJpaEntity member) {
        EventJpaEntity event = member.getTemporaryEvent();
        return new GroupMemberResponse(
                member.getId(),
                member.getGroup().getId(),
                member.getUser().getId(),
                member.getUser().getNickname(),
                member.getRole(),
                member.isActive(),
                member.isTemporary(),
                event == null ? null : event.getId(),
                event == null ? null : event.getName(),
                event == null ? null : event.getStatus().name()
        );
    }
}
