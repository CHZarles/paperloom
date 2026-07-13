package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatSessionRegistryTest {

    private ChatSessionRegistry registry;

    @Mock
    private WebSocketSession session1;

    @Mock
    private WebSocketSession session2;

    @BeforeEach
    void setUp() {
        registry = new ChatSessionRegistry(new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void shouldRegisterMultipleSessionsForSameUser() {
        when(session1.getId()).thenReturn("session-1");
        when(session2.getId()).thenReturn("session-2");
        when(session1.isOpen()).thenReturn(true);
        when(session2.isOpen()).thenReturn(true);

        registry.registerSession("user1", session1);
        registry.registerSession("user1", session2);

        var sessions = registry.getSessions("user1");
        assertEquals(2, sessions.size());
    }

    @Test
    void shouldBroadcastToAllSessions() throws Exception {
        when(session1.getId()).thenReturn("session-1");
        when(session2.getId()).thenReturn("session-2");
        when(session1.isOpen()).thenReturn(true);
        when(session2.isOpen()).thenReturn(true);

        registry.registerSession("user1", session1);
        registry.registerSession("user1", session2);

        registry.sendJsonToUser("user1", Map.of("type", "test", "message", "hello"));

        verify(session1).sendMessage(any());
        verify(session2).sendMessage(any());
    }

    @Test
    void shouldSendOnlyToSessionsForTargetClient() throws Exception {
        when(session1.getId()).thenReturn("session-1");
        when(session2.getId()).thenReturn("session-2");
        when(session1.isOpen()).thenReturn(true);
        when(session1.getAttributes()).thenReturn(new HashMap<>());
        when(session2.getAttributes()).thenReturn(new HashMap<>());

        registry.registerSession("user1", "client-a", session1);
        registry.registerSession("user1", "client-b", session2);

        registry.sendJsonToClient("user1", "client-a", Map.of("type", "chunk", "chunk", "hello"));

        verify(session1).sendMessage(any());
        verify(session2, never()).sendMessage(any());
    }

    @Test
    void shouldUnregisterSpecificSession() {
        when(session1.getId()).thenReturn("session-1");
        when(session2.getId()).thenReturn("session-2");
        when(session2.isOpen()).thenReturn(true);

        registry.registerSession("user1", session1);
        registry.registerSession("user1", session2);

        registry.unregisterSession("user1", session1);

        var sessions = registry.getSessions("user1");
        assertEquals(1, sessions.size());
    }
}
