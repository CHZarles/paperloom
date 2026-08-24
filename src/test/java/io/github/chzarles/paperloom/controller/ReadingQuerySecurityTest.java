package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.config.JwtAuthenticationFilter;
import io.github.chzarles.paperloom.config.PaperAccessAuthorizationFilter;
import io.github.chzarles.paperloom.config.SecurityConfig;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.PaperPublicationRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.service.CustomUserDetailsService;
import io.github.chzarles.paperloom.service.PaperAccessService;
import io.github.chzarles.paperloom.service.PaperReadingService;
import io.github.chzarles.paperloom.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(ReadingQueryController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, PaperAccessAuthorizationFilter.class,
        PaperAccessService.class})
class ReadingQuerySecurityTest {

    @jakarta.annotation.Resource
    private MockMvc mvc;

    @MockBean
    private PaperReadingService readingService;

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

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws Exception {
        when(userRepository.findByUsername(any())).thenAnswer(invocation ->
                Optional.of(productUser(invocation.getArgument(0))));
        when(readingService.searchPaperCandidates(any())).thenReturn(
                new PaperReadingService.PaperSearchResult("", List.of(), 0, 0, "complete", null));
    }

    @Test
    void onlyUserAndAdminRolesCanUsePublicReadingQueries() throws Exception {
        var request = post("/api/v1/reading/papers/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");

        mvc.perform(request).andExpect(status().isForbidden());
        mvc.perform(request.with(user("guest").roles("GUEST"))).andExpect(status().isForbidden());
        mvc.perform(request.with(user("alice").roles("USER"))).andExpect(status().isOk());
        mvc.perform(request.with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void adminRoleDoesNotBypassPaperAcl() throws Exception {
        var request = post("/api/v1/reading/content/read")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paper_id\":\"private-paper\",\"location_refs\":[\"location-a\"]}");

        mvc.perform(request.with(user("alice").roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAPER_NOT_AVAILABLE"));
        mvc.perform(request.with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAPER_NOT_AVAILABLE"));
    }

    private User productUser(String username) {
        User user = new User();
        user.setId("admin".equals(username) ? 1L : 7L);
        user.setUsername(username);
        user.setRole("admin".equals(username) ? User.Role.ADMIN : User.Role.USER);
        return user;
    }
}
