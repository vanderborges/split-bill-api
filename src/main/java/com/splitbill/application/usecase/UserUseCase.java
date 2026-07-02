package com.splitbill.application.usecase;

import com.splitbill.application.dto.CreateUserRequest;
import com.splitbill.application.dto.ChangePasswordRequest;
import com.splitbill.application.dto.ResetPasswordRequest;
import com.splitbill.application.dto.UpdateUserRequest;
import com.splitbill.application.dto.UserOptionResponse;
import com.splitbill.application.dto.UserResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
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
    private final PasswordEncoder passwordEncoder;

    public UserUseCase(UserJpaRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return users.findAll().stream()
                .filter(user -> user.getDeletedAt() == null)
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserOptionResponse> listOptions() {
        return users.findAll().stream()
                .filter(user -> user.getDeletedAt() == null)
                .filter(UserJpaEntity::isActive)
                .map(user -> new UserOptionResponse(user.getId(), user.getNickname(), user.isActive()))
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id, UUID requesterId, boolean requesterIsAdmin) {
        if (!requesterIsAdmin && !id.equals(requesterId)) {
            throw new DomainException("Users can only view their own profile");
        }
        UserJpaEntity user = users.findById(id)
                .orElseThrow(() -> new DomainException("User not found"));
        if (user.getDeletedAt() != null) {
            throw new DomainException("User not found");
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
            throw new DomainException("Email already registered");
        }
        if (users.countByDeletedAtIsNull() == 0 && !allowBootstrapAdmin) {
            throw new DomainException("Bootstrap admin token is required to create the first user");
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
            throw new DomainException("Users can only update their own profile");
        }
        UserJpaEntity user = users.findById(id)
                .orElseThrow(() -> new DomainException("User not found"));
        if (user.getDeletedAt() != null) {
            throw new DomainException("User not found");
        }
        users.findAll().stream()
                .filter(existing -> existing.getDeletedAt() == null)
                .filter(existing -> !existing.getId().equals(id))
                .filter(existing -> existing.getEmail().equalsIgnoreCase(request.email()))
                .findAny()
                .ifPresent(existing -> {
                    throw new DomainException("Email already registered");
                });
        UUID currentBillingUserId = user.getBillingUser() == null ? null : user.getBillingUser().getId();
        if (!requesterIsAdmin && (request.admin() != user.isAdmin()
                || request.active() != user.isActive()
                || !sameId(currentBillingUserId, request.billingUserId()))) {
            throw new DomainException("Users cannot change their access settings");
        }
        if (user.isAdmin() && !request.admin() && users.countByAdminTrueAndActiveTrueAndDeletedAtIsNull() <= 1) {
            throw new DomainException("Cannot remove admin role from the last active admin");
        }
        if (user.isAdmin() && !request.active() && users.countByAdminTrueAndActiveTrueAndDeletedAtIsNull() <= 1) {
            throw new DomainException("Cannot deactivate the last active admin");
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
            throw new DomainException("Only admins can delete users");
        }
        UserJpaEntity user = users.findById(id)
                .orElseThrow(() -> new DomainException("User not found"));
        if (user.getDeletedAt() != null) {
            return;
        }
        if (user.isAdmin() && users.countByAdminTrueAndActiveTrueAndDeletedAtIsNull() <= 1) {
            throw new DomainException("Cannot delete the last active admin");
        }

        LocalDateTime now = LocalDateTime.now();
        user.setActive(false);
        user.setDeletedAt(now);
        user.setUpdatedAt(now);
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        UserJpaEntity user = users.findById(userId)
                .orElseThrow(() -> new DomainException("User not found"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new DomainException("Current password is invalid");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(LocalDateTime.now());
    }

    @Transactional
    public void resetPassword(UUID userId, ResetPasswordRequest request, boolean requesterIsAdmin) {
        if (!requesterIsAdmin) {
            throw new DomainException("Only admins can reset passwords");
        }
        UserJpaEntity user = users.findById(userId)
                .orElseThrow(() -> new DomainException("User not found"));
        if (user.getDeletedAt() != null) {
            throw new DomainException("User not found");
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
            throw new DomainException("Billing user cannot be the same user");
        }
        UserJpaEntity billingUser = users.findById(billingUserId)
                .orElseThrow(() -> new DomainException("Billing user not found"));
        if (billingUser.getDeletedAt() != null || !billingUser.isActive()) {
            throw new DomainException("Billing user must be active");
        }
        if (billingUser.getBillingUser() != null) {
            throw new DomainException("Billing user cannot be linked to another user");
        }
        return billingUser;
    }

    private boolean sameId(UUID first, UUID second) {
        return first == null ? second == null : first.equals(second);
    }
}
