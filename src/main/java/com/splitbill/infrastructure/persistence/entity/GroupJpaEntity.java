package com.splitbill.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "groups")
public class GroupJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private UserJpaEntity createdBy;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_user_id")
    private UserJpaEntity receiver;

    // Dia do mes (1-31) em que o grupo abre automaticamente pra pagamento
    // o(s) evento(s) mensal(is) ainda OPEN e dispara o alerta de cobranca -
    // null desativa. Ver GroupAutoSettlementScheduler.
    @Column(name = "auto_settlement_day")
    private Integer autoSettlementDay;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UserJpaEntity getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UserJpaEntity createdBy) {
        this.createdBy = createdBy;
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

    public UserJpaEntity getReceiver() {
        return receiver;
    }

    public void setReceiver(UserJpaEntity receiver) {
        this.receiver = receiver;
    }

    public Integer getAutoSettlementDay() {
        return autoSettlementDay;
    }

    public void setAutoSettlementDay(Integer autoSettlementDay) {
        this.autoSettlementDay = autoSettlementDay;
    }
}
