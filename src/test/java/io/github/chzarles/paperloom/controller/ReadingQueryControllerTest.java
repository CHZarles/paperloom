package io.github.chzarles.paperloom.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.chzarles.paperloom.config.ReadingQueryAudit;
import io.github.chzarles.paperloom.exception.QuotaExceededException;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.service.CanonicalReadingLocationService;
import io.github.chzarles.paperloom.service.PaperAccessService;
import io.github.chzarles.paperloom.service.PaperReadingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReadingQueryControllerTest {

    private final PaperReadingService readingService = mock(PaperReadingService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PaperAccessService paperAccessService = mock(PaperAccessService.class);
    private final ReadingQueryController controller =
            new ReadingQueryController(readingService, userRepository, paperAccessService,
                    new ObjectMapper(), new ReadingQueryAudit(userRepository));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
    private final UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken("alice", "");

    @Test
    void paperSearchDerivesUserAndDoesNotAcceptCallerScope() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(7L)));
        when(readingService.searchPaperCandidates(any())).thenReturn(
                new PaperReadingService.PaperSearchResult("query", List.of(), 0, 0, "complete", null));

        mvc.perform(post("/api/v1/reading/papers/search")
                .principal(authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query_text\":\"query\",\"limit\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returned_count").value(0))
                .andExpect(jsonPath("$.coverage").value("complete"));

        ArgumentCaptor<PaperReadingService.PaperSearchQuery> query =
                ArgumentCaptor.forClass(PaperReadingService.PaperSearchQuery.class);
        org.mockito.Mockito.verify(readingService).searchPaperCandidates(query.capture());
        assertEquals(7L, query.getValue().userId());
        assertEquals(List.of(), query.getValue().scopePaperIds());

        mvc.perform(post("/api/v1/reading/papers/search")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope_paper_ids\":[\"paper-a\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void contentSearchAndReadReturnOnlyPublicFields() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(7L)));
        when(readingService.searchPaperContent(any())).thenReturn(new PaperReadingService.ContentSearchResult(
                List.of(new PaperReadingService.LocationCandidate(
                        "paper-a", "Paper A", "rm-1", "location-a", "Methods", 2,
                        "passage", "preview", 0.9, 0.8, 0.85)),
                1, 1, "secret-index"));
        when(readingService.findPapersByIdentity(any())).thenReturn(new PaperReadingService.IdentitySearchResult(
                "resolved", List.of(card()), null));
        when(paperAccessService.canAccess("7", "paper-a")).thenReturn(true);
        when(readingService.readPaperContent(any())).thenReturn(new PaperReadingService.ContentReadResult(
                List.of(new CanonicalReadingLocationService.CanonicalLocation(
                        "paper-a", "Paper A", "rm-1", "location-a", "passage", 2, 2,
                        "Methods", "canonical content", "secret-bbox", "mineru", "1", "passage-a",
                        true, true, false, false, List.of())),
                List.of()));

        mvc.perform(post("/api/v1/reading/content/search")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paper_id\":\"paper-a\",\"query_text\":\"query\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locations[0].location_ref").value("location-a"))
                .andExpect(jsonPath("$.locations[0].dense_score").doesNotExist())
                .andExpect(jsonPath("$.index_version").doesNotExist());

        mvc.perform(post("/api/v1/reading/content/read")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paper_id\":\"paper-a\",\"location_refs\":[\"location-a\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].content").value("canonical content"))
                .andExpect(jsonPath("$.items[0].source_quote_ref").doesNotExist())
                .andExpect(jsonPath("$.items[0].source_span_json").doesNotExist())
                .andExpect(jsonPath("$.items[0].parser_name").doesNotExist())
                .andExpect(jsonPath("$.items[0].source_object_id").doesNotExist())
                .andExpect(jsonPath("$.items[0].bbox_json").doesNotExist());
    }

    @Test
    void publicQueryBoundsAreRejectedBeforeJavaReadingWork() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(7L)));

        mvc.perform(post("/api/v1/reading/papers/search")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query_text\":\"" + "x".repeat(4001) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingAuthenticatedDatabaseUserRemainsUnauthorized() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ReadingQueryAudit.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            mvc.perform(post("/api/v1/reading/papers/search")
                            .principal(authentication)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        } finally {
            logger.detachAppender(events);
        }
        assertEquals(1, events.list.size());
        assertTrue(events.list.get(0).getFormattedMessage().contains("outcome=UNAUTHORIZED"));
    }

    @Test
    void queryEndpointsAdaptTheirPagesToTheUtf8ResponseBudget() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(7L)));
        List<PaperReadingService.PaperCard> cards = IntStream.range(0, 100)
                .mapToObj(index -> new PaperReadingService.PaperCard(
                        "paper-" + index, "Paper " + index, List.of("Ada"), 2026, "Conf", null, null,
                        "paper.pdf", "汉".repeat(500)))
                .toList();
        when(readingService.searchPaperCandidates(any())).thenReturn(
                new PaperReadingService.PaperSearchResult("", cards, 100, 100, "complete", null));

        var response = mvc.perform(post("/api/v1/reading/papers/search")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage").value("truncated"))
                .andReturn().getResponse();

        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, Object>) new ObjectMapper().readValue(
                response.getContentAsByteArray(), java.util.Map.class);
        int returned = (Integer) body.get("returned_count");
        assertEquals(returned, body.get("next_offset"));
        org.junit.jupiter.api.Assertions.assertTrue(returned > 0 && returned < 100);
        org.junit.jupiter.api.Assertions.assertTrue(response.getContentAsByteArray().length <= 16 * 1024);

        when(readingService.getPaperStructure(any())).thenReturn(new PaperReadingService.StructureResult(
                "SECTION", IntStream.range(0, 100).mapToObj(index ->
                        new PaperReadingService.StructureItem(
                                "paper-a", "Paper A", "section-" + index, "SECTION",
                                "Section " + index, 1, "汉".repeat(500), index, 1, 1))
                        .toList()));
        var structure = mvc.perform(post("/api/v1/reading/structure")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paper_id\":\"paper-a\",\"limit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage").value("truncated"))
                .andReturn().getResponse();
        assertTrue(structure.getContentAsByteArray().length <= 16 * 1024);

        when(readingService.searchPaperContent(any())).thenReturn(new PaperReadingService.ContentSearchResult(
                IntStream.range(0, 20).mapToObj(index -> new PaperReadingService.LocationCandidate(
                        "paper-a", "Paper A", "rm-1", "location-" + index, "Methods", 1,
                        "passage", "汉".repeat(500), 0.9, 0.8, 0.85)).toList(),
                20, 20, "internal-index"));
        var content = mvc.perform(post("/api/v1/reading/content/search")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paper_id\":\"paper-a\",\"query_text\":\"query\",\"top_k\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage").value("truncated"))
                .andReturn().getResponse();
        assertTrue(content.getContentAsByteArray().length <= 16 * 1024);
    }

    @Test
    void unknownAndInaccessiblePapersHaveTheSamePublicFailure() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(7L)));
        when(paperAccessService.canAccess(anyString(), anyString())).thenReturn(false);

        var inaccessible = mvc.perform(post("/api/v1/reading/content/read")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paper_id\":\"private-paper\",\"location_refs\":[\"location-a\"]}"))
                .andReturn().getResponse();
        var unknown = mvc.perform(post("/api/v1/reading/content/read")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paper_id\":\"unknown-paper\",\"location_refs\":[\"location-a\"]}"))
                .andReturn().getResponse();

        assertEquals(inaccessible.getStatus(), unknown.getStatus());
        assertEquals(inaccessible.getContentAsString(), unknown.getContentAsString());
        verify(readingService, never()).readPaperContent(any());
    }

    @Test
    void exactReadRejectsOversizedContentAndHidesCrossPaperItems() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(7L)));
        when(paperAccessService.canAccess("7", "paper-a")).thenReturn(true);
        when(readingService.readPaperContent(any())).thenReturn(new PaperReadingService.ContentReadResult(
                List.of(canonical("paper-a", "large-ref", "汉".repeat(6_000))), List.of()));

        mvc.perform(post("/api/v1/reading/content/read")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paper_id\":\"paper-a\",\"location_refs\":[\"large-ref\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("READING_RESULT_TOO_LARGE"));

        when(readingService.readPaperContent(any())).thenReturn(new PaperReadingService.ContentReadResult(
                List.of(canonical("paper-b", "foreign-ref", "secret")), List.of()));
        mvc.perform(post("/api/v1/reading/content/read")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paper_id\":\"paper-a\",\"location_refs\":[\"foreign-ref\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.missing_location_refs[0]").value("foreign-ref"))
                .andExpect(jsonPath("$..content").doesNotExist());
    }

    @Test
    void embeddingQuotaFailureHasOneStablePublicError() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(7L)));
        when(readingService.searchPaperContent(any())).thenThrow(new QuotaExceededException("secret balance", 0));

        mvc.perform(post("/api/v1/reading/content/search")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paper_id\":\"paper-a\",\"query_text\":\"query\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("READING_QUOTA_EXHAUSTED"))
                .andExpect(jsonPath("$.message").value("Embedding quota is unavailable"));
    }

    @Test
    void currentModelFailureHasOneStablePublicError() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(7L)));
        when(readingService.getPaperStructure(any())).thenThrow(
                new io.github.chzarles.paperloom.service.PaperReadingException(
                        "READING_MODEL_NOT_READY", "The current reading model is not ready"));

        mvc.perform(post("/api/v1/reading/structure")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paper_id\":\"paper-a\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("READING_MODEL_NOT_READY"));
    }

    @Test
    void auditUsesBoundedFieldsAndReplacesAnUnsafeRequestId() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(7L)));
        when(readingService.searchPaperCandidates(any())).thenReturn(
                new PaperReadingService.PaperSearchResult("secret-query", List.of(), 0, 0, "complete", null));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ReadingQueryAudit.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            mvc.perform(post("/api/v1/reading/papers/search")
                            .principal(authentication)
                            .header("X-Request-ID", "unsafe request id")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query_text\":\"secret-query\"}"))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/v1/reading/papers/search")
                            .principal(authentication)
                            .header("X-Request-ID", "unsafe rejected request id")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query_text\":\"" + "secret".repeat(1000) + "\"}"))
                    .andExpect(status().isBadRequest());
        } finally {
            logger.detachAppender(events);
        }

        assertEquals(2, events.list.size());
        for (ILoggingEvent event : events.list) {
            String audit = event.getFormattedMessage();
            assertTrue(audit.contains("principal_id=7") && audit.contains("tool_name=search_papers"));
            assertFalse(audit.contains("unsafe request id"));
            assertFalse(audit.contains("unsafe rejected request id"));
            assertFalse(audit.contains("secret"));
        }
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("alice");
        user.setRole(User.Role.USER);
        return user;
    }

    private PaperReadingService.PaperCard card() {
        return new PaperReadingService.PaperCard(
                "paper-a", "Paper A", List.of("Ada"), 2026, "Conf", "doi", "arxiv", "paper.pdf", "preview");
    }

    private CanonicalReadingLocationService.CanonicalLocation canonical(
            String paperId, String ref, String content) {
        return new CanonicalReadingLocationService.CanonicalLocation(
                paperId, "Paper", "rm-1", ref, "passage", 1, 1, "Methods", content,
                "", "mineru", "1", ref, false, false, false, false, List.of());
    }
}
