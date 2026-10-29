package com.splitbill.application.usecase;

import com.splitbill.application.dto.GroupInvitePreviewResponse;
import com.splitbill.application.dto.GroupInviteResponse;
import com.splitbill.application.dto.GroupMemberResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.GroupMemberRole;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupInviteJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.infrastructure.persistence.entity.NotificationJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.GroupInviteJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import com.splitbill.infrastructure.persistence.repository.NotificationJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import com.splitbill.application.event.NotificationCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class GroupInviteUseCase {

    private final GroupInviteJpaRepository invites;
    private final GroupJpaRepository groups;
    private final GroupMemberJpaRepository members;
    private final UserJpaRepository users;
    private final GroupUseCase groupRules;
    private final PlaceholderUseCase placeholders;
    private final NotificationJpaRepository notifications;
    private final ApplicationEventPublisher events;

    public GroupInviteUseCase(
            GroupInviteJpaRepository invites,
            GroupJpaRepository groups,
            GroupMemberJpaRepository members,
            UserJpaRepository users,
            GroupUseCase groupRules,
            PlaceholderUseCase placeholders,
            NotificationJpaRepository notifications,
            ApplicationEventPublisher events
    ) {
        this.invites = invites;
        this.groups = groups;
        this.members = members;
        this.users = users;
        this.groupRules = groupRules;
        this.placeholders = placeholders;
        this.notifications = notifications;
        this.events = events;
    }

    @Transactional
    public GroupInviteResponse getOrCreate(UUID groupId, UUID requesterId) {
        // Link do grupo: admin e integrantes fixos (temporario nao convida).
        groupRules.requireFixedMember(groupId, requesterId);
        GroupJpaEntity group = activeGroup(groupId);
        GroupInviteJpaEntity invite = invites.findFirstByGroupIdAndEventIsNullAndActiveTrueOrderByCreatedAtDesc(groupId)
                .orElseGet(() -> createInvite(group, requesterId, null));
        return toResponse(invite);
    }

    /**
     * Convite temporario: link amarrado a um evento aberto do grupo. Quem
     * entra por ele vira integrante TEMPORARIO, so desse evento.
     */
    @Transactional
    public GroupInviteResponse getOrCreateTemporary(UUID groupId, UUID eventId, UUID requesterId) {
        groupRules.requireAdmin(groupId, requesterId);
        GroupJpaEntity group = activeGroup(groupId);
        EventJpaEntity event = groupRules.requireOpenEventOfGroup(groupId, eventId);
        GroupInviteJpaEntity invite = invites.findFirstByEventIdAndActiveTrueOrderByCreatedAtDesc(eventId)
                .orElseGet(() -> createInvite(group, requesterId, event));
        return toResponse(invite);
    }

    @Transactional
    public GroupInviteResponse regenerate(UUID groupId, UUID requesterId) {
        groupRules.requireAdmin(groupId, requesterId);
        GroupJpaEntity group = activeGroup(groupId);
        invites.findFirstByGroupIdAndEventIsNullAndActiveTrueOrderByCreatedAtDesc(groupId)
                .ifPresent(current -> current.setActive(false));
        return toResponse(createInvite(group, requesterId, null));
    }

    @Transactional(readOnly = true)
    public GroupInvitePreviewResponse preview(UUID inviteId) {
        GroupInviteJpaEntity invite = activeInvite(inviteId);
        return new GroupInvitePreviewResponse(
                invite.getId(),
                invite.getGroup().getId(),
                invite.getGroup().getName(),
                invite.isActive(),
                invite.getEvent() == null ? null : invite.getEvent().getId(),
                invite.getEvent() == null ? null : invite.getEvent().getName(),
                placeholders.claimableFor(invite).stream()
                        .map(member -> new GroupInvitePreviewResponse.PlaceholderOption(
                                member.getUser().getId(), member.getUser().getNickname()))
                        .toList()
        );
    }

    @Transactional
    public GroupMemberResponse join(UUID inviteId, UUID requesterId) {
        return join(inviteId, requesterId, null);
    }

    /**
     * Entrar pelo convite. Com {@code placeholderUserId}: a pessoa disse ser
     * aquele nome pre-cadastrado pelo admin e herda as despesas dele (ver
     * {@link PlaceholderUseCase#claim}). Sem: entra como pessoa nova.
     */
    @Transactional
    public GroupMemberResponse join(UUID inviteId, UUID requesterId, UUID placeholderUserId) {
        GroupInviteJpaEntity invite = activeInvite(inviteId);
        GroupJpaEntity group = invite.getGroup();
        UserJpaEntity user = users.findById(requesterId)
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));
        if (!user.isActive() || user.getDeletedAt() != null) {
            throw new DomainException("Usuário não encontrado.");
        }

        EventJpaEntity temporaryEvent = invite.getEvent();
        if (temporaryEvent != null) {
            // Valida que o evento do convite ainda aceita gente (nao fechado/apagado).
            groupRules.requireOpenEventOfGroup(group.getId(), temporaryEvent.getId());
        }
        GroupMemberJpaEntity member = members.findByGroupIdAndUserId(group.getId(), requesterId).orElse(null);
        boolean alreadyMember = member != null && member.isActive();
        if (placeholderUserId != null) {
            String placeholderName = users.findById(placeholderUserId)
                    .map(UserJpaEntity::getNickname)
                    .orElse(null);
            String groupName = group.getName();
            String userName = user.getNickname();
            GroupMemberResponse claimed = toMemberResponse(placeholders.claim(invite, placeholderUserId, user));
            if (!alreadyMember) {
                // claim() limpa o EntityManager: so ids/nomes lidos antes.
                notifyAdminsOfNewMember(group.getId(), groupName, requesterId, userName,
                        temporaryEvent != null ? temporaryEvent.getName() : claimed.temporary() ? "" : null,
                        placeholderName);
            }
            return claimed;
        }

        LocalDateTime now = LocalDateTime.now();
        if (member != null && member.isActive()) {
            if (member.isTemporary()) {
                // Temporario entrando pelo convite do grupo vira integrante
                // fixo; por outro convite temporario, so troca de evento.
                member.setTemporary(temporaryEvent != null);
                member.setTemporaryEvent(temporaryEvent);
                member.setUpdatedAt(now);
            }
            // Integrante fixo nunca e rebaixado a temporario.
            return toMemberResponse(member);
        }

        // Quem saiu/foi removido e volta pelo convite entra como temporario
        // normal (o admin pode tornar fixo depois).
        boolean returning = member != null;
        if (member == null) {
            member = new GroupMemberJpaEntity();
            member.setId(UUID.randomUUID());
            member.setGroup(group);
            member.setUser(user);
            member.setCreatedAt(now);
        }
        member.setRole(GroupMemberRole.MEMBER);
        member.setActive(true);
        member.setTemporary(temporaryEvent != null || returning);
        member.setTemporaryEvent(temporaryEvent);
        member.setUpdatedAt(now);
        GroupMemberResponse joined = toMemberResponse(members.save(member));
        notifyAdminsOfNewMember(group.getId(), group.getName(), requesterId, user.getNickname(),
                temporaryEvent == null ? null : temporaryEvent.getName(),
                null);
        return joined;
    }

    /**
     * Avisa os admins do grupo (in-app, como a cobranca) que alguem entrou
     * pelo convite. Nao avisa quem ja era integrante nem o proprio admin que
     * entrou; pessoa sem cadastro nunca recebe notificacao.
     */
    private void notifyAdminsOfNewMember(
            UUID groupId,
            String groupName,
            UUID newUserId,
            String newUserName,
            String temporaryEventName,
            String placeholderName
    ) {
        StringBuilder message = new StringBuilder(groupName)
                .append(": ")
                .append(newUserName)
                .append(" entrou no grupo");
        if (temporaryEventName != null) {
            message.append(" como temporário");
            if (!temporaryEventName.isEmpty()) {
                message.append(" no evento ").append(temporaryEventName);
            }
        }
        if (placeholderName != null && !placeholderName.equals(newUserName)) {
            message.append(" no lugar de \"").append(placeholderName).append("\"");
        }
        message.append(".");
        String text = message.length() > 500 ? message.substring(0, 500) : message.toString();

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserJpaEntity author = users.getReferenceById(newUserId);
        members.findByGroupIdAndActiveTrue(groupId).stream()
                .filter(admin -> admin.getRole() == GroupMemberRole.ADMIN)
                .map(GroupMemberJpaEntity::getUser)
                .filter(admin -> !admin.getId().equals(newUserId) && !admin.isPlaceholder())
                .forEach(admin -> {
                    NotificationJpaEntity notification = new NotificationJpaEntity();
                    notification.setId(UUID.randomUUID());
                    notification.setRecipient(admin);
                    // Sem evento: o aviso e do grupo (o evento do temporario
                    // ja vai no texto).
                    notification.setEvent(null);
                    notification.setMessage(text);
                    notification.setType(NotificationJpaEntity.TYPE_MEMBER_JOINED);
                    notification.setCreatedBy(author);
                    notification.setCreatedAt(now);
                    notifications.save(notification);
                    events.publishEvent(new NotificationCreatedEvent(
                            notification.getId(), admin.getId(), text, notification.getType()));
                });
    }

    private GroupInviteJpaEntity createInvite(GroupJpaEntity group, UUID requesterId, EventJpaEntity event) {
        UserJpaEntity creator = users.findById(requesterId)
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));
        GroupInviteJpaEntity invite = new GroupInviteJpaEntity();
        invite.setId(UUID.randomUUID());
        invite.setGroup(group);
        invite.setEvent(event);
        invite.setCreatedBy(creator);
        invite.setActive(true);
        invite.setCreatedAt(LocalDateTime.now());
        return invites.save(invite);
    }

    private GroupJpaEntity activeGroup(UUID groupId) {
        GroupJpaEntity group = groups.findById(groupId)
                .orElseThrow(() -> new DomainException("Grupo não encontrado."));
        if (!group.isActive()) {
            throw new DomainException("Grupo não encontrado.");
        }
        return group;
    }

    private GroupInviteJpaEntity activeInvite(UUID inviteId) {
        GroupInviteJpaEntity invite = invites.findById(inviteId)
                .orElseThrow(() -> new DomainException("Convite não encontrado ou inativo."));
        if (!invite.isActive() || !invite.getGroup().isActive()) {
            throw new DomainException("Convite não encontrado ou inativo.");
        }
        return invite;
    }

    private GroupInviteResponse toResponse(GroupInviteJpaEntity invite) {
        return new GroupInviteResponse(
                invite.getId(),
                invite.getGroup().getId(),
                invite.getCreatedBy().getId(),
                invite.isActive(),
                invite.getCreatedAt(),
                invite.getEvent() == null ? null : invite.getEvent().getId()
        );
    }

    private GroupMemberResponse toMemberResponse(GroupMemberJpaEntity member) {
        return GroupMemberResponse.from(member);
    }
}
