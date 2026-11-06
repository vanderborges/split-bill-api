package com.splitbill.application.usecase;

import com.splitbill.application.dto.CreateUserRequest;
import com.splitbill.application.dto.ChangePasswordRequest;
import com.splitbill.application.dto.ResetPasswordRequest;
import com.splitbill.application.dto.UpdateUserRequest;
import com.splitbill.application.dto.UserResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.DeviceTokenJpaRepository;
import com.splitbill.infrastructure.persistence.repository.ExpenseJpaRepository;
import com.splitbill.infrastructure.persistence.repository.NotificationJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class UserUseCase {

    private final UserJpaRepository users;
    private final ExpenseJpaRepository expenses;
    private final GroupMemberJpaRepository groupMembers;
    private final PasswordEncoder passwordEncoder;
    private final DeviceTokenJpaRepository deviceTokens;
    private final NotificationJpaRepository notifications;

    public UserUseCase(
            UserJpaRepository users,
            ExpenseJpaRepository expenses,
            GroupMemberJpaRepository groupMembers,
            PasswordEncoder passwordEncoder,
            DeviceTokenJpaRepository deviceTokens,
            NotificationJpaRepository notifications
    ) {
        this.users = users;
        this.expenses = expenses;
        this.groupMembers = groupMembers;
        this.passwordEncoder = passwordEncoder;
        this.deviceTokens = deviceTokens;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return users.findAll().stream()
                .filter(user -> user.getDeletedAt() == null)
                // Pessoas sem cadastro sao do grupo, nao contas do app.
                .filter(user -> !user.isPlaceholder())
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id, UUID requesterId, boolean requesterIsAdmin) {
        if (!requesterIsAdmin && !id.equals(requesterId)) {
            throw new DomainException("Você só pode ver o próprio perfil.");
        }
        UserJpaEntity user = users.findById(id)
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));
        if (user.getDeletedAt() != null) {
            throw new DomainException("Usuário não encontrado.");
        }
        return toResponse(user);
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        return create(request, false);
    }

    @Transactional
    public UserResponse create(CreateUserRequest request, boolean allowAdminFlag) {
        return create(request, allowAdminFlag, false);
    }

    @Transactional
    public UserResponse create(CreateUserRequest request, boolean allowAdminFlag, boolean allowBootstrapAdmin) {
        if (users.existsByEmailIgnoreCase(request.email())) {
            throw new DomainException("E-mail já cadastrado.");
        }
        if (users.countByDeletedAtIsNull() == 0 && !allowBootstrapAdmin) {
            throw new DomainException("É necessário o token de administrador para criar o primeiro usuário.");
        }

        LocalDateTime now = LocalDateTime.now();
        UserJpaEntity user = new UserJpaEntity();
        user.setId(UUID.randomUUID());
        user.setFullName(request.fullName());
        user.setNickname(request.nickname());
        user.setEmail(request.email().toLowerCase());
        user.setPhone(request.phone());
        user.setPixKey(request.pixKey());
        user.setBillingUser(resolveBillingUser(user.getId(), allowAdminFlag ? request.billingUserId() : null));
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setAdmin(resolveAdminFlag(request.admin(), allowAdminFlag, allowBootstrapAdmin));
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        return toResponse(users.save(user));
    }

    private boolean resolveAdminFlag(boolean requestedAdmin, boolean allowAdminFlag, boolean allowBootstrapAdmin) {
        if (users.countByDeletedAtIsNull() == 0) {
            return allowBootstrapAdmin;
        }
        return allowAdminFlag && requestedAdmin;
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request, UUID requesterId, boolean requesterIsAdmin) {
        if (!requesterIsAdmin && !id.equals(requesterId)) {
            throw new DomainException("Você só pode alterar o próprio perfil.");
        }
        UserJpaEntity user = users.findById(id)
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));
        if (user.getDeletedAt() != null) {
            throw new DomainException("Usuário não encontrado.");
        }
        users.findAll().stream()
                .filter(existing -> existing.getDeletedAt() == null)
                .filter(existing -> !existing.getId().equals(id))
                .filter(existing -> existing.getEmail().equalsIgnoreCase(request.email()))
                .findAny()
                .ifPresent(existing -> {
                    throw new DomainException("E-mail já cadastrado.");
                });
        UUID currentBillingUserId = user.getBillingUser() == null ? null : user.getBillingUser().getId();
        if (!requesterIsAdmin && (request.admin() != user.isAdmin()
                || request.active() != user.isActive()
                || !sameId(currentBillingUserId, request.billingUserId()))) {
            throw new DomainException("Você não pode alterar as próprias permissões de acesso.");
        }
        if (user.isAdmin() && !request.admin() && users.countByAdminTrueAndActiveTrueAndDeletedAtIsNull() <= 1) {
            throw new DomainException("Não é possível remover o perfil de administrador do último administrador ativo.");
        }
        if (user.isAdmin() && !request.active() && users.countByAdminTrueAndActiveTrueAndDeletedAtIsNull() <= 1) {
            throw new DomainException("Não é possível desativar o último administrador ativo.");
        }

        user.setFullName(request.fullName());
        user.setNickname(request.nickname());
        user.setEmail(request.email().toLowerCase());
        user.setPhone(request.phone());
        user.setPixKey(request.pixKey());
        user.setBillingUser(resolveBillingUser(user.getId(), request.billingUserId()));
        user.setAdmin(request.admin());
        user.setActive(request.active());
        user.setUpdatedAt(LocalDateTime.now());
        return toResponse(user);
    }

    @Transactional
    public void delete(UUID id, boolean requesterIsAdmin) {
        if (!requesterIsAdmin) {
            throw new DomainException("Apenas administradores podem excluir usuários.");
        }
        UserJpaEntity user = users.findById(id)
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));
        if (user.getDeletedAt() != null) {
            return;
        }
        requireCanLeave(user, "um usuário que participa");
        deactivate(user, LocalDateTime.now());
    }

    /**
     * "Excluir conta" do Perfil (exigencia da App Store e do Google Play):
     * qualquer pessoa exclui a propria conta. Alem de desativar, apaga os
     * dados pessoais (e-mail, telefone, PIX, senha, aparelhos de push e
     * avisos). Fica so o apelido, porque as despesas compartilhadas
     * continuam no historico dos outros integrantes.
     */
    @Transactional
    public void deleteOwnAccount(UUID userId) {
        UserJpaEntity user = users.findById(userId)
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));
        if (user.getDeletedAt() != null) {
            return;
        }
        requireCanLeave(user, "sua conta enquanto você participa");
        LocalDateTime now = LocalDateTime.now();
        deactivate(user, now);
        user.setFullName(user.getNickname());
        user.setEmail("excluido-" + user.getId() + "@dividiai.invalid");
        user.setPhone("");
        user.setPixKey("");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setAdmin(false);
        deviceTokens.deleteAll(deviceTokens.findByUserId(userId));
        notifications.deleteByRecipientId(userId);
    }

    private void requireCanLeave(UserJpaEntity user, String who) {
        if (user.isAdmin() && users.countByAdminTrueAndActiveTrueAndDeletedAtIsNull() <= 1) {
            throw new DomainException("Não é possível excluir o último administrador ativo.");
        }
        if (expenses.existsByUserInvolvementAndEventStatusNot(user.getId(), EventStatus.CLOSED)) {
            throw new DomainException("Não é possível excluir " + who + " de um evento em aberto."
                    + " Conclua ou acerte os eventos antes.");
        }
    }

    private void deactivate(UserJpaEntity user, LocalDateTime now) {
        user.setActive(false);
        user.setDeletedAt(now);
        user.setUpdatedAt(now);
        for (GroupMemberJpaEntity membership : groupMembers.findByUserIdAndActiveTrue(user.getId())) {
            membership.setActive(false);
            membership.setUpdatedAt(now);
        }
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        UserJpaEntity user = users.findById(userId)
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new DomainException("A senha atual está incorreta.");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(LocalDateTime.now());
    }

    @Transactional
    public void resetPassword(UUID userId, ResetPasswordRequest request, boolean requesterIsAdmin) {
        if (!requesterIsAdmin) {
            throw new DomainException("Apenas administradores podem reiniciar senhas.");
        }
        UserJpaEntity user = users.findById(userId)
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));
        if (user.getDeletedAt() != null || user.isPlaceholder()) {
            throw new DomainException("Usuário não encontrado.");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(LocalDateTime.now());
    }

    private UserResponse toResponse(UserJpaEntity user) {
        return new UserResponse(
                user.getId(),
                user.getFullName(),
                user.getNickname(),
                user.getEmail(),
                user.getPhone(),
                user.getPixKey(),
                user.getBillingUser() == null ? null : user.getBillingUser().getId(),
                user.isAdmin(),
                user.isActive()
        );
    }

    private UserJpaEntity resolveBillingUser(UUID userId, UUID billingUserId) {
        if (billingUserId == null) {
            return null;
        }
        if (billingUserId.equals(userId)) {
            throw new DomainException("O usuário de cobrança não pode ser o próprio usuário.");
        }
        UserJpaEntity billingUser = users.findById(billingUserId)
                .orElseThrow(() -> new DomainException("Usuário de cobrança não encontrado."));
        if (billingUser.getDeletedAt() != null || !billingUser.isActive() || billingUser.isPlaceholder()) {
            throw new DomainException("O usuário de cobrança precisa estar ativo.");
        }
        if (billingUser.getBillingUser() != null) {
            throw new DomainException("O usuário de cobrança não pode estar vinculado a outro usuário.");
        }
        return billingUser;
    }

    private boolean sameId(UUID first, UUID second) {
        return first == null ? second == null : first.equals(second);
    }
}
