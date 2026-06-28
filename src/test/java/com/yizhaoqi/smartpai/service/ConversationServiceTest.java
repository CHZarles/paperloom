package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.Conversation;
import com.yizhaoqi.smartpai.model.ConversationSession;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.ConversationRepository;
import com.yizhaoqi.smartpai.repository.ConversationSessionRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
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
        assertEquals(List.of(), detail.get("assetWarnings"));
    }
}
