package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHandlerStopResponseTest {

    @Test
    void stopWithoutGenerationIdUsesRequesterClientActiveGeneration() {
        ChatGenerationStateService generationStateService = mock(ChatGenerationStateService.class);
        when(generationStateService.getActiveGenerationForUserAndClient("1", "client-a"))
                .thenReturn(Optional.of(snapshot("generation-a")));
        ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-a");
        when(sessionRegistry.getClientId(session)).thenReturn("client-a");
        ChatHandler handler = handler(generationStateService, sessionRegistry);

        handler.stopResponse("1", null, session);

        verify(generationStateService).markCancelled("generation-a");
        verify(generationStateService, never()).getActiveGenerationForUser("1");
        verify(sessionRegistry).sendJsonToClient(eq("1"), eq("client-a"), argThat(payload ->
                "stop".equals(payload.get("type")) && "generation-a".equals(payload.get("generationId"))
        ));
    }

    @Test
    void stopWithoutGenerationIdDoesNotFallbackToAnotherClientWhenRequesterHasNoActiveGeneration() {
        ChatGenerationStateService generationStateService = mock(ChatGenerationStateService.class);
        when(generationStateService.getActiveGenerationForUserAndClient("1", "client-a"))
                .thenReturn(Optional.empty());
        ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-a");
        when(sessionRegistry.getClientId(session)).thenReturn("client-a");
        ChatHandler handler = handler(generationStateService, sessionRegistry);

        handler.stopResponse("1", null, session);

        verify(generationStateService, never()).getActiveGenerationForUser("1");
        verify(generationStateService, never()).markCancelled(anyString());
        verify(sessionRegistry, never()).sendJsonToClient(anyString(), anyString(), any());
    }

    @Test
    void explicitGenerationIdCannotCancelAnotherUsersGeneration() {
        ChatGenerationStateService generationStateService = mock(ChatGenerationStateService.class);
        when(generationStateService.getGenerationForUser("generation-b", "1"))
                .thenReturn(Optional.empty());
        ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
        ProductReadingConversationService readingService = mock(ProductReadingConversationService.class);
        ChatHandler handler = handler(generationStateService, sessionRegistry, readingService);

        handler.stopResponse("1", "generation-b", null);

        verify(generationStateService, never()).markCancelled(anyString());
        verify(readingService, never()).cancelTurn(anyString());
        verify(sessionRegistry, never()).sendJsonToClient(anyString(), any(), any());
    }

    private static ChatHandler handler(ChatGenerationStateService generationStateService,
                                       ChatSessionRegistry sessionRegistry) {
        return handler(generationStateService, sessionRegistry, mock(ProductReadingConversationService.class));
    }

    private static ChatHandler handler(ChatGenerationStateService generationStateService,
                                       ChatSessionRegistry sessionRegistry,
                                       ProductReadingConversationService readingService) {
        return new ChatHandler(
                mock(UsageQuotaService.class),
                mock(ConversationService.class),
                mock(ConversationScopeService.class),
                generationStateService,
                sessionRegistry,
                readingService,
                mock(ProductPaperHandleService.class),
                new ObjectMapper(),
                3,
                mock(ThreadPoolTaskExecutor.class),
                mock(ScheduledExecutorService.class)
        );
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
