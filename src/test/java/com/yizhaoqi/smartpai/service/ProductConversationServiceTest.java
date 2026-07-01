package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductConversationServiceTest {

    @Test
    void loadsPersistedHistoryAndMemoryBeforeCallingHarness() {
        ConversationService conversationService = mock(ConversationService.class);
        ProductMemoryService memoryService = mock(ProductMemoryService.class);
        ProductReActHarness harness = mock(ProductReActHarness.class);
        when(conversationService.getMessagesByConversationId(7L, "conversation-1"))
                .thenReturn(List.of(
                        Map.of("role", "user", "content", "现在有多少论文可以检索"),
                        Map.of("role", "assistant", "content", "当前可检索论文有 2 篇。", "referenceMappings", Map.of()),
                        Map.of("role", "system", "content", "ignored")
                ));
        when(memoryService.loadMemory(7L, "conversation-1"))
                .thenReturn(Map.of("userGoals", List.of("read current library")));
        when(harness.run(any()))
                .thenReturn(new ProductTurnResult(
                        "ok",
                        new AnswerEnvelope(
                                AnswerType.PRODUCT_STATE,
                                "ok",
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                ""
                        ),
                        List.of(),
                        List.of(),
                        ProductStopReason.COMPLETED,
                        ProductResultStatus.COMPLETED
                ));
        ProductConversationService service =
                new ProductConversationService(conversationService, memoryService, harness);

        service.runTurn(
                7L,
                "conversation-1",
                "generation-1",
                "哪两篇",
                SourceScope.auto(),
                ProductModelContext.defaults()
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(harness).run(requestCaptor.capture());
        ProductTurnRequest request = requestCaptor.getValue();
        assertEquals(7L, request.userId());
        assertEquals("conversation-1", request.conversationId());
        assertEquals("generation-1", request.generationId());
        assertEquals("哪两篇", request.userMessage());
        assertEquals(Map.of("userGoals", List.of("read current library")), request.memory());
        assertEquals(2, request.history().size());
        assertEquals("user", request.history().get(0).get("role"));
        assertEquals("assistant", request.history().get(1).get("role"));
    }

    @Test
    void includesOpaqueReferenceMappingsInAssistantHistoryWithoutRawPaperIds() {
        ConversationService conversationService = mock(ConversationService.class);
        ProductMemoryService memoryService = mock(ProductMemoryService.class);
        ProductReActHarness harness = mock(ProductReActHarness.class);
        when(conversationService.getMessagesByConversationId(7L, "conversation-1"))
                .thenReturn(List.of(
                        Map.of("role", "assistant", "content", "LoRA 使用低秩适配 [1]。", "referenceMappings", Map.of(
                                "1", Map.of(
                                        "citationRef", "citation_generation-1_1",
                                        "evidenceRef", "ev_1",
                                        "paperId", "raw-paper-id",
                                        "paperTitle", "LoRA",
                                        "pageNumber", 3
                                )
                        ))
                ));
        when(memoryService.loadMemory(7L, "conversation-1")).thenReturn(Map.of());
        when(harness.run(any()))
                .thenReturn(new ProductTurnResult(
                        "ok",
                        new AnswerEnvelope(AnswerType.NON_EVIDENCE, "ok", List.of(), List.of(), List.of(), List.of(), List.of(), ""),
                        List.of(),
                        List.of(),
                        ProductStopReason.COMPLETED,
                        ProductResultStatus.COMPLETED
                ));
        ProductConversationService service =
                new ProductConversationService(conversationService, memoryService, harness);

        service.runTurn(7L, "conversation-1", "generation-2", "解释引用 1", SourceScope.auto(), ProductModelContext.defaults());

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(harness).run(requestCaptor.capture());
        String assistantHistory = requestCaptor.getValue().history().get(0).get("content");
        assertTrue(assistantHistory.contains("citationRef=citation_generation-1_1"));
        assertTrue(assistantHistory.contains("evidenceRef=ev_1"));
        assertFalse(assistantHistory.contains("raw-paper-id"));
    }

    @Test
    void trimsOldHistoryWhenModelContextBudgetIsExceeded() {
        ConversationService conversationService = mock(ConversationService.class);
        ProductMemoryService memoryService = mock(ProductMemoryService.class);
        ProductReActHarness harness = mock(ProductReActHarness.class);
        List<Map<String, Object>> longHistory = List.of(
                Map.of("role", "user", "content", "old user message that should be compressed away"),
                Map.of("role", "assistant", "content", "old assistant answer that should be compressed away"),
                Map.of("role", "user", "content", "recent user follow up"),
                Map.of("role", "assistant", "content", "recent assistant answer")
        );
        when(conversationService.getMessagesByConversationId(7L, "conversation-1"))
                .thenReturn(longHistory);
        when(memoryService.loadMemory(7L, "conversation-1"))
                .thenReturn(Map.of("userGoals", List.of("use compressed memory")));
        when(harness.run(any()))
                .thenReturn(new ProductTurnResult(
                        "ok",
                        new AnswerEnvelope(AnswerType.NON_EVIDENCE, "ok", List.of(), List.of(), List.of(), List.of(), List.of(), ""),
                        List.of(),
                        List.of(),
                        ProductStopReason.COMPLETED,
                        ProductResultStatus.COMPLETED
                ));
        ProductConversationService service =
                new ProductConversationService(conversationService, memoryService, harness);

        service.runTurn(
                7L,
                "conversation-1",
                "generation-1",
                "current question",
                SourceScope.auto(),
                new ProductModelContext(6, 1600, 60, 2)
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(harness).run(requestCaptor.capture());
        ProductTurnRequest request = requestCaptor.getValue();
        assertEquals(Map.of("userGoals", List.of("use compressed memory")), request.memory());
        assertEquals(2, request.history().size());
        assertEquals("recent user follow up", request.history().get(0).get("content"));
        assertEquals("recent assistant answer", request.history().get(1).get("content"));
    }

    @Test
    void keepsFullHistoryWhenItFitsCharacterBudgetEvenIfTailLimitIsSmaller() {
        ConversationService conversationService = mock(ConversationService.class);
        ProductMemoryService memoryService = mock(ProductMemoryService.class);
        ProductReActHarness harness = mock(ProductReActHarness.class);
        when(conversationService.getMessagesByConversationId(7L, "conversation-1"))
                .thenReturn(List.of(
                        Map.of("role", "user", "content", "u1"),
                        Map.of("role", "assistant", "content", "a1"),
                        Map.of("role", "user", "content", "u2"),
                        Map.of("role", "assistant", "content", "a2")
                ));
        when(memoryService.loadMemory(7L, "conversation-1")).thenReturn(Map.of());
        when(harness.run(any()))
                .thenReturn(new ProductTurnResult(
                        "ok",
                        new AnswerEnvelope(AnswerType.NON_EVIDENCE, "ok", List.of(), List.of(), List.of(), List.of(), List.of(), ""),
                        List.of(),
                        List.of(),
                        ProductStopReason.COMPLETED,
                        ProductResultStatus.COMPLETED
                ));
        ProductConversationService service =
                new ProductConversationService(conversationService, memoryService, harness);

        service.runTurn(
                7L,
                "conversation-1",
                "generation-1",
                "current question",
                SourceScope.auto(),
                new ProductModelContext(6, 1600, 1000, 2)
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(harness).run(requestCaptor.capture());
        assertEquals(4, requestCaptor.getValue().history().size());
    }

    @Test
    void persistsProductTurnAndReferenceSnapshotBeforeUpdatingMemory() {
        ConversationService conversationService = mock(ConversationService.class);
        ProductMemoryService memoryService = mock(ProductMemoryService.class);
        ProductReActHarness harness = mock(ProductReActHarness.class);
        when(conversationService.getMessagesByConversationId(7L, "conversation-1")).thenReturn(List.of());
        when(memoryService.loadMemory(7L, "conversation-1")).thenReturn(Map.of());
        when(memoryService.updateMemory(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ProductMemoryService.MemoryUpdateResult(true, Map.of(), Map.of(), ""));
        when(harness.run(any()))
                .thenReturn(new ProductTurnResult(
                        "LoRA 使用低秩适配 [1]。",
                        new AnswerEnvelope(AnswerType.EVIDENCE_ANSWER, "LoRA 使用低秩适配 [1]。",
                                List.of(Map.of("claim", "LoRA 使用低秩适配", "evidenceRefs", List.of("ev_1"))),
                                List.of(), List.of(), List.of(), List.of(), ""),
                        List.of(Map.of(
                                "referenceNumber", 1,
                                "citationRef", "citation_generation-1_1",
                                "evidenceRef", "ev_1",
                                "paperTitle", "LoRA",
                                "pageNumber", 3
                        )),
                        List.of(),
                        ProductStopReason.COMPLETED,
                        ProductResultStatus.COMPLETED
                ));
        ProductConversationService service =
                new ProductConversationService(conversationService, memoryService, harness);

        ProductTurnResult result = service.runTurn(
                7L,
                "conversation-1",
                "generation-1",
                "LoRA 的方法是什么",
                SourceScope.auto(),
                ProductModelContext.defaults(),
                Map.of("scopeMode", "AUTO_LIBRARY", "sourceLabel", "All searchable papers")
        );

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        ArgumentCaptor<Map<String, Map<String, Object>>> referencesCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> scopeCaptor = ArgumentCaptor.forClass(Map.class);
        verify(conversationService).recordConversation(
                eq(7L),
                eq("LoRA 的方法是什么"),
                eq("LoRA 使用低秩适配 [1]。"),
                eq("conversation-1"),
                referencesCaptor.capture(),
                scopeCaptor.capture()
        );
        assertEquals("ev_1", referencesCaptor.getValue().get("1").get("evidenceRef"));
        assertEquals("citation_generation-1_1", referencesCaptor.getValue().get("1").get("citationRef"));
        assertEquals("AUTO_LIBRARY", scopeCaptor.getValue().get("scopeMode"));
    }

    @Test
    void persistenceFailureFailsTurnAndDoesNotUpdateMemory() {
        ConversationService conversationService = mock(ConversationService.class);
        ProductMemoryService memoryService = mock(ProductMemoryService.class);
        ProductReActHarness harness = mock(ProductReActHarness.class);
        when(conversationService.getMessagesByConversationId(7L, "conversation-1")).thenReturn(List.of());
        when(memoryService.loadMemory(7L, "conversation-1")).thenReturn(Map.of());
        when(harness.run(any()))
                .thenReturn(new ProductTurnResult(
                        "ok",
                        new AnswerEnvelope(AnswerType.NON_EVIDENCE, "ok", List.of(), List.of(), List.of(), List.of(), List.of(), ""),
                        List.of(),
                        List.of(),
                        ProductStopReason.COMPLETED,
                        ProductResultStatus.COMPLETED
                ));
        doThrow(new RuntimeException("db down")).when(conversationService)
                .recordConversation(any(), any(), any(), any(), any(), any());
        ProductConversationService service =
                new ProductConversationService(conversationService, memoryService, harness);

        ProductTurnResult result = service.runTurn(
                7L,
                "conversation-1",
                "generation-1",
                "hi",
                SourceScope.auto(),
                ProductModelContext.defaults()
        );

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.REFERENCE_PERSISTENCE_FAILED, result.stopReason());
        verify(memoryService, never()).updateMemory(any(), any(), any(), any(), any(), any());
    }
}
