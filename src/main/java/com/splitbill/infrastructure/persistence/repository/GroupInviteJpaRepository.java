package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.infrastructure.persistence.entity.GroupInviteJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GroupInviteJpaRepository extends JpaRepository<GroupInviteJpaEntity, UUID> {

    // Convite normal do grupo (sem evento) - convites temporarios ficam de fora.
    Optional<GroupInviteJpaEntity> findFirstByGroupIdAndEventIsNullAndActiveTrueOrderByCreatedAtDesc(UUID groupId);

    Optional<GroupInviteJpaEntity> findFirstByEventIdAndActiveTrueOrderByCreatedAtDesc(UUID eventId);
}
