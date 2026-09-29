package io.github.chzarles.paperloom.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.UserRepository;

import java.time.Duration;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import java.util.List;

/**
 * Local OAuth Authorization Server for the first MCP login slice.
 *
 * The client and authorization state are intentionally in-memory in this slice. This keeps the
 * browser login contract small while the durable JDBC store is added in the token-lifecycle slice.
 */
@Configuration
public class OAuth2AuthorizationServerConfig {

    private static final String MCP_CLIENT_ID = "paperloom-local-mcp";
    private static final String MCP_REDIRECT_URI = "http://127.0.0.1:8095/oauth/callback";

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            AuthorizationServerSettings authorizationServerSettings) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();
        authorizationServerConfigurer.authorizationServerMetadataEndpoint(metadata -> metadata
                .authorizationServerMetadataCustomizer(metadataBuilder -> metadataBuilder
                        .clientRegistrationEndpoint(
                                authorizationServerSettings.getIssuer()
                                        + authorizationServerSettings.getOidcClientRegistrationEndpoint())));
        http
                .securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
                .with(authorizationServerConfigurer, Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/connect/register").permitAll()
                        .anyRequest().authenticated());

        AuthenticationEntryPoint loginEntryPoint = new LoginUrlAuthenticationEntryPoint("/login");
        http.exceptionHandling(exceptions -> exceptions
                .defaultAuthenticationEntryPointFor(
                        loginEntryPoint,
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    /**
     * The authorization endpoint redirects here when the browser has not authenticated yet.
     * This chain has a session for the browser flow while the API chain remains stateless.
     */
    @Bean
    @Order(2)
    SecurityFilterChain loginSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/login", "/error", "/oauth2/consent")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/oauth2/consent").authenticated()
                        .anyRequest().permitAll())
                .formLogin(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        return http.build();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository() {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(MCP_CLIENT_ID)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(MCP_REDIRECT_URI)
                .scope("paper.read")
                .scope("paper.upload")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(10))
                        .refreshTokenTimeToLive(Duration.ofDays(30))
                        .reuseRefreshTokens(false)
                        .build())
                .build();
        return new InMemoryRegisteredClientRepository(client);
    }

    @Bean
    OAuth2AuthorizationService authorizationService() {
        return new InMemoryOAuth2AuthorizationService();
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService() {
        return new InMemoryOAuth2AuthorizationConsentService();
    }

    @Bean
    JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = generateRsaKey();
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(
            @Value("${paperloom.oauth2.issuer:http://127.0.0.1:8081}") String issuer) {
        return AuthorizationServerSettings.builder()
                .issuer(issuer)
                .oidcClientRegistrationEndpoint("/connect/register")
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource,
                          AuthorizationServerSettings settings) {
        NimbusJwtDecoder decoder = (NimbusJwtDecoder)
                OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        OAuth2TokenValidator<Jwt> issuerValidator =
                org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer(settings.getIssuer());
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud", audience -> audience != null && audience.contains(MCP_CLIENT_ID));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
        return decoder;
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer(UserRepository userRepository) {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                return;
            }
            String username = context.getPrincipal().getName();
            User user = userRepository.findByUsername(username).orElse(null);
            if (user != null) {
                context.getClaims()
                        .claim("userId", user.getId().toString())
                        .claim("role", user.getRole().name())
                        .audience(List.of(MCP_CLIENT_ID));
            }
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private KeyPair generateRsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception error) {
            throw new IllegalStateException("Unable to generate OAuth signing key", error);
        }
    }
}
