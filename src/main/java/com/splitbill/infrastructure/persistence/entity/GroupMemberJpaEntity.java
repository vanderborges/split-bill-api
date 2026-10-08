package com.splitbill.infrastructure.persistence.entity;

import com.splitbill.domain.valueobject.GroupMemberRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "group_members")
public class GroupMemberJpaEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private GroupJpaEntity group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserJpaEntity user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GroupMemberRole role;

    @Column(nullable = false)
    private boolean active;

    /** Pessoa temporaria: so enxerga/participa de [temporaryEvent]. */
    @Column(nullable = false)
    private boolean temporary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "temporary_event_id")
    private EventJpaEntity temporaryEvent;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public GroupJpaEntity getGroup() {
        return group;
    }

    public void setGroup(GroupJpaEntity group) {
        this.group = group;
    }

    public UserJpaEntity getUser() {
        return user;
    }

    public void setUser(UserJpaEntity user) {
        this.user = user;
    }

    public GroupMemberRole getRole() {
        return role;
    }

    public void setRole(GroupMemberRole role) {
        this.role = role;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isTemporary() {
        return temporary;
    }

    public void setTemporary(boolean temporary) {
        this.temporary = temporary;
    }

    public EventJpaEntity getTemporaryEvent() {
        return temporaryEvent;
    }

    public void setTemporaryEvent(EventJpaEntity temporaryEvent) {
        this.temporaryEvent = temporaryEvent;
    }

    /**
     * Membro fixo participa de todos os eventos do grupo; temporario so do
     * evento ao qual esta amarrado.
     */
    public boolean participatesIn(UUID eventId) {
        return !temporary || (temporaryEvent != null && temporaryEvent.getId().equals(eventId));
    }
}
