package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.domain.valueobject.EventType;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface EventJpaRepository extends JpaRepository<EventJpaEntity, UUID> {

    Optional<EventJpaEntity> findFirstByMonthIdAndTypeOrderByCreatedAtAsc(UUID monthId, EventType type);

    Optional<EventJpaEntity> findByMonthIdAndTypeAndGroupId(UUID monthId, EventType type, UUID groupId);

    List<EventJpaEntity> findByGroupIdAndDeletedAtIsNull(UUID groupId);

    List<EventJpaEntity> findByGroupIdInAndDeletedAtIsNull(List<UUID> groupIds);
}
