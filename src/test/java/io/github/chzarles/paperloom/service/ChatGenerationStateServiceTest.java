package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatGenerationStateServiceTest {

    @Test
    void createGenerationStoresClientScopedActiveGeneration() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ChatGenerationStateService service = new ChatGenerationStateService(redisTemplate, new ObjectMapper());

        ChatGenerationStateService.GenerationSnapshot snapshot =
                service.createGeneration("1", "client-a", "conversation-1", "question");

        verify(valueOperations).set(
                eq("chat:user:1:client:client-a:active_generation"),
                eq(snapshot.generationId()),
                any(Duration.class)
        );
        verify(valueOperations).set(
                eq("chat:user:1:active_generation"),
                eq(snapshot.generationId()),
                any(Duration.class)
        );
    }

    @Test
    void getActiveGenerationForUserAndClientReadsClientScopedActiveGeneration() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("chat:user:1:client:client-a:active_generation")).thenReturn("generation-1");
        when(valueOperations.get("chat:generation:generation-1:meta")).thenReturn(generationMetaJson());
        when(valueOperations.get("chat:generation:generation-1:content")).thenReturn("partial answer");
        ChatGenerationStateService service = new ChatGenerationStateService(redisTemplate, new ObjectMapper());

        var snapshot = service.getActiveGenerationForUserAndClient("1", "client-a");

        assertTrue(snapshot.isPresent());
        assertEquals("generation-1", snapshot.get().generationId());
        assertEquals("conversation-1", snapshot.get().conversationId());
        assertEquals("partial answer", snapshot.get().content());
    }

    @Test
    void terminalStateClearsMatchingClientScopedActiveGeneration() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("chat:generation:generation-1:meta")).thenReturn(generationMetaJson());
        when(valueOperations.get("chat:user:1:active_generation")).thenReturn("generation-1");
        when(valueOperations.get("chat:user:1:client:client-a:active_generation")).thenReturn("generation-1");
        ChatGenerationStateService service = new ChatGenerationStateService(redisTemplate, new ObjectMapper());

        service.markCancelled("generation-1");

        verify(redisTemplate).delete("chat:user:1:active_generation");
        verify(redisTemplate).delete("chat:user:1:client:client-a:active_generation");
    }

    @Test
    void generationSnapshotCarriesReadingArtifactsStatePatchAndConversationRecordId() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("chat:generation:generation-1:meta")).thenReturn("""
                {
                  "generationId": "generation-1",
                  "userId": "1",
                  "conversationId": "conversation-1",
                  "question": "question",
                  "status": "COMPLETED",
                  "createdAt": "2026-07-07T12:00:00",
                  "updatedAt": "2026-07-07T12:00:05",
                  "errorMessage": null,
                  "clientId": "client-a",
                  "conversationRecordId": 9001
                }
                """);
        when(valueOperations.get("chat:generation:generation-1:content")).thenReturn("answer");
        when(valueOperations.get("chat:generation:generation-1:reading_artifacts")).thenReturn("""
                {"goalCard":{"interpretedGoal":"read agent papers"}}
                """);
        when(valueOperations.get("chat:generation:generation-1:reading_state_patch")).thenReturn("""
                {"selectedPaper":{"paperHandle":"paper_handle_abc"}}
                """);
        ChatGenerationStateService service = new ChatGenerationStateService(redisTemplate, new ObjectMapper());

        var snapshot = service.getGeneration("generation-1").orElseThrow();

        assertEquals(9001L, snapshot.conversationRecordId());
        assertEquals("read agent papers", ((java.util.Map<?, ?>) snapshot.readingArtifacts().get("goalCard"))
                .get("interpretedGoal"));
        assertEquals("paper_handle_abc", ((java.util.Map<?, ?>) snapshot.readingStatePatch().get("selectedPaper"))
                .get("paperHandle"));
    }

    private static String generationMetaJson() {
        return """
                {
                  "generationId": "generation-1",
                  "userId": "1",
                  "conversationId": "conversation-1",
                  "question": "question",
                  "status": "STREAMING",
                  "createdAt": "2026-07-07T12:00:00",
                  "updatedAt": "2026-07-07T12:00:00",
                  "errorMessage": null,
                  "clientId": "client-a"
                }
                """;
    }
}
