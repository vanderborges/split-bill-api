package com.splitbill.application.usecase;

import com.splitbill.application.dto.AddGroupMemberRequest;
import com.splitbill.application.dto.CreateGroupRequest;
import com.splitbill.application.dto.GroupMemberResponse;
import com.splitbill.application.dto.GroupResponse;
import com.splitbill.application.dto.UpdateGroupRequest;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.GroupMemberRole;
import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.GroupJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.ExpenseJpaRepository;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.domain.valueobject.EventStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class GroupUseCase {

    private static final int MAX_ADMINS_PER_GROUP = 2;

    private final GroupJpaRepository groups;
    private final GroupMemberJpaRepository members;
    private final UserJpaRepository users;
    private final EventJpaRepository events;
    private final ExpenseJpaRepository expenses;

    public GroupUseCase(GroupJpaRepository groups, GroupMemberJpaRepository members, UserJpaRepository users, EventJpaRepository events, ExpenseJpaRepository expenses) {
        this.groups = groups;
        this.members = members;
        this.users = users;
        this.events = events;
        this.expenses = expenses;
    }

    @Transactional(readOnly = true)
    public List<GroupResponse> listByUser(UUID viewerUserId) {
        if (viewerUserId == null) {
            return groups.findByActiveTrue().stream().map(this::toResponse).toList();
        }
        return members.findByUserIdAndActiveTrue(viewerUserId).stream()
                .map(GroupMemberJpaEntity::getGroup)
                .filter(GroupJpaEntity::isActive)
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public GroupResponse create(CreateGroupRequest request, UUID creatorId) {
        UserJpaEntity admin = activeUser(creatorId);
        LocalDateTime now = LocalDateTime.now();

        GroupJpaEntity group = new GroupJpaEntity();
        group.setId(UUID.randomUUID());
        group.setName(request.name());
        group.setDescription(request.description());
        group.setCreatedBy(admin);
        group.setActive(true);
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        GroupJpaEntity savedGroup = groups.save(group);

        GroupMemberJpaEntity member = new GroupMemberJpaEntity();
        member.setId(UUID.randomUUID());
        member.setGroup(savedGroup);
        member.setUser(admin);
        member.setRole(GroupMemberRole.ADMIN);
        member.setActive(true);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        members.save(member);

        return toResponse(savedGroup);
    }

    @Transactional
    public GroupResponse update(UUID groupId, UpdateGroupRequest request, UUID requesterId) {
        requireAdmin(groupId, requesterId);
        GroupJpaEntity group = groups.findById(groupId)
                .orElseThrow(() -> new DomainException("Group not found"));
        group.setName(request.name());
        group.setDescription(request.description());
        group.setUpdatedAt(LocalDateTime.now());
        return toResponse(group);
    }

    /**
     * Recebedor padrão do grupo: usado pela sugestão de pagamentos (ver
     * {@link MonthlyReportUseCase#getPaymentSuggestions}) em todos os
     * eventos do grupo - todo devedor manda o valor direto pra essa
     * pessoa, em vez do acerto "quem deve pra quem" calculado
     * normalmente. {@code receiverUserId} null remove o recebedor.
     */
    @Transactional
    public GroupResponse setReceiver(UUID groupId, UUID receiverUserId, UUID requesterId) {
        requireAdmin(groupId, requesterId);
        GroupJpaEntity group = groups.findById(groupId)
                .orElseThrow(() -> new DomainException("Group not found"));
        if (receiverUserId == null) {
            group.setReceiver(null);
            group.setUpdatedAt(LocalDateTime.now());
            return toResponse(group);
        }
        GroupMemberJpaEntity receiverMember = members.findByGroupIdAndUserId(groupId, receiverUserId)
                .filter(GroupMemberJpaEntity::isActive)
                .orElseThrow(() -> new DomainException("Receiver must be an active member of the group"));
        if (receiverMember.isTemporary()) {
            throw new DomainException("Temporary members cannot be the group receiver");
        }
        UserJpaEntity receiver = users.findById(receiverUserId)
                .orElseThrow(() -> new DomainException("User not found"));
        group.setReceiver(receiver);
        group.setUpdatedAt(LocalDateTime.now());
        return toResponse(group);
    }

    /**
     * Dia do mês (1-31) em que o grupo abre automaticamente pra pagamento
     * o(s) evento(s) mensal(is) ainda OPEN e dispara o alerta de cobrança -
     * ver {@link com.splitbill.infrastructure.scheduling.GroupAutoSettlementScheduler}.
     * {@code day} null desativa. Meses mais curtos que o dia escolhido
     * (ex.: 31 em fevereiro) disparam no último dia do mês.
     */
    @Transactional
    public GroupResponse setAutoSettlementDay(UUID groupId, Integer day, UUID requesterId) {
        requireAdmin(groupId, requesterId);
        if (day != null && (day < 1 || day > 31)) {
            throw new DomainException("Day must be between 1 and 31");
        }
        GroupJpaEntity group = groups.findById(groupId)
                .orElseThrow(() -> new DomainException("Group not found"));
        group.setAutoSettlementDay(day);
        group.setUpdatedAt(LocalDateTime.now());
        return toResponse(group);
    }

    /**
     * Integrantes ativos do grupo. Com {@code eventId}: so quem participa
     * desse evento (fixos + temporarios amarrados a ele) - e a lista usada
     * pra escolher participantes/pagadores. Sem {@code eventId}: fixos +
     * temporarios cujo evento ainda nao fechou (fechado = some da lista,
     * mas continua no grupo; ver {@link #listTemporaryMembers}). Um
     * temporario so enxerga as pessoas do proprio evento.
     */
    @Transactional(readOnly = true)
    public List<GroupMemberResponse> listMembers(UUID groupId, UUID viewerUserId, UUID eventId) {
        requireMembership(groupId, viewerUserId);
        UUID scopeEventId = eventId;
        if (viewerUserId != null) {
            GroupMemberJpaEntity viewer = members.findByGroupIdAndUserId(groupId, viewerUserId).orElse(null);
            if (viewer != null && viewer.isTemporary() && viewer.getTemporaryEvent() != null) {
                scopeEventId = viewer.getTemporaryEvent().getId();
            }
        }
        final UUID filterEventId = scopeEventId;
        return members.findByGroupIdAndActiveTrue(groupId).stream()
                .filter(member -> filterEventId != null
                        ? member.participatesIn(filterEventId)
                        : !member.isTemporary() || isTemporaryEventOpen(member))
                .map(this::toMemberResponse)
                .toList();
    }

    /** Admin: todos os temporarios do grupo (inclusive os de eventos ja fechados, ocultos nas listas). */
    @Transactional(readOnly = true)
    public List<GroupMemberResponse> listTemporaryMembers(UUID groupId, UUID requesterId) {
        requireAdmin(groupId, requesterId);
        return members.findByGroupIdAndActiveTrue(groupId).stream()
                .filter(GroupMemberJpaEntity::isTemporary)
                .map(this::toMemberResponse)
                .toList();
    }

    /**
     * Admin: reativa uma pessoa temporaria num outro evento (aberto) do
     * grupo - ela passa a enxergar/participar so desse novo evento.
     */
    @Transactional
    public GroupMemberResponse assignTemporaryEvent(UUID groupId, UUID userId, UUID eventId, UUID requesterId) {
        requireAdmin(groupId, requesterId);
        GroupMemberJpaEntity member = members.findByGroupIdAndUserId(groupId, userId)
                .filter(GroupMemberJpaEntity::isActive)
                .orElseThrow(() -> new DomainException("Group member not found"));
        if (!member.isTemporary()) {
            throw new DomainException("Only temporary members can be moved to another event");
        }
        member.setTemporaryEvent(requireOpenEventOfGroup(groupId, eventId));
        member.setUpdatedAt(LocalDateTime.now());
        return toMemberResponse(member);
    }

    /** Evento do grupo, nao apagado e nao fechado - destino valido pra um temporario. */
    public EventJpaEntity requireOpenEventOfGroup(UUID groupId, UUID eventId) {
        EventJpaEntity event = events.findById(eventId)
                .orElseThrow(() -> new DomainException("Event not found"));
        if (!event.getGroup().getId().equals(groupId) || event.getDeletedAt() != null) {
            throw new DomainException("Event not found");
        }
        if (event.getStatus() == EventStatus.CLOSED) {
            throw new DomainException("Event is already closed");
        }
        return event;
    }

    /**
     * Acesso a um evento: precisa ser integrante ativo do grupo e, se for
     * temporario, o evento tem que ser o dele.
     */
    public void requireEventAccess(EventJpaEntity event, UUID userId) {
        if (userId == null) {
            return;
        }
        GroupMemberJpaEntity member = members.findByGroupIdAndUserId(event.getGroup().getId(), userId)
                .filter(GroupMemberJpaEntity::isActive)
                .orElseThrow(() -> new DomainException("User does not belong to this group"));
        if (!member.participatesIn(event.getId())) {
            throw new DomainException("Temporary members can only access their own event");
        }
    }

    private boolean isTemporaryEventOpen(GroupMemberJpaEntity member) {
        EventJpaEntity event = member.getTemporaryEvent();
        return event != null && event.getDeletedAt() == null && event.getStatus() != EventStatus.CLOSED;
    }

    @Transactional
    public GroupMemberResponse addMember(UUID groupId, AddGroupMemberRequest request, UUID requesterId) {
        requireAdmin(groupId, requesterId);
        GroupJpaEntity group = groups.findById(groupId)
                .orElseThrow(() -> new DomainException("Group not found"));
        UserJpaEntity user = activeUser(request.userId());

        if (request.role() == GroupMemberRole.ADMIN
                && members.countByGroupIdAndRoleAndActiveTrue(groupId, GroupMemberRole.ADMIN) >= MAX_ADMINS_PER_GROUP
                && !members.existsByGroupIdAndUserIdAndRoleAndActiveTrue(groupId, request.userId(), GroupMemberRole.ADMIN)) {
            throw new DomainException("Group can have at most two admins");
        }

        GroupMemberJpaEntity member = members.findByGroupIdAndUserId(groupId, request.userId())
                .orElseGet(() -> {
                    GroupMemberJpaEntity created = new GroupMemberJpaEntity();
                    created.setId(UUID.randomUUID());
                    created.setGroup(group);
                    created.setUser(user);
                    created.setCreatedAt(LocalDateTime.now());
                    return created;
                });
        if (member.getRole() == GroupMemberRole.ADMIN
                && request.role() != GroupMemberRole.ADMIN
                && members.countByGroupIdAndRoleAndActiveTrue(groupId, GroupMemberRole.ADMIN) <= 1) {
            throw new DomainException("Group must have at least one admin");
        }
        member.setRole(request.role());
        member.setActive(true);
        member.setUpdatedAt(LocalDateTime.now());
        return toMemberResponse(members.save(member));
    }

    @Transactional
    public void delete(UUID groupId, UUID adminUserId) {
        requireAdmin(groupId, adminUserId);
        GroupJpaEntity group = groups.findById(groupId)
                .orElseThrow(() -> new DomainException("Group not found"));
        group.setActive(false);
        group.setUpdatedAt(LocalDateTime.now());
    }

    @Transactional
    public void removeMember(UUID groupId, UUID userId, UUID requesterId) {
        requireAdmin(groupId, requesterId);
        deactivateMember(groupId, userId);
    }

    @Transactional
    public void leave(UUID groupId, UUID userId) {
        requireMembership(groupId, userId);
        if (hasPendingBalance(groupId, userId)) {
            throw new DomainException("Settle your open event balances before leaving the group");
        }
        deactivateMember(groupId, userId);
    }

    public void requireMembership(UUID groupId, UUID userId) {
        if (userId == null) {
            return;
        }
        if (!members.existsByGroupIdAndUserIdAndActiveTrue(groupId, userId)) {
            throw new DomainException("User does not belong to this group");
        }
    }

    public void requireAdmin(UUID groupId, UUID userId) {
        if (userId == null || !members.existsByGroupIdAndUserIdAndRoleAndActiveTrue(groupId, userId, GroupMemberRole.ADMIN)) {
            throw new DomainException("Only group admins can perform this action");
        }
    }

    private void deactivateMember(UUID groupId, UUID userId) {
        GroupMemberJpaEntity member = members.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new DomainException("Group member not found"));
        if (!member.isActive()) {
            return;
        }
        if (member.getRole() == GroupMemberRole.ADMIN
                && members.countByGroupIdAndRoleAndActiveTrue(groupId, GroupMemberRole.ADMIN) <= 1) {
            throw new DomainException("Group must have at least one admin");
        }
        member.setActive(false);
        member.setUpdatedAt(LocalDateTime.now());
    }

    private boolean hasPendingBalance(UUID groupId, UUID userId) {
        return events.findByGroupIdAndDeletedAtIsNull(groupId).stream()
                .filter(event -> event.getStatus() != EventStatus.CLOSED)
                .map(event -> balanceForEvent(event, userId))
                .anyMatch(balance -> balance.compareTo(java.math.BigDecimal.ZERO) != 0);
    }

    private java.math.BigDecimal balanceForEvent(EventJpaEntity event, UUID userId) {
        return expenses.findByEventIdAndDeletedAtIsNull(event.getId()).stream()
                .map(expense -> {
                    java.math.BigDecimal paid = expense.getPayers().stream()
                            .filter(payer -> payer.getUser().getId().equals(userId))
                            .map(payer -> payer.getPaidAmount())
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                    java.math.BigDecimal consumed = expense.getParticipants().stream()
                            .filter(participant -> participant.getUser().getId().equals(userId))
                            .map(participant -> participant.getShareAmount())
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                    return paid.subtract(consumed);
                })
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    private UserJpaEntity activeUser(UUID userId) {
        UserJpaEntity user = users.findById(userId)
                .orElseThrow(() -> new DomainException("User not found"));
        if (!user.isActive() || user.getDeletedAt() != null) {
            throw new DomainException("User not found");
        }
        return user;
    }

    private GroupResponse toResponse(GroupJpaEntity group) {
        return new GroupResponse(
                group.getId(),
                group.getName(),
                group.getDescription(),
                group.getCreatedBy().getId(),
                group.isActive(),
                group.getReceiver() == null ? null : group.getReceiver().getId(),
                group.getReceiver() == null ? null : group.getReceiver().getNickname(),
                group.getAutoSettlementDay()
        );
    }

    private GroupMemberResponse toMemberResponse(GroupMemberJpaEntity member) {
        return GroupMemberResponse.from(member);
    }
}
