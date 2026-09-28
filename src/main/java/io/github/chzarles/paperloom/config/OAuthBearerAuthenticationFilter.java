package io.github.chzarles.paperloom.config;

import io.github.chzarles.paperloom.service.CustomUserDetailsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Accepts the RSA signed OAuth access tokens alongside the legacy HS256 frontend tokens.
 * Passwords never reach this filter or the MCP adapter; only the bearer token is forwarded.
 */
@Component
public class OAuthBearerAuthenticationFilter extends OncePerRequestFilter {

    private final JwtDecoder jwtDecoder;
    private final CustomUserDetailsService userDetailsService;

    public OAuthBearerAuthenticationFilter(ObjectProvider<JwtDecoder> jwtDecoderProvider,
                                            CustomUserDetailsService userDetailsService) {
        this.jwtDecoder = jwtDecoderProvider.getIfAvailable();
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (jwtDecoder != null && token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(token, request);
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken) {
            String requiredScope = null;
            if (request.getRequestURI().startsWith("/api/v1/reading/")) {
                requiredScope = "SCOPE_paper.read";
            } else if (request.getRequestURI().startsWith("/api/v1/papers/upload/")) {
                requiredScope = "SCOPE_paper.upload";
            }
            if (requiredScope != null) {
                String scope = requiredScope;
                if (authentication.getAuthorities().stream()
                        .noneMatch(authority -> scope.equals(authority.getAuthority()))) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, scope + " scope is required");
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            Jwt jwt = jwtDecoder.decode(token);
            String username = jwt.getSubject();
            if (username == null || username.isBlank()) {
                return;
            }
            UserDetails user = userDetailsService.loadUserByUsername(username);
            List<GrantedAuthority> authorities = new ArrayList<>(user.getAuthorities());
            List<String> scopes = jwt.getClaimAsStringList("scope");
            if (scopes == null) {
                scopes = List.of();
            }
            for (String scope : scopes) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
            }
            String role = jwt.getClaimAsString("role");
            if (role != null && !role.isBlank()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            AbstractAuthenticationToken authentication = new JwtAuthenticationToken(jwt, authorities, username);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | UsernameNotFoundException | IllegalArgumentException ignored) {
            // The legacy JWT filter gets the next chance to authenticate this bearer token.
        }
    }

    private String extractToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
