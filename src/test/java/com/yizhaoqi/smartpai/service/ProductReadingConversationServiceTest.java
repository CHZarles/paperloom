package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.config.ProductReadingReactProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductReadingConversationServiceTest {

    @Test
    void legacyProductConversationServiceDoesNotAcceptReadingDependencies() {
        assertNoFieldOrConstructorDependency(ProductConversationService.class, ProductReadingReActHarness.class);
        assertNoFieldOrConstructorDependency(ProductConversationService.class, ProductReadingReactProperties.class);
        assertNoFieldOrConstructorDependency(ProductConversationService.class, ProductReadingConversationService.class);
    }

    @Test
    void readingConversationServiceDoesNotAcceptLegacyDependencies() {
        assertNoFieldOrConstructorDependency(ProductReadingConversationService.class, ProductConversationService.class);
        assertNoFieldOrConstructorDependency(ProductReadingConversationService.class, ProductReActHarness.class);
        assertNoFieldOrConstructorDependency(ProductReadingConversationService.class, ProductToolRegistry.class);
        assertNoFieldOrConstructorDependency(ProductReadingConversationService.class, ConversationService.class);
        assertNoFieldOrConstructorDependency(ProductReadingConversationService.class, ProductMemoryService.class);
    }

    @Test
    void disabledReadingPhaseOneFailsClosedWithoutCallingHarness() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        ProductReadingReactProperties properties = new ProductReadingReactProperties();
        properties.setEnabled(false);
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness, properties);

        ProductTurnResult result = service.runTurn(
                7L,
                "conversation-1",
                "generation-1",
                "推荐 Agentic eval 相关论文",
                SourceScope.auto(),
                ProductModelContext.defaults()
        );

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.ANSWER_SCHEMA_INVALID, result.stopReason());
        assertTrue(result.finalAnswerMarkdown().contains("disabled"));
        verify(readingHarness, never()).run(any());
    }

    @Test
    void enabledReadingPhaseOneCallsOnlyReadingHarnessWithEmptyHistoryAndMemory() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        ProductReadingReactProperties properties = new ProductReadingReactProperties();
        properties.setEnabled(true);
        ProductTurnResult expected = productStateResult("reading answer");
        when(readingHarness.run(any())).thenReturn(expected);
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness, properties);

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
    void enabledReadingPhaseOnePassesOnlyExplicitClickedSourceQuoteAnchors() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        ProductReadingReactProperties properties = new ProductReadingReactProperties();
        properties.setEnabled(true);
        ProductTurnResult expected = productStateResult("reading answer");
        when(readingHarness.run(any())).thenReturn(expected);
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness, properties);

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
    void enabledReadingPhaseOneIgnoresUnsupportedClickedSourceQuoteAnchorShapes() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        ProductReadingReactProperties properties = new ProductReadingReactProperties();
        properties.setEnabled(true);
        when(readingHarness.run(any())).thenReturn(productStateResult("reading answer"));
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness, properties);

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
    void enabledReadingPhaseOneCapsClickedSourceQuoteAnchors() {
        ProductReadingReActHarness readingHarness = mock(ProductReadingReActHarness.class);
        ProductReadingReactProperties properties = new ProductReadingReactProperties();
        properties.setEnabled(true);
        when(readingHarness.run(any())).thenReturn(productStateResult("reading answer"));
        ProductReadingConversationService service = new ProductReadingConversationService(readingHarness, properties);
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

    private void assertNoFieldOrConstructorDependency(Class<?> owner, Class<?> forbiddenType) {
        for (java.lang.reflect.Field field : owner.getDeclaredFields()) {
            assertNotEquals(
                    forbiddenType,
                    field.getType(),
                    owner.getSimpleName() + " field must not use " + forbiddenType.getSimpleName()
            );
        }
        for (java.lang.reflect.Constructor<?> constructor : owner.getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                assertNotEquals(
                        forbiddenType,
                        parameterType,
                        owner.getSimpleName() + " constructor must not use " + forbiddenType.getSimpleName()
                );
            }
        }
    }
}
