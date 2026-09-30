package com.splitbill.application.usecase;

import com.splitbill.application.dto.CreateMonthRequest;
import com.splitbill.application.dto.MonthResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.domain.valueobject.EventType;
import com.splitbill.domain.valueobject.MonthStatus;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.MonthJpaEntity;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupJpaRepository;
import com.splitbill.infrastructure.persistence.repository.MonthJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class MonthUseCase {

    private final MonthJpaRepository months;
    private final EventJpaRepository events;
    private final GroupJpaRepository groups;
    private final EventUseCase eventUseCase;
    private final GroupUseCase groupRules;

    public MonthUseCase(
            MonthJpaRepository months,
            EventJpaRepository events,
            GroupJpaRepository groups,
            EventUseCase eventUseCase,
            GroupUseCase groupRules
    ) {
        this.months = months;
        this.events = events;
        this.groups = groups;
        this.eventUseCase = eventUseCase;
        this.groupRules = groupRules;
    }

    @Transactional(readOnly = true)
    public List<MonthResponse> list() {
        return months.findAll().stream().map(this::toResponse).toList();
    }

    /**
     * O mês (mês/ano) é um período global, compartilhado por todos os
     * grupos - mas o evento mensal é por grupo, igual em
     * {@link EventUseCase#create}. Antes só existia um "grupo padrão"
     * implícito (primeiro grupo ativo), então qualquer grupo além desse
     * ficava sem conseguir criar evento: ou o mês já existia (criado por
     * outro grupo) e o create() rejeitava com "Month already exists", ou o
     * evento acabava indo parar no grupo errado.
     *
     * Exige só ser membro do grupo (não admin) - esse endpoint é usado pelo
     * fluxo de "primeira despesa do mês" de qualquer usuário, não pela tela
     * de administração de eventos (essa sim exige admin, em
     * {@link EventUseCase#create}).
     */
    @Transactional
    public MonthResponse create(CreateMonthRequest request, UUID requesterId) {
        groupRules.requireMembership(request.groupId(), requesterId);
        GroupJpaEntity group = groups.findById(request.groupId())
                .orElseThrow(() -> new DomainException("Group not found"));

        LocalDateTime now = LocalDateTime.now();
        MonthJpaEntity month = months.findByMonthAndYear(request.month(), request.year())
                .orElseGet(() -> {
                    MonthJpaEntity created = new MonthJpaEntity();
                    created.setId(UUID.randomUUID());
                    created.setMonth(request.month());
                    created.setYear(request.year());
                    created.setStatus(MonthStatus.OPEN);
                    created.setOpenedAt(now);
                    created.setCreatedAt(now);
                    return months.save(created);
                });

        events.findByMonthIdAndTypeAndGroupId(month.getId(), EventType.MONTHLY, group.getId())
                .ifPresentOrElse(
                        existing -> { },
                        () -> createMonthlyEvent(month, group, now)
                );

        return toResponse(month, group.getId());
    }

    @Transactional
    public MonthResponse close(UUID id, UUID requesterId) {
        MonthJpaEntity month = months.findById(id)
                .orElseThrow(() -> new DomainException("Month not found"));
        events.findFirstByMonthIdAndTypeOrderByCreatedAtAsc(month.getId(), EventType.MONTHLY)
                .ifPresentOrElse(
                        event -> eventUseCase.close(event.getId(), requesterId),
                        () -> {
                            month.setStatus(MonthStatus.CLOSED);
                            month.setClosedAt(LocalDateTime.now());
                        }
                );
        return toResponse(month);
    }

    @Transactional
    public MonthResponse reopen(UUID id, UUID requesterId) {
        MonthJpaEntity month = months.findById(id)
                .orElseThrow(() -> new DomainException("Month not found"));
        month.setStatus(MonthStatus.OPEN);
        month.setClosedAt(null);
        events.findFirstByMonthIdAndTypeOrderByCreatedAtAsc(month.getId(), EventType.MONTHLY)
                .ifPresent(event -> eventUseCase.reopen(event.getId(), requesterId));
        return toResponse(month);
    }

    private void createMonthlyEvent(MonthJpaEntity month, GroupJpaEntity group, LocalDateTime now) {
        EventJpaEntity event = new EventJpaEntity();
        event.setId(UUID.randomUUID());
        event.setName(String.format("%02d/%d", month.getMonth(), month.getYear()));
        event.setDescription("Evento mensal criado automaticamente");
        event.setType(EventType.MONTHLY);
        event.setStatus(EventStatus.OPEN);
        event.setMonth(month);
        event.setGroup(group);
        event.setCreatedAt(now);
        events.save(event);
    }

    private MonthResponse toResponse(MonthJpaEntity month) {
        UUID eventId = events.findFirstByMonthIdAndTypeOrderByCreatedAtAsc(month.getId(), EventType.MONTHLY)
                .map(EventJpaEntity::getId)
                .orElse(null);
        return new MonthResponse(
                month.getId(),
                eventId,
                month.getMonth(),
                month.getYear(),
                month.getStatus(),
                month.getOpenedAt(),
                month.getClosedAt()
        );
    }

    private MonthResponse toResponse(MonthJpaEntity month, UUID groupId) {
        UUID eventId = events.findByMonthIdAndTypeAndGroupId(month.getId(), EventType.MONTHLY, groupId)
                .map(EventJpaEntity::getId)
                .orElse(null);
        return new MonthResponse(
                month.getId(),
                eventId,
                month.getMonth(),
                month.getYear(),
                month.getStatus(),
                month.getOpenedAt(),
                month.getClosedAt()
        );
    }
}
