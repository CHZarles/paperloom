package io.github.chzarles.paperloom.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RFC 7591 registration for remote MCP clients.
 *
 * Only public Authorization Code + PKCE clients are accepted. The endpoint deliberately does not
 * issue a client secret: MCP clients run on a user's machine and cannot keep one confidential.
 */
@RestController
public class OAuthClientRegistrationController {

    private static final List<String> SUPPORTED_SCOPES = List.of("paper.read", "paper.upload");

    private final RegisteredClientRepository registeredClientRepository;

    public OAuthClientRegistrationController(RegisteredClientRepository registeredClientRepository) {
        this.registeredClientRepository = registeredClientRepository;
    }

    @PostMapping(path = "/connect/register", consumes = "application/json", produces = "application/json")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> request) {
        List<String> redirectUris = strings(request.get("redirect_uris"));
        if (redirectUris.isEmpty() || redirectUris.size() > 10 || redirectUris.stream().anyMatch(uri -> !isAllowedRedirectUri(uri))) {
            return error("invalid_client_metadata", "redirect_uris");
        }
        if (!matchesOnly(request.get("grant_types"), "authorization_code")
                || !matchesOnly(request.get("response_types"), "code")) {
            return error("invalid_client_metadata", "grant_types/response_types");
        }
        Object authMethod = request.get("token_endpoint_auth_method");
        if (authMethod != null && !"none".equals(authMethod)) {
            return error("invalid_client_metadata", "token_endpoint_auth_method");
        }

        List<String> scopes = requestedScopes(request.get("scope"));
        if (scopes == null) {
            return error("invalid_client_metadata", "scope");
        }

        String clientId = "paperloom-mcp-" + UUID.randomUUID().toString().replace("-", "");
        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(10))
                        .refreshTokenTimeToLive(Duration.ofDays(30))
                        .reuseRefreshTokens(false)
                        .build());
        redirectUris.forEach(builder::redirectUri);
        scopes.forEach(builder::scope);
        RegisteredClient client = builder.build();
        registeredClientRepository.save(client);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client_id", clientId);
        response.put("client_id_issued_at", Instant.now().getEpochSecond());
        response.put("client_name", request.getOrDefault("client_name", "PaperLoom MCP client"));
        response.put("redirect_uris", redirectUris);
        response.put("grant_types", List.of("authorization_code"));
        response.put("response_types", List.of("code"));
        response.put("token_endpoint_auth_method", "none");
        response.put("scope", String.join(" ", scopes));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private static ResponseEntity<Map<String, Object>> error(String error, String parameter) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", error,
                "error_description", "Unsupported or invalid " + parameter));
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank()) {
                return List.of();
            }
            result.add(text);
        }
        return result;
    }

    private static boolean matchesOnly(Object value, String expected) {
        if (value == null) {
            return true;
        }
        List<String> values = strings(value);
        return values.size() == 1 && expected.equals(values.get(0));
    }

    private static List<String> requestedScopes(Object value) {
        if (value == null) {
            return List.of("paper.read");
        }
        if (!(value instanceof String text)) {
            return null;
        }
        List<String> scopes = List.of(text.trim().split("\\s+"));
        if (scopes.isEmpty() || scopes.stream().anyMatch(scope -> !SUPPORTED_SCOPES.contains(scope))) {
            return null;
        }
        return scopes;
    }

    private static boolean isAllowedRedirectUri(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getHost() == null) {
                return false;
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return true;
            }
            return "http".equalsIgnoreCase(uri.getScheme())
                    && ("127.0.0.1".equals(uri.getHost())
                    || "localhost".equalsIgnoreCase(uri.getHost())
                    || "::1".equals(uri.getHost()));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
