package io.github.chzarles.paperloom.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {
        "jwt.secret-key=MDEyMzQ1Njc4OWFiY2RlZg==",
        "model-provider.security.secret-key=MDEyMzQ1Njc4OWFiY2RlZg==",
        "spring.datasource.url=jdbc:h2:mem:paperloom_oauth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "paper.bootstrap.enabled=false",
        "qdrant.base-url=http://127.0.0.1:6333"
})
@AutoConfigureMockMvc
class OAuthAuthorizationServerSmokeTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void authorizationServerPublishesDiscoveryMetadata() throws Exception {
        mvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://127.0.0.1:8081"))
                .andExpect(jsonPath("$.authorization_endpoint").value("http://127.0.0.1:8081/oauth2/authorize"))
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"));
    }

    @Test
    void authorizationEndpointRedirectsAnonymousBrowserToLogin() throws Exception {
        mvc.perform(get("/oauth2/authorize?response_type=code&client_id=paperloom-local-mcp&redirect_uri=http://127.0.0.1:8095/oauth/callback&scope=paper.read&state=state&code_challenge=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&code_challenge_method=S256"))
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void authorizationEndpointRedirectsAuthenticatedBrowserToConsent() throws Exception {
        mvc.perform(get("/oauth2/authorize?response_type=code&client_id=paperloom-local-mcp&redirect_uri=http://127.0.0.1:8095/oauth/callback&scope=paper.read&state=state&code_challenge=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&code_challenge_method=S256")
                        .with(SecurityMockMvcRequestPostProcessors.user("alice").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Consent required")));
    }
}
