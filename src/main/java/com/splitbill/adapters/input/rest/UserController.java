package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.CreateUserRequest;
import com.splitbill.application.dto.ChangePasswordRequest;
import com.splitbill.application.dto.ResetPasswordRequest;
import com.splitbill.application.dto.UpdateUserRequest;
import com.splitbill.application.dto.UserOptionResponse;
import com.splitbill.application.dto.UserResponse;
import com.splitbill.application.usecase.UserUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserUseCase users;
    private final String bootstrapAdminToken;

    public UserController(
            UserUseCase users,
            @Value("${split-bill.bootstrap.admin-token:}") String bootstrapAdminToken
    ) {
        this.users = users;
        this.bootstrapAdminToken = bootstrapAdminToken;
    }

    @GetMapping
    public List<UserResponse> list() {
        if (!isAdmin()) {
            throw new AccessDeniedException("Only admins can list users");
        }
        return users.list();
    }

    @GetMapping("/options")
    public List<UserOptionResponse> options() {
        return users.listOptions();
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id) {
        return users.get(id, currentUserId(), isAdmin());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(
            @Valid @RequestBody CreateUserRequest request,
            @RequestHeader(name = "X-Bootstrap-Token", required = false) String bootstrapToken
    ) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        boolean bootstrapAdmin = bootstrapAdminToken != null
                && !bootstrapAdminToken.isBlank()
                && bootstrapAdminToken.equals(bootstrapToken);
        return users.create(request, admin, bootstrapAdmin);
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return users.update(id, request, currentUserId(), isAdmin());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        users.delete(id, isAdmin());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/me/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        users.changePassword(currentUserId(), request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/password/reset")
    public ResponseEntity<Void> resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody ResetPasswordRequest request
    ) {
        users.resetPassword(id, request, isAdmin());
        return ResponseEntity.noContent().build();
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
