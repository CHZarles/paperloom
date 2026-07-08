package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductReadingConversationServiceTest {

    @Test
    void readingConversationServiceCallsOnlyReadingHarnessWithEmptyHistoryAndMemory() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        ProductTurnResult expected = productStateResult("reading answer");
        when(readingHarness.run(any())).thenReturn(expected);
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness);

        ProductTurnResult result = service.runTurn(
                7L,
                "conversation-1",
                "generation-1",
                "推荐 Agentic eval 相关论文",
                SourceScope.auto(),
                ProductModelContext.defaults()
        );

        assertEquals(expected, result);
        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(readingHarness).run(requestCaptor.capture());
        ProductTurnRequest request = requestCaptor.getValue();
        assertEquals(7L, request.userId());
        assertEquals("conversation-1", request.conversationId());
        assertEquals("generation-1", request.generationId());
        assertEquals("推荐 Agentic eval 相关论文", request.userMessage());
        assertTrue(request.history().isEmpty());
        assertTrue(request.memory().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void readingConversationServicePassesOnlyExplicitClickedSourceQuoteAnchors() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        ProductTurnResult expected = productStateResult("reading answer");
        when(readingHarness.run(any())).thenReturn(expected);
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness);

        service.runTurn(
                7L,
                "conversation-1",
                "generation-2",
                "解释这个来源",
                SourceScope.auto(),
                ProductModelContext.defaults(),
                Map.of(
                        "clickedSourceQuoteRefs", List.of(
                                " source_quote_abc ",
                                "source_quote_abc",
                                "not_a_source_ref",
                                "source_quote_def"
                        ),
                        "memory", Map.of("sourceQuoteRefs", List.of("source_quote_hidden"))
                )
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(readingHarness).run(requestCaptor.capture());
        ProductTurnRequest request = requestCaptor.getValue();
        assertTrue(request.history().isEmpty());
        Map<String, Object> anchors = (Map<String, Object>) request.memory().get("readingTurnAnchors");
        assertEquals(List.of("source_quote_abc", "source_quote_def"), anchors.get("clickedSourceQuoteRefs"));
        assertEquals(null, request.memory().get("memory"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readingConversationServicePassesOnlyExplicitClickedPaperAnchors() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        ProductTurnResult expected = productStateResult("reading answer");
        when(readingHarness.run(any())).thenReturn(expected);
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness);

        service.runTurn(
                7L,
                "conversation-1",
                "generation-2",
                "看这篇论文",
                SourceScope.auto(),
                ProductModelContext.defaults(),
                Map.of(
                        "clickedPaperHandles", List.of(
                                " paper_handle_abc ",
                                "paper_handle_abc",
                                "not_a_paper_handle",
                                "paper_handle_def"
                        ),
                        "memory", Map.of("paperHandles", List.of("paper_handle_hidden"))
                )
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(readingHarness).run(requestCaptor.capture());
        ProductTurnRequest request = requestCaptor.getValue();
        assertTrue(request.history().isEmpty());
        Map<String, Object> anchors = (Map<String, Object>) request.memory().get("readingTurnAnchors");
        assertEquals(List.of("paper_handle_abc", "paper_handle_def"), anchors.get("clickedPaperHandles"));
        assertEquals(null, request.memory().get("memory"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readingConversationServicePassesExplicitReadingAction() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        when(readingHarness.run(any())).thenReturn(productStateResult("reading answer"));
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness);

        service.runTurn(
                7L,
                "conversation-1",
                "generation-2",
                "查找方法相关位置",
                SourceScope.auto(),
                ProductModelContext.defaults(),
                Map.of(
                        "clickedPaperHandles", List.of("paper_handle_abc"),
                        "readingAction", "find_locations"
                )
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(readingHarness).run(requestCaptor.capture());
        ProductTurnRequest request = requestCaptor.getValue();
        Map<String, Object> anchors = (Map<String, Object>) request.memory().get("readingTurnAnchors");
        assertEquals(List.of("paper_handle_abc"), anchors.get("clickedPaperHandles"));
        assertEquals("FIND_LOCATIONS", request.memory().get("readingTurnAction"));
    }

    @Test
    void readingConversationServicePassesExplicitListLocationsAction() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        when(readingHarness.run(any())).thenReturn(productStateResult("reading answer"));
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness);

        service.runTurn(
                7L,
                "conversation-1",
                "generation-2",
                "列出这篇论文可阅读的位置",
                SourceScope.auto(),
                ProductModelContext.defaults(),
                Map.of(
                        "clickedPaperHandles", List.of("paper_handle_abc"),
                        "readingAction", "LIST_LOCATIONS"
                )
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(readingHarness).run(requestCaptor.capture());
        assertEquals("LIST_LOCATIONS", requestCaptor.getValue().memory().get("readingTurnAction"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readingConversationServiceAcceptsClickedPaperAnchorArrays() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        when(readingHarness.run(any())).thenReturn(productStateResult("reading answer"));
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness);

        service.runTurn(
                7L,
                "conversation-1",
                "generation-2",
                "看这些论文",
                SourceScope.auto(),
                ProductModelContext.defaults(),
                Map.of("clickedPaperHandles", new String[]{"paper_handle_array_a", "paper_handle_array_b"})
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(readingHarness).run(requestCaptor.capture());
        Map<String, Object> anchors = (Map<String, Object>) requestCaptor.getValue().memory().get("readingTurnAnchors");
        assertEquals(List.of("paper_handle_array_a", "paper_handle_array_b"), anchors.get("clickedPaperHandles"));
    }

    @Test
    void readingConversationServiceIgnoresUnsupportedClickedPaperAnchorShapes() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        when(readingHarness.run(any())).thenReturn(productStateResult("reading answer"));
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness);

        service.runTurn(
                7L,
                "conversation-1",
                "generation-2",
                "看这篇论文",
                SourceScope.auto(),
                ProductModelContext.defaults(),
                Map.of("clickedPaperHandles", "paper_handle_abc")
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(readingHarness).run(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().memory().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void readingConversationServiceCapsClickedPaperAnchors() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        when(readingHarness.run(any())).thenReturn(productStateResult("reading answer"));
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness);
        List<String> handles = IntStream.range(0, 25)
                .mapToObj(index -> "paper_handle_" + index)
                .toList();

        service.runTurn(
                7L,
                "conversation-1",
                "generation-2",
                "看这些论文",
                SourceScope.auto(),
                ProductModelContext.defaults(),
                Map.of("clickedPaperHandles", handles)
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(readingHarness).run(requestCaptor.capture());
        Map<String, Object> anchors = (Map<String, Object>) requestCaptor.getValue().memory().get("readingTurnAnchors");
        @SuppressWarnings("unchecked")
        List<String> clickedHandles = (List<String>) anchors.get("clickedPaperHandles");
        assertEquals(20, clickedHandles.size());
        assertEquals("paper_handle_0", clickedHandles.get(0));
        assertEquals("paper_handle_19", clickedHandles.get(19));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readingConversationServiceMergesClickedPaperAndSourceQuoteAnchors() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        when(readingHarness.run(any())).thenReturn(productStateResult("reading answer"));
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness);

        service.runTurn(
                7L,
                "conversation-1",
                "generation-2",
                "看这篇论文和这个来源",
                SourceScope.auto(),
                ProductModelContext.defaults(),
                Map.of(
                        "clickedPaperHandles", List.of("paper_handle_abc"),
                        "clickedSourceQuoteRefs", List.of("source_quote_abc")
                )
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(readingHarness).run(requestCaptor.capture());
        Map<String, Object> anchors = (Map<String, Object>) requestCaptor.getValue().memory().get("readingTurnAnchors");
        assertEquals(List.of("paper_handle_abc"), anchors.get("clickedPaperHandles"));
        assertEquals(List.of("source_quote_abc"), anchors.get("clickedSourceQuoteRefs"));
    }

    @Test
    void readingConversationServiceIgnoresUnsupportedClickedSourceQuoteAnchorShapes() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        when(readingHarness.run(any())).thenReturn(productStateResult("reading answer"));
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness);

        service.runTurn(
                7L,
                "conversation-1",
                "generation-2",
                "解释这个来源",
                SourceScope.auto(),
                ProductModelContext.defaults(),
                Map.of("clickedSourceQuoteRefs", "source_quote_abc")
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(readingHarness).run(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().memory().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void readingConversationServiceCapsClickedSourceQuoteAnchors() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        when(readingHarness.run(any())).thenReturn(productStateResult("reading answer"));
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness);
        List<String> refs = IntStream.range(0, 25)
                .mapToObj(index -> "source_quote_" + index)
                .toList();

        service.runTurn(
                7L,
                "conversation-1",
                "generation-2",
                "解释这些来源",
                SourceScope.auto(),
                ProductModelContext.defaults(),
                Map.of("clickedSourceQuoteRefs", refs)
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(readingHarness).run(requestCaptor.capture());
        Map<String, Object> anchors = (Map<String, Object>) requestCaptor.getValue().memory().get("readingTurnAnchors");
        @SuppressWarnings("unchecked")
        List<String> clickedRefs = (List<String>) anchors.get("clickedSourceQuoteRefs");
        assertEquals(20, clickedRefs.size());
        assertEquals("source_quote_0", clickedRefs.get(0));
        assertEquals("source_quote_19", clickedRefs.get(19));
    }

    private ProductTurnResult productStateResult(String answer) {
        AnswerEnvelope envelope = new AnswerEnvelope(
                AnswerType.PRODUCT_STATE,
                answer,
                List.of(),
                List.of(Map.of("claim", answer, "sourceTool", "search_paper_candidates")),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
        return new ProductTurnResult(
                answer,
                envelope,
                List.of(),
                List.of(),
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
    }
}
