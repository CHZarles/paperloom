package io.github.chzarles.paperloom.config;

import io.github.chzarles.paperloom.service.CustomUserDetailsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthBearerAuthenticationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsOAuthTokenAndMapsPaperReadScope() throws Exception {
        JwtDecoder decoder = mock(JwtDecoder.class);
        ObjectProvider<JwtDecoder> decoderProvider = mock(ObjectProvider.class);
        CustomUserDetailsService users = mock(CustomUserDetailsService.class);
        when(decoderProvider.getIfAvailable()).thenReturn(decoder);
        when(users.loadUserByUsername("alice")).thenReturn(
                User.withUsername("alice").password("ignored").roles("USER").build());
        when(decoder.decode("oauth-token")).thenReturn(jwt(List.of("paper.read")));

        OAuthBearerAuthenticationFilter filter = new OAuthBearerAuthenticationFilter(decoderProvider, users);
        MockHttpServletRequest request = request("/api/v1/reading/papers/search");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);

        assertEquals("alice", SecurityContextHolder.getContext().getAuthentication().getName());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> "SCOPE_paper.read".equals(authority.getAuthority())));
        verify(chain).doFilter(request, response);
    }

    @Test
    void rejectsOAuthTokenWithoutUploadScope() throws Exception {
        JwtDecoder decoder = mock(JwtDecoder.class);
        ObjectProvider<JwtDecoder> decoderProvider = mock(ObjectProvider.class);
        CustomUserDetailsService users = mock(CustomUserDetailsService.class);
        when(decoderProvider.getIfAvailable()).thenReturn(decoder);
        when(users.loadUserByUsername("alice")).thenReturn(
                User.withUsername("alice").password("ignored").roles("USER").build());
        when(decoder.decode("oauth-token")).thenReturn(jwt(List.of("paper.read")));

        OAuthBearerAuthenticationFilter filter = new OAuthBearerAuthenticationFilter(decoderProvider, users);
        MockHttpServletRequest request = request("/api/v1/papers/upload/chunk");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        org.mockito.Mockito.verifyNoInteractions(chain);
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader("Authorization", "Bearer oauth-token");
        return request;
    }

    private Jwt jwt(List<String> scopes) {
        return Jwt.withTokenValue("oauth-token")
                .header("alg", "RS256")
                .issuer("http://127.0.0.1:8081")
                .subject("alice")
                .audience(List.of("paperloom-local-mcp"))
                .claim("scope", scopes)
                .claim("role", "USER")
                .claim("userId", "42")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
    }
}
