package com.splitbill.application.usecase;

import com.splitbill.application.dto.CreatePlaceholderRequest;
import com.splitbill.application.dto.GroupMemberResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.GroupMemberRole;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupInviteJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.GroupJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Pessoa sem cadastro (estilo Tricount). O admin do grupo cadastra so o
 * nome; vira um usuario "fantasma" ({@code placeholder = true}) que entra no
 * grupo como integrante fixo ou temporario de um evento e pode pagar/dividir
 * despesas normalmente - relatorio, extrato e acertos nao sabem a diferenca.
 * Quem entra pelo convite e escolhe esse nome ({@link #claim}) herda tudo
 * dele, e o fantasma e apagado.
 */
@Service
public class PlaceholderUseCase {

    private final UserJpaRepository users;
    private final GroupJpaRepository groups;
    private final GroupMemberJpaRepository members;
    private final GroupUseCase groupRules;
    private final EntityManager entityManager;

    public PlaceholderUseCase(
            UserJpaRepository users,
            GroupJpaRepository groups,
            GroupMemberJpaRepository members,
            GroupUseCase groupRules,
            EntityManager entityManager
    ) {
        this.users = users;
        this.groups = groups;
        this.members = members;
        this.groupRules = groupRules;
        this.entityManager = entityManager;
    }

    @Transactional
    public GroupMemberResponse create(UUID groupId, CreatePlaceholderRequest request, UUID requesterId) {
        groupRules.requireAdmin(groupId, requesterId);
        GroupJpaEntity group = groups.findById(groupId)
                .filter(GroupJpaEntity::isActive)
                .orElseThrow(() -> new DomainException("Grupo não encontrado."));
        EventJpaEntity event = request.eventId() == null
                ? null
                : groupRules.requireOpenEventOfGroup(groupId, request.eventId());
        String nickname = request.nickname().trim();
        if (nickname.isEmpty()) {
            throw new DomainException("Informe o nome da pessoa.");
        }

        LocalDateTime now = LocalDateTime.now();
        UUID userId = UUID.randomUUID();
        UserJpaEntity user = new UserJpaEntity();
        user.setId(userId);
        user.setFullName(nickname);
        user.setNickname(nickname);
        // Valores internos: dominio .invalid nunca recebe email e "!" nao e
        // um hash BCrypt valido, entao nenhuma senha confere. O login ainda
        // recusa placeholder explicitamente (AuthUseCase).
        user.setEmail("sem-cadastro+" + userId + "@dividiai.invalid");
        user.setPasswordHash("!");
        user.setPhone("");
        user.setPixKey("");
        user.setAdmin(false);
        user.setActive(true);
        user.setPlaceholder(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        users.save(user);

        GroupMemberJpaEntity member = new GroupMemberJpaEntity();
        member.setId(UUID.randomUUID());
        member.setGroup(group);
        member.setUser(user);
        member.setRole(GroupMemberRole.MEMBER);
        member.setActive(true);
        member.setTemporary(event != null || Boolean.TRUE.equals(request.temporary()));
        member.setTemporaryEvent(event);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        return GroupMemberResponse.from(members.save(member));
    }

    /** Admin: nomes sem cadastro do grupo (fixos e temporarios), ainda sem dono. */
    @Transactional(readOnly = true)
    public List<GroupMemberResponse> list(UUID groupId, UUID requesterId) {
        groupRules.requireAdmin(groupId, requesterId);
        return members.findByGroupIdAndActiveTrue(groupId).stream()
                .filter(member -> member.getUser().isPlaceholder())
                .sorted(Comparator.comparing(member -> member.getUser().getNickname().toLowerCase()))
                .map(GroupMemberResponse::from)
                .toList();
    }

    @Transactional
    public GroupMemberResponse rename(UUID groupId, UUID userId, String nickname, UUID requesterId) {
        groupRules.requireAdmin(groupId, requesterId);
        GroupMemberJpaEntity member = placeholderMember(groupId, userId);
        String trimmed = nickname.trim();
        if (trimmed.isEmpty()) {
            throw new DomainException("Informe o nome da pessoa.");
        }
        UserJpaEntity user = member.getUser();
        user.setNickname(trimmed);
        user.setFullName(trimmed);
        user.setUpdatedAt(LocalDateTime.now());
        return GroupMemberResponse.from(member);
    }

    /** Admin: remove um nome sem cadastro - so se ele ainda nao tem nenhuma despesa. */
    @Transactional
    public void delete(UUID groupId, UUID userId, UUID requesterId) {
        groupRules.requireAdmin(groupId, requesterId);
        GroupMemberJpaEntity member = placeholderMember(groupId, userId);
        if (hasExpenses(userId)) {
            throw new DomainException("Essa pessoa já tem despesas lançadas e não pode ser removida.");
        }
        LocalDateTime now = LocalDateTime.now();
        member.setActive(false);
        member.setUpdatedAt(now);
        UserJpaEntity user = member.getUser();
        user.setActive(false);
        user.setDeletedAt(now);
        user.setUpdatedAt(now);
    }

    /**
     * Nomes sem dono que quem entra por este convite pode escolher: no
     * convite do grupo, os fixos; no convite temporario, os daquele evento.
     */
    public List<GroupMemberJpaEntity> claimableFor(GroupInviteJpaEntity invite) {
        EventJpaEntity inviteEvent = invite.getEvent();
        return members.findByGroupIdAndActiveTrue(invite.getGroup().getId()).stream()
                .filter(member -> member.getUser().isPlaceholder())
                .filter(member -> member.getUser().getDeletedAt() == null)
                // Convite do grupo: fixos e temporarios do grupo ainda na
                // lista (quem escolhe um temporario continua temporario).
                .filter(member -> inviteEvent == null
                        ? !member.isTemporary()
                        || (member.isGroupTemporary() && groupRules.isTemporaryListed(member))
                        : member.isTemporary()
                        && member.getTemporaryEvent() != null
                        && member.getTemporaryEvent().getId().equals(inviteEvent.getId()))
                .sorted(Comparator.comparing(member -> member.getUser().getNickname().toLowerCase()))
                .toList();
    }

    /**
     * "Sou eu": passa tudo do nome sem cadastro pra conta real de quem
     * entrou e apaga o fantasma. Tudo na transacao do join; a linha do
     * fantasma fica travada (select ... for update), entao duas pessoas
     * escolhendo o mesmo nome ao mesmo tempo: so a primeira leva, a
     * segunda recebe "ja foi escolhido".
     *
     * @return a participacao da conta real no grupo, ja atualizada
     */
    public GroupMemberJpaEntity claim(GroupInviteJpaEntity invite, UUID placeholderUserId, UserJpaEntity realUser) {
        UUID groupId = invite.getGroup().getId();
        UserJpaEntity placeholder = users.findById(placeholderUserId)
                .orElseThrow(() -> new DomainException("Pessoa não encontrada."));
        // Relê do banco ja travando a linha: quem chegar depois espera e ve
        // o fantasma ja apagado.
        entityManager.refresh(placeholder, LockModeType.PESSIMISTIC_WRITE);
        if (!placeholder.isPlaceholder() || placeholder.getDeletedAt() != null || !placeholder.isActive()) {
            throw new DomainException("Esse nome já foi escolhido por outra pessoa.");
        }
        GroupMemberJpaEntity placeholderMember = claimableFor(invite).stream()
                .filter(member -> member.getUser().getId().equals(placeholderUserId))
                .findFirst()
                .orElseThrow(() -> new DomainException("Esse nome não faz parte deste convite."));

        LocalDateTime now = LocalDateTime.now();
        GroupMemberJpaEntity realMember = members.findByGroupIdAndUserId(groupId, realUser.getId()).orElse(null);
        GroupMemberJpaEntity result;
        if (realMember == null) {
            // Mais simples: a participacao do fantasma passa a ser da conta real.
            placeholderMember.setUser(realUser);
            placeholderMember.setUpdatedAt(now);
            result = placeholderMember;
        } else {
            if (!realMember.isActive()) {
                realMember.setActive(true);
                realMember.setRole(GroupMemberRole.MEMBER);
                realMember.setTemporary(placeholderMember.isTemporary());
                realMember.setTemporaryEvent(placeholderMember.getTemporaryEvent());
            } else if (realMember.isTemporary()) {
                // Fixo prevalece; temporario nos dois troca pro evento do convite.
                realMember.setTemporary(placeholderMember.isTemporary());
                realMember.setTemporaryEvent(placeholderMember.getTemporaryEvent());
            }
            realMember.setUpdatedAt(now);
            placeholderMember.setActive(false);
            placeholderMember.setUpdatedAt(now);
            result = realMember;
        }
        entityManager.flush();

        mergeRecords(placeholderUserId, realUser.getId());

        placeholder.setActive(false);
        placeholder.setDeletedAt(now);
        placeholder.setUpdatedAt(now);
        entityManager.flush();
        // As despesas/acertos mudaram por SQL direto: descarta o que estiver
        // em memoria e recarrega a participacao.
        UUID resultId = result.getId();
        entityManager.clear();
        return members.findById(resultId)
                .orElseThrow(() -> new DomainException("Integrante do grupo não encontrado."));
    }

    /**
     * Repassa os registros do fantasma pra conta real. Onde os dois ja
     * aparecem na mesma despesa/acerto, soma (pagador e participante) ou
     * fica so o da conta real (acerto - o valor e recalculado ao abrir o
     * relatorio).
     */
    private void mergeRecords(UUID placeholderId, UUID realId) {
        // Pagadores: soma o valor pago quando os dois pagaram a mesma despesa.
        execute("""
                update expense_payers r
                   set paid_amount = r.paid_amount + p.paid_amount
                  from expense_payers p
                 where p.expense_id = r.expense_id
                   and r.user_id = :real
                   and p.user_id = :placeholder
                """, placeholderId, realId);
        execute("""
                delete from expense_payers
                 where user_id = :placeholder
                   and expense_id in (select expense_id from expense_payers where user_id = :real)
                """, placeholderId, realId);
        execute("update expense_payers set user_id = :real where user_id = :placeholder", placeholderId, realId);

        // Participantes: soma valor e cotas (divisao por valor fica com 1 cota).
        execute("""
                update expense_participants r
                   set share_amount = r.share_amount + p.share_amount,
                       share_count = case when e.custom_split then 1 else r.share_count + p.share_count end,
                       share_description = coalesce(r.share_description, p.share_description)
                  from expense_participants p, expenses e
                 where p.expense_id = r.expense_id
                   and e.id = r.expense_id
                   and r.user_id = :real
                   and p.user_id = :placeholder
                """, placeholderId, realId);
        execute("""
                delete from expense_participants
                 where user_id = :placeholder
                   and expense_id in (select expense_id from expense_participants where user_id = :real)
                """, placeholderId, realId);
        execute("update expense_participants set user_id = :real where user_id = :placeholder", placeholderId, realId);

        execute("update expenses set payer_id = :real where payer_id = :placeholder", placeholderId, realId);
        execute("update expenses set created_by_user_id = :real where created_by_user_id = :placeholder",
                placeholderId, realId);
        execute("update installment_groups set payer_id = :real where payer_id = :placeholder", placeholderId, realId);

        // Acertos: se a conta real ja tem linha no evento, a do fantasma sai.
        execute("""
                delete from event_settlements
                 where user_id = :placeholder
                   and event_id in (select event_id from event_settlements where user_id = :real)
                """, placeholderId, realId);
        execute("update event_settlements set user_id = :real where user_id = :placeholder", placeholderId, realId);

        execute("update notifications set recipient_user_id = :real where recipient_user_id = :placeholder",
                placeholderId, realId);
        execute("update groups set receiver_user_id = :real where receiver_user_id = :placeholder",
                placeholderId, realId);
    }

    private void execute(String sql, UUID placeholderId, UUID realId) {
        entityManager.createNativeQuery(sql)
                .setParameter("placeholder", placeholderId)
                .setParameter("real", realId)
                .executeUpdate();
    }

    private boolean hasExpenses(UUID userId) {
        Number count = (Number) entityManager.createNativeQuery("""
                select count(*)
                  from expenses e
                 where e.deleted_at is null
                   and (e.payer_id = :user
                        or exists (select 1 from expense_payers p where p.expense_id = e.id and p.user_id = :user)
                        or exists (select 1 from expense_participants p where p.expense_id = e.id and p.user_id = :user))
                """)
                .setParameter("user", userId)
                .getSingleResult();
        return count.longValue() > 0;
    }

    private GroupMemberJpaEntity placeholderMember(UUID groupId, UUID userId) {
        return members.findByGroupIdAndUserId(groupId, userId)
                .filter(GroupMemberJpaEntity::isActive)
                .filter(member -> member.getUser().isPlaceholder())
                .orElseThrow(() -> new DomainException("Pessoa sem cadastro não encontrada."));
    }
}
