package com.splitbill.infrastructure.security;

import com.splitbill.domain.exception.DomainException;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BearerTokenAuthenticationFilterTest {

    private final AuthTokenService tokens = mock(AuthTokenService.class);
    private final BearerTokenAuthenticationFilter filter = new BearerTokenAuthenticationFilter(tokens);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ignoresInvalidBearerTokenOnLoginEndpoint() throws ServletException, IOException {
        when(tokens.resolveUser("expired")).thenThrow(new DomainException("Expired token"));
        MockHttpServletRequest request = request("POST", "/auth/login");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer expired");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void rejectsInvalidBearerTokenOnProtectedEndpoint() throws ServletException, IOException {
        when(tokens.resolveUser("expired")).thenThrow(new DomainException("Expired token"));
        MockHttpServletRequest request = request("GET", "/users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer expired");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void authenticatesValidBearerTokenOnPublicUserCreationEndpoint() throws ServletException, IOException {
        UserJpaEntity user = new UserJpaEntity();
        user.setId(UUID.randomUUID());
        user.setAdmin(true);
        when(tokens.resolveUser("valid")).thenReturn(user);
        MockHttpServletRequest request = request("POST", "/users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
