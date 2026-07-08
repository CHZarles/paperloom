package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.ChatGenerationStateService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;

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
                mock(StringRedisTemplate.class)
        );

        ResponseEntity<?> response = controller.getActiveGeneration("Bearer token", "client-a");

        verify(generationStateService).getActiveGenerationForUserAndClient("1", "client-a");
        verify(generationStateService, never()).getActiveGenerationForUser("1");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(snapshot, body(response).get("data"));
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
                Map.of()
        );
    }
}
