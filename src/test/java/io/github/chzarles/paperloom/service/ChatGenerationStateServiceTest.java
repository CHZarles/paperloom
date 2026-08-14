package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatGenerationStateServiceTest {

    @Test
    void createGenerationStoresClientScopedActiveGeneration() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq("chat:user:1:conversation:conversation-1:active_generation"),
                any(String.class),
                any(Duration.class)
        )).thenReturn(true);
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
    void createGenerationRejectsASecondActiveTurnInTheSameConversation() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq("chat:user:1:conversation:conversation-1:active_generation"),
                any(String.class),
                any(Duration.class)
        )).thenReturn(true, false);
        ChatGenerationStateService service = new ChatGenerationStateService(redisTemplate, new ObjectMapper());

        service.createGeneration("1", "client-a", "conversation-1", "first question");

        assertThrows(
                ChatGenerationStateService.GenerationInProgressException.class,
                () -> service.createGeneration("1", "client-b", "conversation-1", "second question")
        );
    }

    @Test
    void createGenerationAllowsDifferentConversationsToRunInParallel() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(String.class), any(String.class), any(Duration.class))).thenReturn(true);
        ChatGenerationStateService service = new ChatGenerationStateService(redisTemplate, new ObjectMapper());

        var first = service.createGeneration("1", "client-a", "conversation-1", "first question");
        var second = service.createGeneration("1", "client-b", "conversation-2", "second question");

        assertNotEquals(first.generationId(), second.generationId());
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
        ChatGenerationStateService service = new ChatGenerationStateService(redisTemplate, new ObjectMapper());

        service.markCancelled("generation-1");

        verify(redisTemplate).execute(
                eq(ChatGenerationStateService.COMPARE_AND_DELETE_SCRIPT),
                eq(List.of("chat:user:1:active_generation")),
                eq("generation-1")
        );
        verify(redisTemplate).execute(
                eq(ChatGenerationStateService.COMPARE_AND_DELETE_SCRIPT),
                eq(List.of("chat:user:1:conversation:conversation-1:active_generation")),
                eq("generation-1")
        );
        verify(redisTemplate).execute(
                eq(ChatGenerationStateService.COMPARE_AND_DELETE_SCRIPT),
                eq(List.of("chat:user:1:client:client-a:active_generation")),
                eq("generation-1")
        );
        verify(redisTemplate, never()).delete("chat:user:1:conversation:conversation-1:active_generation");
    }

    @Test
    void renewConversationLeaseOnlyExtendsTheCurrentOwnersLease() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("chat:generation:generation-1:meta")).thenReturn(generationMetaJson());
        when(redisTemplate.execute(
                eq(ChatGenerationStateService.COMPARE_AND_EXPIRE_SCRIPT),
                eq(List.of("chat:user:1:conversation:conversation-1:active_generation")),
                eq("generation-1"),
                eq("1800000")
        )).thenReturn(1L);
        ChatGenerationStateService service = new ChatGenerationStateService(redisTemplate, new ObjectMapper());

        assertTrue(service.renewConversationLease("generation-1"));

        verify(redisTemplate).execute(
                eq(ChatGenerationStateService.COMPARE_AND_EXPIRE_SCRIPT),
                eq(List.of("chat:user:1:conversation:conversation-1:active_generation")),
                eq("generation-1"),
                eq("1800000")
        );
    }

    @Test
    void renewConversationLeaseReportsLostOwnership() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("chat:generation:generation-1:meta")).thenReturn(generationMetaJson());
        when(redisTemplate.execute(
                eq(ChatGenerationStateService.COMPARE_AND_EXPIRE_SCRIPT),
                eq(List.of("chat:user:1:conversation:conversation-1:active_generation")),
                eq("generation-1"),
                eq("1800000")
        )).thenReturn(0L);
        ChatGenerationStateService service = new ChatGenerationStateService(redisTemplate, new ObjectMapper());

        assertFalse(service.renewConversationLease("generation-1"));
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

    @Test
    void generationSnapshotProjectsResearchAuditTrailFromReferencesAndProgressEvents() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
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
                  "clientId": "client-a"
                }
                """);
        when(valueOperations.get("chat:generation:generation-1:content")).thenReturn("answer [1]");
        when(valueOperations.get("chat:generation:generation-1:refs")).thenReturn("""
                {
                  "1": {
                    "paperId": "paper-1",
                    "paperTitle": "Paper One",
                    "evidenceRef": "ev_1",
                    "citationRef": "[1]",
                    "matchedChunkText": "Quoted evidence.",
                    "pageScreenshotAvailable": true
                  }
                }
                """);
        when(listOperations.range("chat:generation:generation-1:progress", 0, -1)).thenReturn(List.of("""
                {
                  "eventType": "tool_completed",
                  "tool": "read_locations",
                  "status": "completed",
                  "output": {
                    "evidence": [
                      {
                        "evidenceId": "ev_1",
                        "paperId": "paper-1",
                        "title": "Paper One",
                        "locationRef": "location_ref_1",
                        "quote": "Quoted evidence.",
                        "pageScreenshotAvailable": true
                      }
                    ]
                  }
                }
                """));
        ChatGenerationStateService service = new ChatGenerationStateService(redisTemplate, new ObjectMapper());

        var snapshot = service.getGeneration("generation-1").orElseThrow();

        assertTrue(snapshot.researchAuditTrail().hasContent());
        assertEquals(1, snapshot.researchAuditTrail().diagnostics().citedEvidenceCount());
        assertEquals("cited", snapshot.researchAuditTrail().evidence().get(0).status());
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
