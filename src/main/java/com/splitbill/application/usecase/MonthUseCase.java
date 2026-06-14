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

    public MonthUseCase(
            MonthJpaRepository months,
            EventJpaRepository events,
            GroupJpaRepository groups,
            EventUseCase eventUseCase
    ) {
        this.months = months;
        this.events = events;
        this.groups = groups;
        this.eventUseCase = eventUseCase;
    }

    @Transactional(readOnly = true)
    public List<MonthResponse> list() {
        return months.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public MonthResponse create(CreateMonthRequest request) {
        months.findByMonthAndYear(request.month(), request.year())
                .ifPresent(month -> {
                    throw new DomainException("Month already exists");
                });

        LocalDateTime now = LocalDateTime.now();
        MonthJpaEntity month = new MonthJpaEntity();
        month.setId(UUID.randomUUID());
        month.setMonth(request.month());
        month.setYear(request.year());
        month.setStatus(MonthStatus.OPEN);
        month.setOpenedAt(now);
        month.setCreatedAt(now);

        MonthJpaEntity savedMonth = months.save(month);
        createMonthlyEvent(savedMonth, now);

        return toResponse(savedMonth);
    }

    @Transactional
    public MonthResponse close(UUID id) {
        MonthJpaEntity month = months.findById(id)
                .orElseThrow(() -> new DomainException("Month not found"));
        events.findFirstByMonthIdAndTypeOrderByCreatedAtAsc(month.getId(), EventType.MONTHLY)
                .ifPresentOrElse(
                        event -> eventUseCase.close(event.getId(), null),
                        () -> {
                            month.setStatus(MonthStatus.CLOSED);
                            month.setClosedAt(LocalDateTime.now());
                        }
                );
        return toResponse(month);
    }

    @Transactional
    public MonthResponse reopen(UUID id) {
        MonthJpaEntity month = months.findById(id)
                .orElseThrow(() -> new DomainException("Month not found"));
        month.setStatus(MonthStatus.OPEN);
        month.setClosedAt(null);
        events.findFirstByMonthIdAndTypeOrderByCreatedAtAsc(month.getId(), EventType.MONTHLY)
                .ifPresent(event -> {
                    event.setStatus(EventStatus.OPEN);
                    event.setClosedAt(null);
                });
        return toResponse(month);
    }

    private void createMonthlyEvent(MonthJpaEntity month, LocalDateTime now) {
        EventJpaEntity event = new EventJpaEntity();
        event.setId(UUID.randomUUID());
        event.setName(String.format("%02d/%d", month.getMonth(), month.getYear()));
        event.setDescription("Evento mensal criado automaticamente");
        event.setType(EventType.MONTHLY);
        event.setStatus(EventStatus.OPEN);
        event.setMonth(month);
        event.setGroup(defaultGroup());
        event.setCreatedAt(now);
        events.save(event);
    }

    private GroupJpaEntity defaultGroup() {
        return groups.findFirstByActiveTrueOrderByCreatedAtAsc()
                .orElseThrow(() -> new DomainException("Group not found"));
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
}
