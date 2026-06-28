package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.controller.dto.ConversationScopeRequests.UpdateConversationScopeRequest;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.ConversationScopeMode;
import com.yizhaoqi.smartpai.model.ConversationScopeStatus;
import com.yizhaoqi.smartpai.service.ConversationScopeService;
import com.yizhaoqi.smartpai.service.ConversationService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationSessionScopeControllerTest {

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private ConversationService conversationService;

    @Mock
    private ConversationScopeService conversationScopeService;

    private ConversationSessionController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new ConversationSessionController();
        ReflectionTestUtils.setField(controller, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(controller, "conversationService", conversationService);
        ReflectionTestUtils.setField(controller, "conversationScopeService", conversationScopeService);
        when(jwtUtils.extractUsernameFromToken("token")).thenReturn("owner");
        when(jwtUtils.extractUserIdFromToken("token")).thenReturn("1");
    }

    @Test
    void getScopeReturns200WithData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scopeMode", "AUTO_LIBRARY");
        data.put("scopeLocked", false);
        data.put("scopeStatus", "READY");
        data.put("sourceLabel", "All searchable papers");
        data.put("sourcePaperCount", null);
        data.put("paperIds", List.of());
        data.put("sourceRecipe", null);
        ConversationScopeService.EffectiveConversationScope scope =
                new ConversationScopeService.EffectiveConversationScope(
                        ConversationScopeMode.AUTO_LIBRARY,
                        ConversationScopeStatus.READY,
                        false,
                        "All searchable papers",
                        List.of(),
                        Map.of()
                );
        when(conversationScopeService.resolveForChat(1L, "conversation-1")).thenReturn(scope);
        when(conversationScopeService.scopeResponse(scope)).thenReturn(data);

        ResponseEntity<?> response = controller.getScope("Bearer token", "conversation-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(200, body(response).get("code"));
        assertEquals(data, body(response).get("data"));
        verify(conversationScopeService).resolveForChat(1L, "conversation-1");
    }

    @Test
    void putScopePassesRequestToServiceAndReturns200() {
        UpdateConversationScopeRequest request = new UpdateConversationScopeRequest(
                "SOURCE_SET_SNAPSHOT",
                "Selected papers",
                null,
                List.of("paper-1"),
                Map.of("kind", "manual")
        );
        Map<String, Object> data = Map.of(
                "scopeMode", "SOURCE_SET_SNAPSHOT",
                "scopeLocked", false,
                "scopeStatus", "READY",
                "sourceLabel", "Selected papers",
                "sourcePaperCount", 1,
                "paperIds", List.of("paper-1")
        );
        when(conversationScopeService.updateUnlockedScope(1L, "conversation-1", request)).thenReturn(data);

        ResponseEntity<?> response = controller.updateScope("Bearer token", "conversation-1", request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(data, body(response).get("data"));
        verify(conversationScopeService).updateUnlockedScope(1L, "conversation-1", request);
    }

    @Test
    void lockedScopeConflictReturns409() {
        UpdateConversationScopeRequest request = new UpdateConversationScopeRequest(
                "SOURCE_SET_SNAPSHOT",
                "Selected papers",
                null,
                List.of("paper-1"),
                null
        );
        when(conversationScopeService.updateUnlockedScope(1L, "conversation-1", request))
                .thenThrow(new CustomException("Conversation scope is locked", HttpStatus.CONFLICT));

        ResponseEntity<?> response = controller.updateScope("Bearer token", "conversation-1", request);

        assertEquals(409, response.getStatusCode().value());
        assertEquals(409, body(response).get("code"));
        assertEquals("Conversation scope is locked", body(response).get("message"));
    }

    @Test
    void invalidTokenReturns401() {
        when(jwtUtils.extractUsernameFromToken("bad-token")).thenReturn(null);

        ResponseEntity<?> response = controller.getScope("Bearer bad-token", "conversation-1");

        assertEquals(401, response.getStatusCode().value());
        assertEquals(401, body(response).get("code"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}
