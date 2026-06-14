package com.splitbill.infrastructure.persistence.repository;

import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupJpaRepository extends JpaRepository<GroupJpaEntity, UUID> {

    List<GroupJpaEntity> findByActiveTrue();

    Optional<GroupJpaEntity> findFirstByActiveTrueOrderByCreatedAtAsc();
}
