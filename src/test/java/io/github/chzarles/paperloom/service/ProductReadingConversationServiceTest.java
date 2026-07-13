package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    void readingConversationServicePassesExplicitTraceSourceQuoteAction() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        when(readingHarness.run(any())).thenReturn(productStateResult("reading answer"));
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness);

        service.runTurn(
                7L,
                "conversation-1",
                "generation-2",
                "解释这个引用",
                SourceScope.auto(),
                ProductModelContext.defaults(),
                Map.of(
                        "clickedSourceQuoteRefs", List.of("source_quote_abc"),
                        "readingAction", "trace_source_quote"
                )
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(readingHarness).run(requestCaptor.capture());
        assertEquals("TRACE_SOURCE_QUOTE", requestCaptor.getValue().memory().get("readingTurnAction"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readingConversationServicePassesExplicitClickedLocationAnchorForReadAction() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        when(readingHarness.run(any())).thenReturn(productStateResult("reading answer"));
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness);

        service.runTurn(
                7L,
                "conversation-1",
                "generation-2",
                "读取这个位置",
                SourceScope.auto(),
                ProductModelContext.defaults(),
                Map.of(
                        "clickedLocationRefs", List.of(
                                " page_ref_abc ",
                                "page_ref_abc",
                                "not_a_location_ref",
                                "section_ref_methods"
                        ),
                        "readingAction", "read_location"
                )
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(readingHarness).run(requestCaptor.capture());
        Map<String, Object> anchors = (Map<String, Object>) requestCaptor.getValue().memory().get("readingTurnAnchors");
        assertEquals(List.of("page_ref_abc", "section_ref_methods"), anchors.get("clickedLocationRefs"));
        assertEquals("READ_LOCATION", requestCaptor.getValue().memory().get("readingTurnAction"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readingConversationServicePassesLatestReadingStatePatchAsConversationMemory() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        ConversationService conversationService = mock(ConversationService.class);
        when(readingHarness.run(any())).thenReturn(productStateResult("reading answer"));
        when(conversationService.findLatestReadingStatePatch(7L, "conversation-1"))
                .thenReturn(Optional.of(Map.of(
                        "selectedPaper", Map.of(
                                "paperHandle", "paper_handle_abc",
                                "title", "Agentic Eval Benchmark"
                        )
                )));
        ProductReadingConversationService service =
                new ProductReadingConversationService(readingHarness, conversationService);

        service.runTurn(
                7L,
                "conversation-1",
                "generation-2",
                "解释这篇论文",
                SourceScope.auto(),
                ProductModelContext.defaults()
        );

        ArgumentCaptor<ProductTurnRequest> requestCaptor = ArgumentCaptor.forClass(ProductTurnRequest.class);
        verify(readingHarness).run(requestCaptor.capture());
        Map<String, Object> patch = (Map<String, Object>) requestCaptor.getValue().memory().get("readingStatePatch");
        Map<String, Object> selectedPaper = (Map<String, Object>) patch.get("selectedPaper");
        assertEquals("paper_handle_abc", selectedPaper.get("paperHandle"));
        assertEquals("Agentic Eval Benchmark", selectedPaper.get("title"));
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
