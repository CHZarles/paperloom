package io.github.chzarles.paperloom.controller;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(ReadingQueryController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, PaperAccessAuthorizationFilter.class,
        PaperAccessService.class, ReadingQueryAudit.class})
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
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ReadingQueryAudit.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        var businessLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                "io.github.chzarles.paperloom.business");
        ListAppender<ILoggingEvent> businessEvents = new ListAppender<>();
        events.start();
        businessEvents.start();
        logger.addAppender(events);
        businessLogger.addAppender(businessEvents);
        try {
            mvc.perform(post("/api/v1/reading/papers/search")
                            .header("X-Request-ID", "unsafe anonymous request id")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/reading/papers/search")
                            .header("X-Request-ID", "unsafe guest request id")
                            .contentType(MediaType.APPLICATION_JSON).content("{}")
                            .with(user("guest").roles("GUEST")))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/reading/papers/search")
                            .contentType(MediaType.APPLICATION_JSON).content("{}")
                            .with(user("alice").roles("USER")))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/v1/reading/papers/search")
                            .contentType(MediaType.APPLICATION_JSON).content("{}")
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/admin/system/status"))
                    .andExpect(status().isForbidden());
        } finally {
            logger.detachAppender(events);
            businessLogger.detachAppender(businessEvents);
        }

        List<String> denials = events.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> !message.contains("outcome=SUCCESS"))
                .toList();
        org.junit.jupiter.api.Assertions.assertEquals(2, denials.size());
        String anonymous = denials.get(0);
        String guest = denials.get(1);
        org.junit.jupiter.api.Assertions.assertTrue(
                anonymous.contains("principal_id=unknown") && anonymous.contains("outcome=UNAUTHORIZED"));
        org.junit.jupiter.api.Assertions.assertTrue(
                guest.contains("principal_id=7") && guest.contains("outcome=FORBIDDEN"));
        org.junit.jupiter.api.Assertions.assertFalse((anonymous + guest).contains("unsafe"));
        org.junit.jupiter.api.Assertions.assertTrue(businessEvents.list.isEmpty());
    }

    @Test
    void requestAndResponsePayloadLoggersStayAtInfo() {
        var requestLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                "org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor");
        var responseLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor");
        var providerPayloadLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                "org.springframework.web.HttpLogging");
        var providerUrlLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                "org.springframework.web.reactive.function.client.ExchangeFunctions");
        var exceptionLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver");
        var routeLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping");
        var filterLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                "org.springframework.security.web.FilterChainProxy");
        org.junit.jupiter.api.Assertions.assertEquals(ch.qos.logback.classic.Level.INFO,
                requestLogger.getEffectiveLevel());
        org.junit.jupiter.api.Assertions.assertEquals(ch.qos.logback.classic.Level.INFO,
                responseLogger.getEffectiveLevel());
        org.junit.jupiter.api.Assertions.assertEquals(ch.qos.logback.classic.Level.INFO,
                providerPayloadLogger.getEffectiveLevel());
        org.junit.jupiter.api.Assertions.assertEquals(ch.qos.logback.classic.Level.INFO,
                providerUrlLogger.getEffectiveLevel());
        org.junit.jupiter.api.Assertions.assertEquals(ch.qos.logback.classic.Level.INFO,
                exceptionLogger.getEffectiveLevel());
        org.junit.jupiter.api.Assertions.assertEquals(ch.qos.logback.classic.Level.INFO,
                routeLogger.getEffectiveLevel());
        org.junit.jupiter.api.Assertions.assertEquals(ch.qos.logback.classic.Level.INFO,
                filterLogger.getEffectiveLevel());
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
