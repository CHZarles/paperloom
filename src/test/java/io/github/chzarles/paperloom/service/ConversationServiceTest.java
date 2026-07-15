package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.Conversation;
import io.github.chzarles.paperloom.model.ConversationSourceQuote;
import io.github.chzarles.paperloom.model.ConversationSession;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperSourceQuote;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.ConversationSourceQuoteRepository;
import io.github.chzarles.paperloom.repository.ConversationRepository;
import io.github.chzarles.paperloom.repository.ConversationSessionRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperSourceQuoteRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ConversationServiceTest {

    private static final String LEGACY_SOURCE_TYPE = "EVAL" + "_IMPORT";
    private static final String LEGACY_STRUCTURED_FIELD = "structured" + "Import";
    private static final String LEGACY_EVAL_FIELD = "eval" + "Import";
    private static final String LEGACY_ASSET_WARNING = "structured_" + "import_text_only";

    @Mock
    private UserRepository userRepository;

    @Mock
    private ConversationSourceQuoteRepository conversationSourceQuoteRepository;

    @Mock
    private PaperSourceQuoteRepository sourceQuoteRepository;

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private ProductReadingSourceQuoteResolver sourceQuoteResolver;

    @Mock
    private ConversationScopeService conversationScopeService;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ConversationSessionRepository sessionRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void testRecordConversation() {
        User user = new User();
        user.setId(1L);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        conversationService.recordConversation("testuser", "What is AI?", "AI stands for Artificial Intelligence.");

        verify(conversationRepository, times(1)).save(any(Conversation.class));
    }

    @Test
    void testGetConversations() {
        User user = new User();
        user.setId(1L);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        Conversation conversation = new Conversation();
        conversation.setId(1L);
        conversation.setQuestion("What is AI?");
        conversation.setAnswer("AI stands for Artificial Intelligence.");
        when(conversationRepository.findByUserIdOrderByTimestampAsc(anyLong())).thenReturn(List.of(conversation));

        var result = conversationService.getConversations("testuser", null, null);
        assertEquals(1, result.size());
    }

    @Test
    void getMessagesByConversationIdQueriesWithinCurrentUserScope() {
        when(conversationRepository.findByUserIdAndConversationIdOrderByTimestampAsc(2L, "conversation-1"))
                .thenReturn(List.of());

        var result = conversationService.getMessagesByConversationId(2L, "conversation-1");

        assertEquals(0, result.size());
        verify(conversationRepository).findByUserIdAndConversationIdOrderByTimestampAsc(2L, "conversation-1");
    }

    @Test
    void recordConversationPersistsEffectiveScopeJson() throws Exception {
        ReflectionTestUtils.setField(conversationService, "objectMapper", new ObjectMapper());
        User user = new User();
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        Map<String, Object> effectiveScope = new LinkedHashMap<>();
        effectiveScope.put("scopeMode", "SOURCE_SET_SNAPSHOT");
        effectiveScope.put("sourceLabel", "Agent papers");
        effectiveScope.put("paperIds", List.of("p1", "p2"));
        effectiveScope.put("paperCount", 2);

        conversationService.recordConversation(
                1L,
                "Question",
                "Answer",
                "conversation-1",
                Map.of(),
                effectiveScope
        );

        ArgumentCaptor<Conversation> conversationCaptor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(conversationCaptor.capture());
        Map<String, Object> persistedScope = new ObjectMapper().readValue(
                conversationCaptor.getValue().getEffectiveScopeJson(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                }
        );
        assertEquals("SOURCE_SET_SNAPSHOT", persistedScope.get("scopeMode"));
        assertEquals("Agent papers", persistedScope.get("sourceLabel"));
        assertEquals(List.of("p1", "p2"), persistedScope.get("paperIds"));
        assertEquals(2, persistedScope.get("paperCount"));
    }

    @Test
    void recordConversationPersistsReadingArtifactsAndStatePatch() throws Exception {
        ReflectionTestUtils.setField(conversationService, "objectMapper", new ObjectMapper());
        User user = new User();
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        ConversationSession session = new ConversationSession();
        session.setConversationId("conversation-1");
        session.setTitle("Existing title");
        when(sessionRepository.findByConversationIdAndUserId("conversation-1", 1L))
                .thenReturn(Optional.of(session));
        ReadingTurnArtifacts artifacts = ReadingTurnArtifacts.empty("read agent evaluation papers");
        ReadingStatePatch statePatch = new ReadingStatePatch(
                new ReadingStatePatch.SelectedPaper(
                        "paper-1",
                        "paper_handle_abc",
                        "Agentic Eval Benchmark",
                        "agentic-eval.pdf"
                ),
                null,
                null,
                List.of()
        );

        conversationService.recordConversation(
                1L,
                "Question",
                "Answer",
                "conversation-1",
                Map.of(),
                Map.of(),
                artifacts,
                statePatch
        );

        ArgumentCaptor<Conversation> conversationCaptor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(conversationCaptor.capture());
        Conversation conversation = conversationCaptor.getValue();
        assertTrue(conversation.getReadingArtifactsJson().contains("read agent evaluation papers"));
        assertTrue(conversation.getReadingStatePatchJson().contains("paper_handle_abc"));

        List<Map<String, Object>> messages = conversationService.toMessageHistory(List.of(conversation), false);
        Map<String, Object> assistantMessage = messages.get(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> persistedPatch = (Map<String, Object>) assistantMessage.get("readingStatePatch");
        @SuppressWarnings("unchecked")
        Map<String, Object> selectedPaper = (Map<String, Object>) persistedPatch.get("selectedPaper");
        assertEquals("paper_handle_abc", selectedPaper.get("paperHandle"));
        assertTrue(assistantMessage.containsKey("readingArtifacts"));
        assertTrue(session.getConversationMemoryJson().contains("paper_handle_abc"));
    }

    @Test
    void findLatestReadingStatePatchReturnsNewestPersistedPatch() {
        ReflectionTestUtils.setField(conversationService, "objectMapper", new ObjectMapper());
        Conversation older = new Conversation();
        older.setReadingStatePatchJson("""
                {
                  "selectedPaper": {
                    "paperHandle": "paper_handle_old",
                    "title": "Old"
                  }
                }
                """);
        Conversation newer = new Conversation();
        newer.setReadingStatePatchJson("""
                {
                  "selectedPaper": {
                    "paperHandle": "paper_handle_new",
                    "title": "New"
                  }
                }
                """);
        when(conversationRepository.findByUserIdAndConversationIdOrderByTimestampAsc(1L, "conversation-1"))
                .thenReturn(List.of(older, newer));

        Optional<Map<String, Object>> patch =
                conversationService.findLatestReadingStatePatch(1L, "conversation-1");

        assertTrue(patch.isPresent());
        @SuppressWarnings("unchecked")
        Map<String, Object> selectedPaper = (Map<String, Object>) patch.get().get("selectedPaper");
        assertEquals("paper_handle_new", selectedPaper.get("paperHandle"));
    }

    @Test
    void findLatestReadingStatePatchPrefersSessionMemory() {
        ReflectionTestUtils.setField(conversationService, "objectMapper", new ObjectMapper());
        ConversationSession session = new ConversationSession();
        session.setConversationMemoryJson("""
                {
                  "readingStatePatch": {
                    "selectedPaper": {
                      "paperHandle": "paper_handle_session",
                      "title": "Session Target"
                    }
                  }
                }
                """);
        when(sessionRepository.findByConversationIdAndUserId("conversation-1", 1L))
                .thenReturn(Optional.of(session));

        Optional<Map<String, Object>> patch =
                conversationService.findLatestReadingStatePatch(1L, "conversation-1");

        assertTrue(patch.isPresent());
        @SuppressWarnings("unchecked")
        Map<String, Object> selectedPaper = (Map<String, Object>) patch.get().get("selectedPaper");
        assertEquals("paper_handle_session", selectedPaper.get("paperHandle"));
        verify(conversationRepository, never()).findByUserIdAndConversationIdOrderByTimestampAsc(anyLong(), anyString());
    }

    @Test
    void messageHistoryIncludesEffectiveScopeOnUserMessageOnly() {
        ReflectionTestUtils.setField(conversationService, "objectMapper", new ObjectMapper());
        Conversation conversation = new Conversation();
        conversation.setId(10L);
        conversation.setQuestion("Question");
        conversation.setAnswer("Answer");
        conversation.setConversationId("conversation-1");
        conversation.setEffectiveScopeJson("""
                {
                  "scopeMode": "SOURCE_SET_SNAPSHOT",
                  "sourceLabel": "Agent papers",
                  "paperIds": ["p1", "p2"],
                  "paperCount": 2
                }
                """);
        ReflectionTestUtils.setField(conversation, "timestamp", LocalDateTime.of(2026, 6, 28, 12, 0));

        List<Map<String, Object>> messages = conversationService.toMessageHistory(List.of(conversation), false);

        assertEquals(2, messages.size());
        Map<String, Object> userMessage = messages.get(0);
        Map<String, Object> assistantMessage = messages.get(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> effectiveScope = (Map<String, Object>) userMessage.get("effectiveScope");
        assertEquals("SOURCE_SET_SNAPSHOT", effectiveScope.get("scopeMode"));
        assertEquals("Agent papers", effectiveScope.get("sourceLabel"));
        assertEquals(List.of("p1", "p2"), effectiveScope.get("paperIds"));
        assertEquals(2, effectiveScope.get("paperCount"));
        assertEquals(false, assistantMessage.containsKey("effectiveScope"));
    }

    @Test
    void switchCurrentConversationRejectsConversationNotOwnedByCurrentUser() {
        when(sessionRepository.findByConversationIdAndUserId("other-conversation", 2L))
                .thenReturn(Optional.empty());

        CustomException exception = assertThrows(CustomException.class,
                () -> conversationService.switchCurrentConversation(2L, "other-conversation"));

        assertEquals("对话不存在", exception.getMessage());
        verify(valueOperations, never()).set(anyString(), anyString(), any());
        verify(sessionRepository, never()).existsByConversationId(anyString());
    }

    @Test
    void requireActiveOwnedConversationSessionReturnsActiveOwnedSession() {
        ConversationSession session = new ConversationSession();
        session.setConversationId("conversation-1");
        session.setStatus(ConversationSession.SessionStatus.ACTIVE);
        when(sessionRepository.findByConversationIdAndUserId("conversation-1", 1L))
                .thenReturn(Optional.of(session));

        ConversationSession result = conversationService.requireActiveOwnedConversationSession(1L, "conversation-1");

        assertEquals(session, result);
    }

    @Test
    void requireActiveOwnedConversationSessionRejectsArchivedSession() {
        ConversationSession session = new ConversationSession();
        session.setConversationId("conversation-1");
        session.setStatus(ConversationSession.SessionStatus.ARCHIVED);
        when(sessionRepository.findByConversationIdAndUserId("conversation-1", 1L))
                .thenReturn(Optional.of(session));

        CustomException exception = assertThrows(CustomException.class,
                () -> conversationService.requireActiveOwnedConversationSession(1L, "conversation-1"));

        assertEquals("对话不存在", exception.getMessage());
    }

    @Test
    void archiveConversationSessionRejectsConversationNotOwnedByCurrentUser() {
        when(sessionRepository.findByConversationIdAndUserId("other-conversation", 2L))
                .thenReturn(Optional.empty());

        CustomException exception = assertThrows(CustomException.class,
                () -> conversationService.archiveConversationSession(2L, "other-conversation"));

        assertEquals("对话不存在", exception.getMessage());
        verify(sessionRepository, never()).save(any(ConversationSession.class));
    }

    @Test
    void unarchiveConversationSessionRejectsConversationNotOwnedByCurrentUser() {
        when(sessionRepository.findByConversationIdAndUserId("other-conversation", 2L))
                .thenReturn(Optional.empty());

        CustomException exception = assertThrows(CustomException.class,
                () -> conversationService.unarchiveConversationSession(2L, "other-conversation"));

        assertEquals("对话不存在", exception.getMessage());
        verify(sessionRepository, never()).save(any(ConversationSession.class));
    }

    @Test
    void deleteConversationSessionDeletesOwnedSessionAndConversationRows() {
        ConversationSession session = new ConversationSession();
        session.setConversationId("conversation-1");
        when(sessionRepository.findByConversationIdAndUserId("conversation-1", 1L))
                .thenReturn(Optional.of(session));
        when(valueOperations.get("user:1:current_conversation")).thenReturn("other-conversation");

        conversationService.deleteConversationSession(1L, "conversation-1");

        verify(conversationRepository).deleteByUserIdAndConversationId(1L, "conversation-1");
        verify(sessionRepository).delete(session);
        verify(redisTemplate, never()).delete("user:1:current_conversation");
    }

    @Test
    void deleteConversationSessionClearsRedisWhenDeletingCurrentSession() {
        ConversationSession session = new ConversationSession();
        session.setConversationId("conversation-1");
        when(sessionRepository.findByConversationIdAndUserId("conversation-1", 1L))
                .thenReturn(Optional.of(session));
        when(valueOperations.get("user:1:current_conversation")).thenReturn("conversation-1");

        conversationService.deleteConversationSession(1L, "conversation-1");

        verify(redisTemplate).delete("user:1:current_conversation");
    }

    @Test
    void deleteConversationSessionRejectsConversationNotOwnedByCurrentUser() {
        when(sessionRepository.findByConversationIdAndUserId("other-conversation", 2L))
                .thenReturn(Optional.empty());

        CustomException exception = assertThrows(CustomException.class,
                () -> conversationService.deleteConversationSession(2L, "other-conversation"));

        assertEquals("对话不存在", exception.getMessage());
        verify(conversationRepository, never()).deleteByUserIdAndConversationId(anyLong(), anyString());
        verify(sessionRepository, never()).delete(any(ConversationSession.class));
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void createConversationSessionIncludesDefaultScopeFields() {
        User user = new User();
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(sessionRepository.save(any(ConversationSession.class))).thenAnswer(invocation -> {
            ConversationSession session = invocation.getArgument(0);
            ReflectionTestUtils.setField(session, "id", 10L);
            ReflectionTestUtils.setField(session, "createdAt", LocalDateTime.of(2026, 6, 28, 12, 0));
            ReflectionTestUtils.setField(session, "updatedAt", LocalDateTime.of(2026, 6, 28, 12, 0));
            return session;
        });

        Map<String, Object> result = conversationService.createConversationSession(1L);

        assertEquals("AUTO_LIBRARY", result.get("scopeMode"));
        assertEquals(false, result.get("scopeLocked"));
        assertEquals("READY", result.get("scopeStatus"));
        assertEquals("All readable papers", result.get("sourceLabel"));
        assertEquals(0, result.get("sourcePaperCount"));
    }

    @Test
    void createConversationSessionIncludesAutoLibraryReadablePaperCountWhenAvailable() {
        User user = new User();
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(conversationScopeService.autoLibraryReadablePaperCount(1L)).thenReturn(30);
        when(sessionRepository.save(any(ConversationSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = conversationService.createConversationSession(1L);

        assertEquals("AUTO_LIBRARY", result.get("scopeMode"));
        assertEquals("All readable papers", result.get("sourceLabel"));
        assertEquals(30, result.get("sourcePaperCount"));
    }

    @Test
    void getConversationSessionsIncludesStoredSnapshotSummaryFields() {
        ConversationSession session = new ConversationSession();
        ReflectionTestUtils.setField(session, "id", 10L);
        session.setConversationId("conversation-1");
        session.setTitle("Reading set");
        session.setStatus(ConversationSession.SessionStatus.ACTIVE);
        session.setScopeMode(io.github.chzarles.paperloom.model.ConversationScopeMode.SOURCE_SET_SNAPSHOT);
        session.setScopeLocked(true);
        session.setScopeStatus(io.github.chzarles.paperloom.model.ConversationScopeStatus.READY);
        session.setSourceLabel("Custom reading set");
        session.setSourcePaperCount(2);
        ReflectionTestUtils.setField(session, "createdAt", LocalDateTime.of(2026, 6, 28, 12, 0));
        ReflectionTestUtils.setField(session, "updatedAt", LocalDateTime.of(2026, 6, 28, 12, 5));
        when(sessionRepository.findByUserIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(session));
        when(conversationRepository.findDistinctConversationIdsByUserId(1L)).thenReturn(List.of("conversation-1"));

        List<Map<String, Object>> result = conversationService.getConversationSessions(1L);

        assertEquals(1, result.size());
        Map<String, Object> item = result.get(0);
        assertEquals("SOURCE_SET_SNAPSHOT", item.get("scopeMode"));
        assertEquals(true, item.get("scopeLocked"));
        assertEquals("READY", item.get("scopeStatus"));
        assertEquals("Custom reading set", item.get("sourceLabel"));
        assertEquals(2, item.get("sourcePaperCount"));
    }

    @Test
    void getConversationSessionsHidesEmptySessions() {
        ConversationSession empty = new ConversationSession();
        ReflectionTestUtils.setField(empty, "id", 11L);
        empty.setConversationId("empty-session");
        empty.setTitle("新对话");
        empty.setStatus(ConversationSession.SessionStatus.ACTIVE);
        ConversationSession used = new ConversationSession();
        ReflectionTestUtils.setField(used, "id", 12L);
        used.setConversationId("used-session");
        used.setTitle("Reading session");
        used.setStatus(ConversationSession.SessionStatus.ACTIVE);
        when(sessionRepository.findByUserIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(empty, used));
        when(conversationRepository.findDistinctConversationIdsByUserId(1L)).thenReturn(List.of("used-session"));

        List<Map<String, Object>> result = conversationService.getConversationSessions(1L);

        assertEquals(1, result.size());
        assertEquals("used-session", result.get(0).get("conversationId"));
    }

    @Test
    void getCurrentConversationSessionSkipsEmptyRedisSessionAndUsesNewestNonEmptyActiveSession() {
        ConversationSession empty = new ConversationSession();
        ReflectionTestUtils.setField(empty, "id", 11L);
        empty.setConversationId("empty-session");
        empty.setTitle("新对话");
        empty.setStatus(ConversationSession.SessionStatus.ACTIVE);
        ConversationSession used = new ConversationSession();
        ReflectionTestUtils.setField(used, "id", 12L);
        used.setConversationId("used-session");
        used.setTitle("Reading session");
        used.setStatus(ConversationSession.SessionStatus.ACTIVE);

        String redisKey = "user:1:current_conversation";
        when(valueOperations.get(redisKey)).thenReturn("empty-session");
        when(sessionRepository.findByConversationIdAndUserId("empty-session", 1L)).thenReturn(Optional.of(empty));
        when(sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(
                1L,
                ConversationSession.SessionStatus.ACTIVE
        )).thenReturn(List.of(empty, used));
        when(conversationRepository.existsByUserIdAndConversationId(1L, "empty-session")).thenReturn(false);
        when(conversationRepository.existsByUserIdAndConversationId(1L, "used-session")).thenReturn(true);

        Optional<Map<String, Object>> result = conversationService.getCurrentConversationSession(1L);

        assertTrue(result.isPresent());
        assertEquals("used-session", result.get().get("conversationId"));
        verify(redisTemplate).delete(redisKey);
        verify(valueOperations).set(redisKey, "used-session", Duration.ofDays(7));
    }

    @Test
    void getCurrentConversationSessionReturnsEmptyWhenOnlyActiveSessionsAreEmpty() {
        ConversationSession empty = new ConversationSession();
        ReflectionTestUtils.setField(empty, "id", 11L);
        empty.setConversationId("empty-session");
        empty.setTitle("新对话");
        empty.setStatus(ConversationSession.SessionStatus.ACTIVE);

        String redisKey = "user:1:current_conversation";
        when(valueOperations.get(redisKey)).thenReturn(null);
        when(sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(
                1L,
                ConversationSession.SessionStatus.ACTIVE
        )).thenReturn(List.of(empty));
        when(conversationRepository.existsByUserIdAndConversationId(1L, "empty-session")).thenReturn(false);

        Optional<Map<String, Object>> result = conversationService.getCurrentConversationSession(1L);

        assertTrue(result.isEmpty());
        verify(valueOperations, never()).set(eq(redisKey), anyString(), any(Duration.class));
    }

    @Test
    void findReferenceDetailRestoresPaperEvidenceFieldsFromMysqlHistory() {
        ReflectionTestUtils.setField(conversationService, "objectMapper", new ObjectMapper());
        Conversation conversation = new Conversation();
        conversation.setId(10L);
        conversation.setReferenceMappingsJson("""
                {
                  "1": {
                    "paperId": "paper-1",
                    "paperTitle": "Parsed Paper Title",
                    "originalFilename": "uploaded-paper.pdf",
                    "pageNumber": 3,
                    "anchorText": "anchor text",
                    "retrievalMode": "HYBRID",
                    "retrievalLabel": "混合召回",
                    "retrievalQuery": "bandit method",
                    "matchedChunkText": "matched chunk text",
                    "evidenceSnippet": "evidence snippet",
                    "score": 0.82,
                    "chunkId": 17,
                    "elementType": "PARAGRAPH",
                    "sectionTitle": "Method",
                    "sectionLevel": 2,
                    "bboxJson": "{\\"x1\\":10,\\"y1\\":20,\\"x2\\":300,\\"y2\\":360}",
                    "parserName": "OpenDataLoader",
                    "parserVersion": "2.4.7",
                    "sourceType": "%s",
                    "evidenceAssetLevel": "TEXT_ONLY",
                    "pdfEvidenceAvailable": false,
                    "%s": true,
                    "%s": true,
                    "pageScreenshotAvailable": false,
                    "figureScreenshotAvailable": false,
                    "assetWarnings": ["%s"]
                  }
                }
                """.formatted(LEGACY_SOURCE_TYPE, LEGACY_STRUCTURED_FIELD, LEGACY_EVAL_FIELD, LEGACY_ASSET_WARNING));
        when(conversationRepository.findByIdAndUserId(10L, 2L)).thenReturn(Optional.of(conversation));

        Optional<Map<String, Object>> detailOpt = conversationService.findReferenceDetail(2L, 10L, 1);

        assertTrue(detailOpt.isPresent());
        Map<String, Object> detail = detailOpt.get();
        assertEquals(1, detail.get("referenceNumber"));
        assertEquals("paper-1", detail.get("paperId"));
        assertEquals("Parsed Paper Title", detail.get("paperTitle"));
        assertEquals("uploaded-paper.pdf", detail.get("originalFilename"));
        assertEquals(3, detail.get("pageNumber"));
        assertEquals(17, detail.get("chunkId"));
        assertEquals("PARAGRAPH", detail.get("elementType"));
        assertEquals("Method", detail.get("sectionTitle"));
        assertEquals(2, detail.get("sectionLevel"));
        assertEquals("{\"x1\":10,\"y1\":20,\"x2\":300,\"y2\":360}", detail.get("bboxJson"));
        assertEquals("OpenDataLoader", detail.get("parserName"));
        assertEquals("2.4.7", detail.get("parserVersion"));
        assertEquals("PDF", detail.get("sourceType"));
        assertEquals("PDF_PENDING_ASSETS", detail.get("evidenceAssetLevel"));
        assertEquals(false, detail.get("pdfEvidenceAvailable"));
        assertEquals(false, detail.containsKey(LEGACY_STRUCTURED_FIELD));
        assertEquals(false, detail.containsKey(LEGACY_EVAL_FIELD));
        assertEquals(false, detail.get("pageScreenshotAvailable"));
        assertEquals(false, detail.get("figureScreenshotAvailable"));
        assertEquals(List.of("pdf_page_visual_evidence_unavailable"), detail.get("assetWarnings"));
    }

    @Test
    void findReferenceDetailResolvesSourceQuoteThroughAuthoritativeTables() {
        ReflectionTestUtils.setField(conversationService, "objectMapper", new ObjectMapper());
        Conversation conversation = new Conversation();
        conversation.setId(10L);
        conversation.setConversationId("conversation-1");
        conversation.setReferenceMappingsJson("""
                {
                  "1": {
                    "sourceQuoteRef": "source_quote_abc"
                  }
                }
                """);
        ConversationSourceQuote registryRow = new ConversationSourceQuote();
        registryRow.setConversationId("conversation-1");
        registryRow.setSourceQuoteRef("source_quote_abc");
        PaperSourceQuote quote = new PaperSourceQuote();
        quote.setSourceQuoteRef("source_quote_abc");
        quote.setPaperId("paper-1");
        quote.setModelVersion("model-v1");
        quote.setLocationRef("page_ref_3");
        quote.setLocationType("PAGE");
        quote.setPageNumber(3);
        quote.setPageEndNumber(3);
        quote.setSectionTitle("Method");
        quote.setContentKind("TEXT");
        quote.setContent("The method uses a two-stage evaluator.");
        Paper paper = new Paper();
        paper.setPaperId("paper-1");
        paper.setPaperTitle("Parsed Paper Title");
        paper.setOriginalFilename("uploaded-paper.pdf");
        when(conversationRepository.findByIdAndUserId(10L, 2L)).thenReturn(Optional.of(conversation));
        when(sourceQuoteResolver.resolveRegisteredCurrentQuote(
                "conversation-1",
                "source_quote_abc"
        )).thenReturn(new ProductReadingSourceQuoteResolver.Resolution(
                ProductReadingSourceQuoteResolver.STATUS_OK,
                Optional.of(quote),
                Optional.of(paper)
        ));

        Optional<Map<String, Object>> detailOpt = conversationService.findReferenceDetail(2L, 10L, 1);

        assertTrue(detailOpt.isPresent());
        Map<String, Object> detail = detailOpt.get();
        assertEquals(1, detail.get("referenceNumber"));
        assertEquals("OK", detail.get("sourceQuoteResolutionStatus"));
        assertEquals("source_quote_abc", detail.get("sourceQuoteRef"));
        assertEquals("paper-1", detail.get("paperId"));
        assertEquals("model-v1", detail.get("paperVersion"));
        assertEquals("Parsed Paper Title", detail.get("paperTitle"));
        assertEquals("uploaded-paper.pdf", detail.get("originalFilename"));
        assertEquals("page_ref_3", detail.get("locationRef"));
        assertEquals("PAGE", detail.get("locationType"));
        assertEquals(3, detail.get("pageNumber"));
        assertEquals("Method", detail.get("sectionTitle"));
        assertEquals("TEXT", detail.get("contentKind"));
        assertEquals("TEXT", detail.get("sourceKind"));
        assertEquals("The method uses a two-stage evaluator.", detail.get("anchorText"));
        assertEquals("The method uses a two-stage evaluator.", detail.get("matchedChunkText"));
        assertEquals("The method uses a two-stage evaluator.", detail.get("evidenceSnippet"));
        assertEquals("PDF_PENDING_ASSETS", detail.get("evidenceAssetLevel"));
        assertEquals(false, detail.get("pdfEvidenceAvailable"));
        assertEquals(List.of("pdf_page_visual_evidence_unavailable"), detail.get("assetWarnings"));
    }

    @Test
    void findReferenceDetailFailsClosedWhenMappedSourceQuoteIsNotRegisteredForConversation() {
        ReflectionTestUtils.setField(conversationService, "objectMapper", new ObjectMapper());
        Conversation conversation = new Conversation();
        conversation.setId(10L);
        conversation.setConversationId("conversation-1");
        conversation.setReferenceMappingsJson("""
                {
                  "1": {
                    "sourceQuoteRef": "source_quote_abc",
                    "paperId": "stale-paper"
                  }
                }
                """);
        when(conversationRepository.findByIdAndUserId(10L, 2L)).thenReturn(Optional.of(conversation));
        when(sourceQuoteResolver.resolveRegisteredCurrentQuote(
                "conversation-1",
                "source_quote_abc"
        )).thenReturn(ProductReadingSourceQuoteResolver.Resolution.status(
                ProductReadingSourceQuoteResolver.STATUS_NOT_IN_CONVERSATION
        ));

        Optional<Map<String, Object>> detailOpt = conversationService.findReferenceDetail(2L, 10L, 1);

        assertTrue(detailOpt.isEmpty());
        verify(sourceQuoteResolver).resolveRegisteredCurrentQuote("conversation-1", "source_quote_abc");
    }

    @Test
    void findReferenceDetailFailsClosedWhenSourceQuoteIsFromOlderReadingModelVersion() {
        ReflectionTestUtils.setField(conversationService, "objectMapper", new ObjectMapper());
        Conversation conversation = new Conversation();
        conversation.setId(10L);
        conversation.setConversationId("conversation-1");
        conversation.setReferenceMappingsJson("""
                {
                  "1": {
                    "sourceQuoteRef": "source_quote_abc"
                  }
                }
                """);
        ConversationSourceQuote registryRow = new ConversationSourceQuote();
        registryRow.setConversationId("conversation-1");
        registryRow.setSourceQuoteRef("source_quote_abc");
        PaperSourceQuote quote = new PaperSourceQuote();
        quote.setSourceQuoteRef("source_quote_abc");
        quote.setPaperId("paper-1");
        quote.setModelVersion("model-v1");
        quote.setLocationRef("page_ref_3");
        quote.setLocationType("PAGE");
        quote.setContentKind("TEXT");
        quote.setContent("Stale quote content.");
        when(conversationRepository.findByIdAndUserId(10L, 2L)).thenReturn(Optional.of(conversation));
        when(sourceQuoteResolver.resolveRegisteredCurrentQuote(
                "conversation-1",
                "source_quote_abc"
        )).thenReturn(ProductReadingSourceQuoteResolver.Resolution.status(
                ProductReadingSourceQuoteResolver.STATUS_UNAVAILABLE
        ));

        Optional<Map<String, Object>> detailOpt = conversationService.findReferenceDetail(2L, 10L, 1);

        assertTrue(detailOpt.isEmpty());
    }

}
