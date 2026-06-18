package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.AuthResponse;
import com.splitbill.application.dto.LoginRequest;
import com.splitbill.application.dto.UserResponse;
import com.splitbill.application.usecase.AuthUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthUseCase auth;

    public AuthController(AuthUseCase auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request);
    }

    @GetMapping("/me")
    public UserResponse me(@RequestHeader("Authorization") String authorization) {
        return auth.me(authorization.replaceFirst("(?i)^Bearer\\s+", ""));
    }
}
