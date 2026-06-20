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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ConversationServiceTest {

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
}
