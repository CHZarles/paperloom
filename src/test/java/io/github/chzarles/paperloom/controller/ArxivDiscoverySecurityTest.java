package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.config.JwtAuthenticationFilter;
import io.github.chzarles.paperloom.config.PaperAccessAuthorizationFilter;
import io.github.chzarles.paperloom.config.ReadingQueryAudit;
import io.github.chzarles.paperloom.config.SecurityConfig;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.PaperPublicationRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.service.CustomUserDetailsService;
import io.github.chzarles.paperloom.service.PaperAccessService;
import io.github.chzarles.paperloom.service.discovery.ArxivDiscoveryService;
import io.github.chzarles.paperloom.service.discovery.ArxivSearchPage;
import io.github.chzarles.paperloom.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ArxivDiscoveryController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, PaperAccessAuthorizationFilter.class,
        PaperAccessService.class, ReadingQueryAudit.class})
class ArxivDiscoverySecurityTest {

    @jakarta.annotation.Resource
    private MockMvc mvc;

    @MockBean
    private ArxivDiscoveryService arxivDiscoveryService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    @MockBean
    private PaperRepository paperRepository;

    @MockBean
    private PaperPublicationRepository paperPublicationRepository;

    @BeforeEach
    void setUp() {
        when(userRepository.findByUsername(any())).thenAnswer(invocation ->
                Optional.of(productUser(invocation.getArgument(0))));
        when(arxivDiscoveryService.search(anyString(), any(), any(), nullable(String.class), anyString()))
                .thenReturn(new ArxivSearchPage(List.of(), 0, 0, 20));
    }

    @Test
    void discoverySearchIsLimitedToUserAndAdmin() throws Exception {
        mvc.perform(get("/api/v1/discovery/arxiv/search").param("query", "rag").param("sort", "relevance"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/discovery/arxiv/search").param("query", "rag")
                        .with(user("guest").roles("GUEST")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/discovery/arxiv/search").param("query", "rag")
                        .requestAttr("userId", "7")
                        .with(user("alice").roles("USER")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/discovery/arxiv/search").param("query", "rag")
                        .requestAttr("userId", "1")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    private User productUser(String username) {
        User user = new User();
        user.setId("admin".equals(username) ? 1L : 7L);
        user.setUsername(username);
        if ("guest".equals(username)) {
            user.setRole(User.Role.GUEST);
        } else if ("admin".equals(username)) {
            user.setRole(User.Role.ADMIN);
        } else {
            user.setRole(User.Role.USER);
        }
        return user;
    }
}
