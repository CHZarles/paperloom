package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductReadingConversationServiceTest {

    @Test
    void submitRetryTurnBuildsTransportRequestWithRetryContext() {
        ResearchHarnessTransport transport = mock(ResearchHarnessTransport.class);
        ConversationService conversationService = mock(ConversationService.class);
        ProductTurnResult result = completedTurn();
        when(transport.submit(any(ProductTurnRequest.class), any())).thenReturn(CompletableFuture.completedFuture(result));
        when(conversationService.getMessagesBeforeAnswerSlot(7L, "conversation-1", 12L)).thenReturn(List.of(
                Map.of("role", "user", "content", "old question"),
                Map.of("role", "assistant", "content", "old answer")
        ));
        when(conversationService.findLatestReadingStatePatchBeforeAnswerSlot(7L, "conversation-1", 12L))
                .thenReturn(java.util.Optional.empty());
        when(conversationService.findLatestReferenceFocusBeforeAnswerSlot(7L, "conversation-1", 12L))
                .thenReturn(java.util.Optional.empty());
        ProductReadingConversationService service = new ProductReadingConversationService(transport, conversationService);
        Map<String, Object> retry = Map.of(
                "kind", "USER_UNSATISFIED",
                "retry_of_generation_id", "generation-parent",
                "answer_slot_id", 12L,
                "target_revision", 2
        );
        Consumer<Map<String, Object>> progressListener = ignored -> {};

        CompletableFuture<ProductTurnResult> future = service.submitRetryTurn(
                7L,
                "conversation-1",
                "generation-retry",
                "question",
                SourceScope.manual(List.of("paper-1")),
                ProductModelContext.defaults(),
                Map.of("paperIds", List.of("paper-1")),
                retry,
                progressListener
        );

        assertSame(result, future.join());
        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Map<String, Object>>> listenerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(transport).submit(requestCaptor.capture(), listenerCaptor.capture());
        ProductTurnRequest request = requestCaptor.getValue();
        assertEquals("generation-retry", request.generationId());
        assertEquals(List.of("paper-1"), request.lockedScope().paperIds());
        assertEquals(retry, request.retryContext());
        assertEquals("old question", request.history().get(0).get("content"));
        assertSame(progressListener, listenerCaptor.getValue());
        verify(conversationService).findLatestReadingStatePatchBeforeAnswerSlot(7L, "conversation-1", 12L);
        verify(conversationService).findLatestReferenceFocusBeforeAnswerSlot(7L, "conversation-1", 12L);
    }

    private static ProductTurnResult completedTurn() {
        return new ProductTurnResult(
                "ok",
                new AnswerEnvelope(
                        AnswerType.NON_EVIDENCE,
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
                List.of(),
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
    }
}
