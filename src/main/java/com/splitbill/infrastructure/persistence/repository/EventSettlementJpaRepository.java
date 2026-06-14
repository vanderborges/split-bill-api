package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.infrastructure.persistence.entity.EventSettlementJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventSettlementJpaRepository extends JpaRepository<EventSettlementJpaEntity, UUID> {

    List<EventSettlementJpaEntity> findByEventId(UUID eventId);

    Optional<EventSettlementJpaEntity> findByEventIdAndUserId(UUID eventId, UUID userId);
}
