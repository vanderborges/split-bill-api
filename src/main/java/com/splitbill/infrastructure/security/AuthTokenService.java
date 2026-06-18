package com.splitbill.infrastructure.security;

import com.splitbill.domain.exception.DomainException;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class AuthTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long TOKEN_TTL_SECONDS = 60L * 60L * 24L * 30L;

    private final UserJpaRepository users;
    private final byte[] secret;

    public AuthTokenService(
            UserJpaRepository users,
            @Value("${split-bill.auth.secret:change-this-local-dev-secret}") String secret
    ) {
        this.users = users;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(UserJpaEntity user) {
        long expiresAt = Instant.now().getEpochSecond() + TOKEN_TTL_SECONDS;
        String payload = user.getId() + ":" + expiresAt;
        return base64(payload) + "." + sign(payload);
    }

    public UserJpaEntity resolveUser(String token) {
        String payload = payload(token);
        String[] parts = payload.split(":");
        if (parts.length != 2) {
            throw new DomainException("Invalid token");
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException exception) {
            throw new DomainException("Invalid token");
        }
        if (expiresAt < Instant.now().getEpochSecond()) {
            throw new DomainException("Expired token");
        }
        UUID userId = UUID.fromString(parts[0]);
        UserJpaEntity user = users.findById(userId)
                .orElseThrow(() -> new DomainException("Invalid token"));
        if (user.getDeletedAt() != null || !user.isActive()) {
            throw new DomainException("Invalid token");
        }
        return user;
    }

    private String payload(String token) {
        String[] tokenParts = token == null ? new String[0] : token.split("\\.");
        if (tokenParts.length != 2) {
            throw new DomainException("Invalid token");
        }
        String payload = new String(Base64.getUrlDecoder().decode(tokenParts[0]), StandardCharsets.UTF_8);
        String expectedSignature = sign(payload);
        if (!constantTimeEquals(expectedSignature, tokenParts[1])) {
            throw new DomainException("Invalid token");
        }
        return payload;
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign auth token", exception);
        }
    }

    private String base64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        if (expectedBytes.length != actualBytes.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < expectedBytes.length; i++) {
            result |= expectedBytes[i] ^ actualBytes[i];
        }
        return result == 0;
    }
}
