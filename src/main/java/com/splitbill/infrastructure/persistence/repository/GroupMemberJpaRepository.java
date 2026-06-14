package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.domain.valueobject.GroupMemberRole;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupMemberJpaRepository extends JpaRepository<GroupMemberJpaEntity, UUID> {

    List<GroupMemberJpaEntity> findByUserIdAndActiveTrue(UUID userId);

    List<GroupMemberJpaEntity> findByGroupIdAndActiveTrue(UUID groupId);

    Optional<GroupMemberJpaEntity> findByGroupIdAndUserId(UUID groupId, UUID userId);

    long countByGroupIdAndRoleAndActiveTrue(UUID groupId, GroupMemberRole role);

    boolean existsByGroupIdAndUserIdAndActiveTrue(UUID groupId, UUID userId);

    boolean existsByGroupIdAndUserIdAndRoleAndActiveTrue(UUID groupId, UUID userId, GroupMemberRole role);
}
