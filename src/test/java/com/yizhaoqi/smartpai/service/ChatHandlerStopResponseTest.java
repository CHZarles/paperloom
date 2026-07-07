package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;

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

    private static ChatHandler handler(ChatGenerationStateService generationStateService,
                                       ChatSessionRegistry sessionRegistry) {
        return new ChatHandler(
                mock(RedisTemplate.class),
                mock(RateLimitService.class),
                mock(ConversationService.class),
                mock(ConversationScopeService.class),
                generationStateService,
                sessionRegistry,
                mock(ProductConversationService.class),
                new ObjectMapper(),
                mock(ThreadPoolTaskExecutor.class)
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
                Map.of()
        );
    }
}
