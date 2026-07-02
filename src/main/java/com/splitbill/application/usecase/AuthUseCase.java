package com.splitbill.application.usecase;

import com.splitbill.application.dto.AuthResponse;
import com.splitbill.application.dto.LoginRequest;
import com.splitbill.application.dto.UserResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import com.splitbill.infrastructure.security.AuthTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthUseCase {

    private final UserJpaRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService tokens;

    public AuthUseCase(UserJpaRepository users, PasswordEncoder passwordEncoder, AuthTokenService tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        UserJpaEntity user = users.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new DomainException("Invalid email or password"));
        if (user.getDeletedAt() != null || !user.isActive()) {
            throw new DomainException("Invalid email or password");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new DomainException("Invalid email or password");
        }
        return new AuthResponse(tokens.issue(user), toResponse(user));
    }

    @Transactional(readOnly = true)
    public UserResponse me(String token) {
        UserJpaEntity user = tokens.resolveUser(token);
        return toResponse(user);
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
}
