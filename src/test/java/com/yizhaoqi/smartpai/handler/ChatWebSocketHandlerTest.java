package com.yizhaoqi.smartpai.handler;

import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.ChatSessionRegistry;
import com.yizhaoqi.smartpai.service.RetrievalBudgetProfile;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatWebSocketHandlerTest {

    @Test
    void structuredChatPayloadDefaultsMissingRetrievalBudgetProfileToInteractive() {
        ChatHandler chatHandler = mock(ChatHandler.class);
        ChatWebSocketHandler handler = handler(chatHandler);
        WebSocketSession session = session();

        handler.handleTextMessage(
                session,
                new TextMessage("""
                        {"type":"chat","message":"explain this paper","scope":{}}
                        """)
        );

        ChatHandler.ChatRequest request = capturedRequest(chatHandler, session);
        assertEquals("explain this paper", request.message());
        assertEquals(RetrievalBudgetProfile.INTERACTIVE, request.scope().retrievalBudgetProfile());
    }

    @Test
    void structuredChatPayloadCarriesRetrievalBudgetProfile() {
        ChatHandler chatHandler = mock(ChatHandler.class);
        ChatWebSocketHandler handler = handler(chatHandler);
        WebSocketSession session = session();

        handler.handleTextMessage(
                session,
                new TextMessage("""
                        {"type":"chat","message":"audit the evidence","scope":{"retrievalBudgetProfile":"deep_audit"}}
                        """)
        );

        ChatHandler.ChatRequest request = capturedRequest(chatHandler, session);
        assertEquals("audit the evidence", request.message());
        assertEquals(RetrievalBudgetProfile.DEEP_AUDIT, request.scope().retrievalBudgetProfile());
    }

    private ChatWebSocketHandler handler(ChatHandler chatHandler) {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        when(jwtUtils.extractUserIdFromToken("token")).thenReturn("u1");
        return new ChatWebSocketHandler(chatHandler, jwtUtils, mock(ChatSessionRegistry.class));
    }

    private WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create("ws://127.0.0.1:8081/chat/token"));
        return session;
    }

    private ChatHandler.ChatRequest capturedRequest(ChatHandler chatHandler, WebSocketSession session) {
        ArgumentCaptor<ChatHandler.ChatRequest> request = ArgumentCaptor.forClass(ChatHandler.ChatRequest.class);
        verify(chatHandler).processMessage(eq("u1"), request.capture(), eq(session));
        return request.getValue();
    }
}
