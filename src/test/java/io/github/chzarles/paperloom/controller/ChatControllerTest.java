package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.service.ChatGenerationStateService;
import io.github.chzarles.paperloom.service.ChatHandler;
import io.github.chzarles.paperloom.service.ConversationService;
import io.github.chzarles.paperloom.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    @Test
    void activeGenerationUsesClientScopedLookupWhenClientIdIsSupplied() {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        when(jwtUtils.validateToken("token")).thenReturn(true);
        when(jwtUtils.extractUserIdFromToken("token")).thenReturn("1");
        ChatGenerationStateService generationStateService = mock(ChatGenerationStateService.class);
        ChatGenerationStateService.GenerationSnapshot snapshot = snapshot("generation-1");
        when(generationStateService.getActiveGenerationForUserAndClient("1", "client-a"))
                .thenReturn(Optional.of(snapshot));
        ChatController controller = new ChatController(
                jwtUtils,
                generationStateService,
                mock(StringRedisTemplate.class),
                mock(ChatHandler.class),
                mock(ConversationService.class)
        );

        ResponseEntity<?> response = controller.getActiveGeneration("Bearer token", "client-a");

        verify(generationStateService).getActiveGenerationForUserAndClient("1", "client-a");
        verify(generationStateService, never()).getActiveGenerationForUser("1");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(snapshot, body(response).get("data"));
    }

    @Test
    void retryGenerationDelegatesToChatHandlerWithClientId() {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        when(jwtUtils.validateToken("token")).thenReturn(true);
        when(jwtUtils.extractUserIdFromToken("token")).thenReturn("1");
        ChatHandler chatHandler = mock(ChatHandler.class);
        Map<String, Object> started = Map.of(
                "generationId", "generation-2",
                "retryOfGenerationId", "generation-1",
                "answerSlotId", 12L,
                "answerRevision", 2,
                "replaceMessage", true
        );
        when(chatHandler.retryGeneration("1", "generation-1", "client-a", "user_unsatisfied"))
                .thenReturn(started);
        ChatController controller = new ChatController(
                jwtUtils,
                mock(ChatGenerationStateService.class),
                mock(StringRedisTemplate.class),
                chatHandler,
                mock(ConversationService.class)
        );

        ResponseEntity<?> response = controller.retryGeneration(
                "generation-1",
                "Bearer token",
                new ChatController.RetryGenerationRequest("user_unsatisfied", "client-a")
        );

        verify(chatHandler).retryGeneration("1", "generation-1", "client-a", "user_unsatisfied");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(started, body(response).get("data"));
    }

    @Test
    void answerRevisionsAreScopedToAuthenticatedUser() {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        when(jwtUtils.validateToken("token")).thenReturn(true);
        when(jwtUtils.extractUserIdFromToken("token")).thenReturn("1");
        ConversationService conversationService = mock(ConversationService.class);
        List<Map<String, Object>> revisions = List.of(Map.of(
                "conversationRecordId", 12L,
                "answerSlotId", 12L,
                "answerRevision", 1
        ));
        when(conversationService.getAnswerRevisions(1L, 12L)).thenReturn(revisions);
        ChatController controller = new ChatController(
                jwtUtils,
                mock(ChatGenerationStateService.class),
                mock(StringRedisTemplate.class),
                mock(ChatHandler.class),
                conversationService
        );

        ResponseEntity<?> response = controller.getAnswerRevisions(12L, "Bearer token");

        verify(conversationService).getAnswerRevisions(1L, 12L);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(revisions, body(response).get("data"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    private static ChatGenerationStateService.GenerationSnapshot snapshot(String generationId) {
        return new ChatGenerationStateService.GenerationSnapshot(
                generationId,
                "1",
                "conversation-1",
                "question",
                ChatGenerationStateService.GenerationStatus.STREAMING,
                "",
                "2026-07-07T12:00:00",
                "2026-07-07T12:00:00",
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null
        );
    }
}
