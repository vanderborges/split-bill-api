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
import com.splitbill.infrastructure.persistence.entity.ExpenseJpaEntity;
import com.splitbill.domain.valueobject.EventStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class GroupUseCase {

    private static final int MAX_ADMINS_PER_GROUP = 3;

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
        return listByUser(viewerUserId, false);
    }

    /**
     * {@code includeRemoved} (Extrato): tambem os grupos de onde a pessoa
     * saiu/foi removida mas em que tem despesa em algum evento - vem com
     * {@code viewerRemoved = true} e da acesso so de leitura.
     */
    @Transactional(readOnly = true)
    public List<GroupResponse> listByUser(UUID viewerUserId, boolean includeRemoved) {
        if (viewerUserId == null) {
            return groups.findByActiveTrue().stream().map(this::toResponse).toList();
        }
        List<GroupResponse> result = new ArrayList<>(members.findByUserIdAndActiveTrue(viewerUserId).stream()
                .map(GroupMemberJpaEntity::getGroup)
                .filter(GroupJpaEntity::isActive)
                .map(this::toResponse)
                .toList());
        if (includeRemoved) {
            members.findByUserId(viewerUserId).stream()
                    .filter(member -> !member.isActive())
                    .filter(member -> member.getGroup().isActive())
                    .filter(member -> !expenses.findInvolvedEventStatuses(
                            member.getGroup().getId(), viewerUserId).isEmpty())
                    .map(member -> toResponse(member.getGroup(), true))
                    .forEach(result::add);
        }
        return result;
    }

    /**
     * Quem pode VER o evento (relatorio, saldo, pagamentos, Extrato):
     * integrante fixo ativo sempre; temporario de um evento, o proprio
     * evento; temporario do grupo, eventos abertos e os em que tem despesa;
     * quem saiu/foi removido, so os eventos em que tem despesa (leitura).
     */
    public boolean canSeeEvent(GroupMemberJpaEntity member, EventJpaEntity event, Set<UUID> involvedEventIds) {
        if (member == null) {
            return false;
        }
        boolean involved = involvedEventIds.contains(event.getId());
        if (!member.isActive()) {
            return involved;
        }
        if (!member.isTemporary()) {
            return true;
        }
        if (!member.isGroupTemporary()) {
            return member.participatesIn(event.getId()) || involved;
        }
        return event.getStatus() == EventStatus.OPEN || involved;
    }

    public Set<UUID> involvedEventIds(UUID userId) {
        return new java.util.HashSet<>(expenses.findInvolvedEventIds(userId));
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
                .orElseThrow(() -> new DomainException("Grupo não encontrado."));
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
                .orElseThrow(() -> new DomainException("Grupo não encontrado."));
        if (receiverUserId == null) {
            group.setReceiver(null);
            group.setUpdatedAt(LocalDateTime.now());
            return toResponse(group);
        }
        GroupMemberJpaEntity receiverMember = members.findByGroupIdAndUserId(groupId, receiverUserId)
                .filter(GroupMemberJpaEntity::isActive)
                .orElseThrow(() -> new DomainException("Quem recebe precisa ser integrante ativo do grupo."));
        if (receiverMember.isTemporary()) {
            throw new DomainException("Convidados temporários não podem ser o recebedor do grupo.");
        }
        UserJpaEntity receiver = users.findById(receiverUserId)
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));
        if (receiver.isPlaceholder()) {
            // Sem cadastro = sem chave PIX pra receber.
            throw new DomainException("Pessoa sem cadastro não pode ser o recebedor do grupo.");
        }
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
            throw new DomainException("O dia precisa estar entre 1 e 31.");
        }
        GroupJpaEntity group = groups.findById(groupId)
                .orElseThrow(() -> new DomainException("Grupo não encontrado."));
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
        return listMembers(groupId, viewerUserId, eventId, false);
    }

    /**
     * {@code includeRemoved} (filtros, ex.: pessoa do Extrato geral): no fim,
     * quem saiu/foi removido mas tem despesa em algum evento do grupo, com
     * {@code active = false}.
     */
    @Transactional(readOnly = true)
    public List<GroupMemberResponse> listMembers(
            UUID groupId, UUID viewerUserId, UUID eventId, boolean includeRemoved) {
        requireMembership(groupId, viewerUserId);
        UUID scopeEventId = eventId;
        if (viewerUserId != null) {
            GroupMemberJpaEntity viewer = members.findByGroupIdAndUserId(groupId, viewerUserId).orElse(null);
            if (viewer != null && viewer.isTemporary() && viewer.getTemporaryEvent() != null) {
                scopeEventId = viewer.getTemporaryEvent().getId();
            }
        }
        final UUID filterEventId = scopeEventId;
        List<GroupMemberResponse> listed = new ArrayList<>(members.findByGroupIdAndActiveTrue(groupId).stream()
                .filter(member -> filterEventId != null
                        ? member.participatesIn(filterEventId) && (!member.isGroupTemporary() || isTemporaryListed(member))
                        : isTemporaryListed(member))
                .map(this::toMemberResponse)
                .toList());
        if (filterEventId != null) {
            listed.addAll(removedMembersWithExpensesIn(groupId, filterEventId, listed));
        } else if (includeRemoved) {
            Set<UUID> listedUserIds = listed.stream()
                    .map(GroupMemberResponse::userId)
                    .collect(Collectors.toSet());
            members.findByGroupId(groupId).stream()
                    .filter(member -> !member.isActive())
                    .filter(member -> !listedUserIds.contains(member.getUser().getId()))
                    .filter(member -> member.getUser().getDeletedAt() == null)
                    .filter(member -> !expenses.findInvolvedEventStatuses(
                            groupId, member.getUser().getId()).isEmpty())
                    .map(this::toMemberResponse)
                    .forEach(listed::add);
        }
        return listed;
    }

    /**
     * Quem foi removido do grupo mas tem despesa neste evento continua
     * aparecendo nele (form de despesa) ate o evento acabar - senao, ao
     * editar uma despesa dele, ele sairia da divisao sem ninguem pedir.
     * Vem com {@code active = false}.
     */
    private List<GroupMemberResponse> removedMembersWithExpensesIn(
            UUID groupId, UUID eventId, List<GroupMemberResponse> alreadyListed) {
        Set<UUID> listedUserIds = alreadyListed.stream()
                .map(GroupMemberResponse::userId)
                .collect(Collectors.toSet());
        Set<UUID> expenseUserIds = new LinkedHashSet<>();
        for (ExpenseJpaEntity expense : expenses.findByEventIdAndDeletedAtIsNull(eventId)) {
            expense.getParticipants().forEach(participant -> expenseUserIds.add(participant.getUser().getId()));
            expense.getPayers().forEach(payer -> expenseUserIds.add(payer.getUser().getId()));
        }
        return expenseUserIds.stream()
                .filter(userId -> !listedUserIds.contains(userId))
                .map(userId -> members.findByGroupIdAndUserId(groupId, userId).orElse(null))
                .filter(member -> member != null && !member.isActive())
                .filter(member -> member.getUser().getDeletedAt() == null)
                .map(this::toMemberResponse)
                .toList();
    }

    /** Admin: todos os temporarios do grupo (inclusive os de eventos ja fechados, ocultos nas listas). */
    @Transactional(readOnly = true)
    public List<GroupMemberResponse> listTemporaryMembers(UUID groupId, UUID requesterId) {
        requireAdmin(groupId, requesterId);
        return members.findByGroupIdAndActiveTrue(groupId).stream()
                .filter(GroupMemberJpaEntity::isTemporary)
                // Sai da lista quando os eventos dele fecham: o do grupo,
                // quando todos os eventos com despesa dele fecham; o de um
                // evento (modelo antigo), quando esse evento fecha.
                .filter(this::isTemporaryListed)
                .map(this::toMemberResponse)
                .toList();
    }

    /**
     * Fixo: sempre. Temporario de um evento: enquanto o evento nao fecha.
     * Temporario do grupo: enquanto nao tem despesa ou tem alguma em
     * evento ainda nao fechado.
     */
    public boolean isTemporaryListed(GroupMemberJpaEntity member) {
        if (!member.isTemporary()) {
            return true;
        }
        if (!member.isGroupTemporary()) {
            return isTemporaryEventOpen(member);
        }
        List<EventStatus> statuses = expenses.findInvolvedEventStatuses(
                member.getGroup().getId(), member.getUser().getId());
        return statuses.isEmpty() || statuses.stream().anyMatch(status -> status != EventStatus.CLOSED);
    }

    /**
     * Admin: integrante fixo (ou admin) vira temporario do grupo - continua
     * nos eventos abertos e sai do grupo quando o ultimo evento em que tem
     * despesa for concluido ({@link #releaseFinishedTemporaries}).
     */
    @Transactional
    public GroupMemberResponse makeTemporary(UUID groupId, UUID userId, UUID requesterId) {
        requireAdmin(groupId, requesterId);
        GroupMemberJpaEntity member = members.findByGroupIdAndUserId(groupId, userId)
                .filter(GroupMemberJpaEntity::isActive)
                .orElseThrow(() -> new DomainException("Integrante do grupo não encontrado."));
        if (member.isTemporary()) {
            throw new DomainException("Essa pessoa já é temporária no grupo.");
        }
        if (member.getRole() == GroupMemberRole.ADMIN
                && members.countByGroupIdAndRoleAndActiveTrue(groupId, GroupMemberRole.ADMIN) <= 1) {
            throw new DomainException("O grupo precisa ter ao menos um administrador.");
        }
        member.setRole(GroupMemberRole.MEMBER);
        member.setTemporary(true);
        member.setTemporaryEvent(null);
        member.setUpdatedAt(LocalDateTime.now());
        return toMemberResponse(member);
    }

    /**
     * Depois de concluir um evento: temporario que tinha despesa e agora so
     * tem despesa em eventos fechados sai do grupo (fica como removido, com
     * leitura do Extrato desses eventos). Temporario sem despesa nenhuma
     * continua (ainda pode ser incluido em algo).
     */
    @Transactional
    public void releaseFinishedTemporaries(UUID groupId) {
        LocalDateTime now = LocalDateTime.now();
        for (GroupMemberJpaEntity member : members.findByGroupIdAndActiveTrue(groupId)) {
            if (!member.isTemporary()) {
                continue;
            }
            List<EventStatus> statuses = expenses.findInvolvedEventStatuses(groupId, member.getUser().getId());
            boolean finished = member.isGroupTemporary()
                    ? !statuses.isEmpty() && statuses.stream().allMatch(status -> status == EventStatus.CLOSED)
                    : !isTemporaryEventOpen(member)
                            && statuses.stream().allMatch(status -> status == EventStatus.CLOSED);
            if (finished) {
                member.setActive(false);
                member.setUpdatedAt(now);
            }
        }
    }

    /** Admin: o temporario passa a ser integrante fixo do grupo. */
    @Transactional
    public GroupMemberResponse promoteTemporary(UUID groupId, UUID userId, UUID requesterId) {
        requireAdmin(groupId, requesterId);
        GroupMemberJpaEntity member = members.findByGroupIdAndUserId(groupId, userId)
                .filter(GroupMemberJpaEntity::isActive)
                .orElseThrow(() -> new DomainException("Integrante do grupo não encontrado."));
        if (!member.isTemporary()) {
            throw new DomainException("Essa pessoa já é integrante do grupo.");
        }
        // So entra de vez no grupo quem ja criou conta (escolheu o nome no
        // convite); so o nome, sem cadastro, continua temporario.
        if (member.getUser().isPlaceholder()) {
            throw new DomainException("Só é possível tornar integrante quem já criou conta no app.");
        }
        member.setTemporary(false);
        member.setTemporaryEvent(null);
        member.setUpdatedAt(LocalDateTime.now());
        return toMemberResponse(member);
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
                .orElseThrow(() -> new DomainException("Integrante do grupo não encontrado."));
        if (!member.isTemporary()) {
            throw new DomainException("Apenas convidados temporários podem ser incluídos em outro evento.");
        }
        member.setTemporaryEvent(requireOpenEventOfGroup(groupId, eventId));
        member.setUpdatedAt(LocalDateTime.now());
        return toMemberResponse(member);
    }

    /** Evento do grupo, nao apagado e nao fechado - destino valido pra um temporario. */
    public EventJpaEntity requireOpenEventOfGroup(UUID groupId, UUID eventId) {
        EventJpaEntity event = events.findById(eventId)
                .orElseThrow(() -> new DomainException("Evento não encontrado."));
        if (!event.getGroup().getId().equals(groupId) || event.getDeletedAt() != null) {
            throw new DomainException("Evento não encontrado.");
        }
        if (event.getStatus() == EventStatus.CLOSED) {
            throw new DomainException("O evento já está fechado.");
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
                .orElseThrow(() -> new DomainException("O usuário não faz parte deste grupo."));
        // Leitura (relatorio, saldo, pagamentos): ver canSeeEvent - quem saiu
        // do grupo continua vendo os eventos em que tem despesa.
        if (!canSeeEvent(member, event, involvedEventIds(userId))) {
            throw new DomainException(member.isActive()
                    ? "Convidados temporários só acessam os eventos de que participam."
                    : "O usuário não faz parte deste grupo.");
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
                .orElseThrow(() -> new DomainException("Grupo não encontrado."));
        UserJpaEntity user = activeUser(request.userId());
        if (user.isPlaceholder() && request.role() == GroupMemberRole.ADMIN) {
            throw new DomainException("Pessoa sem cadastro não pode ser administradora do grupo.");
        }

        if (request.role() == GroupMemberRole.ADMIN
                && members.countByGroupIdAndRoleAndActiveTrue(groupId, GroupMemberRole.ADMIN) >= MAX_ADMINS_PER_GROUP
                && !members.existsByGroupIdAndUserIdAndRoleAndActiveTrue(groupId, request.userId(), GroupMemberRole.ADMIN)) {
            throw new DomainException("O grupo pode ter no máximo " + MAX_ADMINS_PER_GROUP + " administradores.");
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
            throw new DomainException("O grupo precisa ter ao menos um administrador.");
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
                .orElseThrow(() -> new DomainException("Grupo não encontrado."));
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
        List<String> pendingEvents = eventsWithPendingBalance(groupId, userId);
        if (!pendingEvents.isEmpty()) {
            throw new DomainException("Você ainda tem valores em aberto "
                    + (pendingEvents.size() == 1 ? "no evento " : "nos eventos ")
                    + String.join(", ", pendingEvents)
                    + ". Acerte esses pagamentos antes de sair do grupo.");
        }
        deactivateMember(groupId, userId);
    }

    public void requireMembership(UUID groupId, UUID userId) {
        if (userId == null) {
            return;
        }
        if (!members.existsByGroupIdAndUserIdAndActiveTrue(groupId, userId)) {
            throw new DomainException("O usuário não faz parte deste grupo.");
        }
    }

    /** Integrante ativo e fixo (admin ou integrante), não temporário. */
    public void requireFixedMember(UUID groupId, UUID userId) {
        boolean fixed = userId != null && members.findByGroupIdAndUserId(groupId, userId)
                .filter(GroupMemberJpaEntity::isActive)
                .filter(member -> !member.isTemporary())
                .isPresent();
        if (!fixed) {
            throw new DomainException("Apenas integrantes do grupo podem convidar.");
        }
    }

    public void requireAdmin(UUID groupId, UUID userId) {
        if (userId == null || !members.existsByGroupIdAndUserIdAndRoleAndActiveTrue(groupId, userId, GroupMemberRole.ADMIN)) {
            throw new DomainException("Apenas administradores do grupo podem fazer isso.");
        }
    }

    private void deactivateMember(UUID groupId, UUID userId) {
        GroupMemberJpaEntity member = members.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new DomainException("Integrante do grupo não encontrado."));
        if (!member.isActive()) {
            return;
        }
        if (member.getRole() == GroupMemberRole.ADMIN
                && members.countByGroupIdAndRoleAndActiveTrue(groupId, GroupMemberRole.ADMIN) <= 1) {
            throw new DomainException("O grupo precisa ter ao menos um administrador.");
        }
        member.setActive(false);
        member.setUpdatedAt(LocalDateTime.now());
    }

    private List<String> eventsWithPendingBalance(UUID groupId, UUID userId) {
        return events.findByGroupIdAndDeletedAtIsNull(groupId).stream()
                .filter(event -> event.getStatus() != EventStatus.CLOSED)
                .filter(event -> balanceForEvent(event, userId).compareTo(java.math.BigDecimal.ZERO) != 0)
                .map(EventJpaEntity::getName)
                .sorted()
                .toList();
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
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));
        if (!user.isActive() || user.getDeletedAt() != null) {
            throw new DomainException("Usuário não encontrado.");
        }
        return user;
    }

    private GroupResponse toResponse(GroupJpaEntity group) {
        return toResponse(group, false);
    }

    private GroupResponse toResponse(GroupJpaEntity group, boolean viewerRemoved) {
        return new GroupResponse(
                group.getId(),
                group.getName(),
                group.getDescription(),
                group.getCreatedBy().getId(),
                group.isActive(),
                group.getReceiver() == null ? null : group.getReceiver().getId(),
                group.getReceiver() == null ? null : group.getReceiver().getNickname(),
                group.getAutoSettlementDay(),
                viewerRemoved
        );
    }

    private GroupMemberResponse toMemberResponse(GroupMemberJpaEntity member) {
        return GroupMemberResponse.from(member);
    }
}
