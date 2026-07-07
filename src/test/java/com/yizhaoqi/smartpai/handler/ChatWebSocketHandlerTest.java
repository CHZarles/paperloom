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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                        {"type":"chat","conversationId":"conversation-1","message":"explain this paper","scope":{}}
                        """)
        );

        ChatHandler.ChatRequest request = capturedRequest(chatHandler, session);
        assertEquals("explain this paper", request.message());
        assertEquals(RetrievalBudgetProfile.INTERACTIVE, request.retrievalBudgetProfile());
    }

    @Test
    void structuredChatPayloadIgnoresScopeRetrievalBudgetProfile() {
        ChatHandler chatHandler = mock(ChatHandler.class);
        ChatWebSocketHandler handler = handler(chatHandler);
        WebSocketSession session = session();

        handler.handleTextMessage(
                session,
                new TextMessage("""
                        {"type":"chat","conversationId":"conversation-1","message":"audit the evidence","scope":{"retrievalBudgetProfile":"deep_audit"}}
                        """)
        );

        ChatHandler.ChatRequest request = capturedRequest(chatHandler, session);
        assertEquals("audit the evidence", request.message());
        assertEquals(RetrievalBudgetProfile.INTERACTIVE, request.retrievalBudgetProfile());
    }

    @Test
    void structuredChatPayloadIgnoresTopLevelRetrievalBudgetWithoutReferenceFocus() {
        ChatHandler chatHandler = mock(ChatHandler.class);
        ChatWebSocketHandler handler = handler(chatHandler);
        WebSocketSession session = session();

        handler.handleTextMessage(
                session,
                new TextMessage("""
                        {"type":"chat","conversationId":"conversation-1","message":"audit the session","retrievalBudgetProfile":"deep_audit","referenceFocus":null}
                        """)
        );

        ChatHandler.ChatRequest request = capturedRequest(chatHandler, session);
        assertEquals("audit the session", request.message());
        assertEquals(RetrievalBudgetProfile.INTERACTIVE, request.retrievalBudgetProfile());
    }

    @Test
    void structuredChatPayloadUsesReferenceFocusInsteadOfLegacyScopeWhenPresent() {
        ChatHandler chatHandler = mock(ChatHandler.class);
        ChatWebSocketHandler handler = handler(chatHandler);
        WebSocketSession session = session();

        handler.handleTextMessage(
                session,
                new TextMessage("""
                        {"type":"chat","conversationId":"conversation-1","message":"explain this citation","referenceFocus":{"referenceNumber":2,"retrievalBudgetProfile":"high_recall"},"scope":{"referenceNumber":9,"retrievalBudgetProfile":"deep_audit"}}
                        """)
        );

        ChatHandler.ChatRequest request = capturedRequest(chatHandler, session);
        assertEquals("explain this citation", request.message());
        assertEquals(2, request.referenceFocus().referenceNumber());
        assertEquals(RetrievalBudgetProfile.INTERACTIVE, request.retrievalBudgetProfile());
    }

    @Test
    void structuredChatPayloadPreservesSourceQuoteReferenceFocus() {
        ChatHandler chatHandler = mock(ChatHandler.class);
        ChatWebSocketHandler handler = handler(chatHandler);
        WebSocketSession session = session();

        handler.handleTextMessage(
                session,
                new TextMessage("""
                        {"type":"chat","conversationId":"conversation-1","message":"解释这个引用","referenceFocus":{"sourceQuoteRef":"source_quote_abc","referenceNumber":1}}
                        """)
        );

        ChatHandler.ChatRequest request = capturedRequest(chatHandler, session);
        assertEquals("解释这个引用", request.message());
        assertEquals("source_quote_abc", request.referenceFocus().sourceQuoteRef());
        assertEquals(1, request.referenceFocus().referenceNumber());
    }

    @Test
    void structuredChatPayloadPreservesConversationId() {
        ChatHandler chatHandler = mock(ChatHandler.class);
        ChatWebSocketHandler handler = handler(chatHandler);
        WebSocketSession session = session();

        handler.handleTextMessage(
                session,
                new TextMessage("""
                        {"type":"user_message","conversationId":"conversation-1","message":"keep this in the visible thread"}
                        """)
        );

        ChatHandler.ChatRequest request = capturedRequest(chatHandler, session);
        assertEquals("keep this in the visible thread", request.message());
        assertEquals("conversation-1", request.conversationId());
    }

    @Test
    void structuredChatPayloadWithoutConversationIdIsRejected() throws Exception {
        ChatHandler chatHandler = mock(ChatHandler.class);
        ChatWebSocketHandler handler = handler(chatHandler);
        WebSocketSession session = session();

        handler.handleTextMessage(
                session,
                new TextMessage("""
                        {"type":"user_message","message":"missing target"}
                        """)
        );

        verify(chatHandler, never()).processMessage(anyString(), any(ChatHandler.ChatRequest.class), eq(session));
        verify(chatHandler, never()).processMessage(anyString(), anyString(), eq(session));
        verify(session).sendMessage(argThat(message ->
                message instanceof TextMessage textMessage
                        && textMessage.getPayload().contains("conversationId")
        ));
    }

    @Test
    void structuredChatPayloadUsesLegacyScopeFieldWhenReferenceFocusIsNull() {
        ChatHandler chatHandler = mock(ChatHandler.class);
        ChatWebSocketHandler handler = handler(chatHandler);
        WebSocketSession session = session();

        handler.handleTextMessage(
                session,
                new TextMessage("""
                        {"type":"chat","conversationId":"conversation-1","message":"legacy audit","referenceFocus":null,"scope":{"retrievalBudgetProfile":"deep_audit"}}
                        """)
        );

        ChatHandler.ChatRequest request = capturedRequest(chatHandler, session);
        assertEquals("legacy audit", request.message());
        assertEquals(RetrievalBudgetProfile.INTERACTIVE, request.retrievalBudgetProfile());
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
