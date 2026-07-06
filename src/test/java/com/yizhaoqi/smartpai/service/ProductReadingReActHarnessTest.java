package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductReadingReActHarnessTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readingHarnessHasNoLegacyHarnessOrToolRegistryDependency() {
        assertNoFieldOrConstructorDependency(ProductReadingReActHarness.class, ProductReActHarness.class);
        assertNoFieldOrConstructorDependency(ProductReadingReActHarness.class, ProductToolRegistry.class);
    }

    @Test
    void passesExactlyReadingToolsToLlmAndPromptContainsNoLegacyToolNames() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any()))
                .thenReturn(searchResult());
        when(llm.completeReActTurn(eq("7"), any(), any(), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(finalTurn(productStateEnvelope("找到候选论文：Agentic Eval Benchmark。")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        harness.run(request("推荐 Agentic eval 相关论文"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolRegistry.AgentTool>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).completeReActTurn(eq("7"), messagesCaptor.capture(), toolsCaptor.capture(), anyInt());
        for (List<AgentToolRegistry.AgentTool> capturedTools : toolsCaptor.getAllValues()) {
            List<String> names = capturedTools.stream().map(AgentToolRegistry.AgentTool::name).toList();
            assertEquals(List.of("search_paper_candidates", "find_reading_locations"), names);
            assertFalse(names.contains("find_papers"));
            assertFalse(names.contains("retrieve_evidence"));
            assertFalse(names.contains("inspect_reference"));
            assertFalse(names.contains("inspect_page"));
            assertFalse(names.contains("get_paper_metadata"));
            assertFalse(names.contains("resolve_papers"));
            assertFalse(names.contains("answer_without_product_state"));
        }
        String promptMessages = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(0));
        assertFalse(promptMessages.contains("find_papers"));
        assertFalse(promptMessages.contains("retrieve_evidence"));
        assertFalse(promptMessages.contains("inspect_reference"));
        assertTrue(promptMessages.contains("search_paper_candidates"));
        assertTrue(promptMessages.contains("find_reading_locations"));
    }

    @Test
    void candidateListQuestionCallsSearchToolAndAcceptsProductStateAnswer() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any()))
                .thenReturn(searchResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(finalTurn(productStateEnvelope("找到候选论文：Agentic Eval Benchmark。")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("推荐 Agentic eval 相关论文"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(ProductStopReason.COMPLETED, result.stopReason());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("Agentic Eval Benchmark"));
        assertTrue(result.references().isEmpty());
        verify(registry).execute(eq("search_paper_candidates"), any(), any());
        verify(traceRecorder).recordReadingTurn(any(), any(), any(), any(), any(Instant.class), any(Instant.class));
    }

    @Test
    void navigationQuestionCallsLocationToolAndAcceptsProductStateAnswer() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("find_reading_locations"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "find_reading_locations",
                        true,
                        Map.of(
                                "status", "OK",
                                "candidates", List.of(Map.of(
                                        "locationRef", "loc_page_1",
                                        "paperHandle", "paper_handle_abc",
                                        "preview", "navigation preview"
                                )),
                                "constraints", Map.of(
                                        "previewIsSourceQuote", false,
                                        "paperContentClaimsAllowed", false
                                )
                        ),
                        ProductToolEffect.PAPER_DISCOVERY
                ));
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryText", "methods"
                )))
                .thenReturn(finalTurn(productStateEnvelope("找到候选阅读位置：loc_page_1。")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("在 paper_handle_abc 里找方法部分"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("loc_page_1"));
        assertTrue(result.references().isEmpty());
        verify(registry).execute(eq("find_reading_locations"), any(), any());
    }

    @Test
    void rejectsEvidenceAnswerBecausePhaseOneHasNoSourceQuotes() {
        ProductTurnResult result = runWithFinalEnvelope("""
                {
                  "answerType": "EVIDENCE_ANSWER",
                  "answer": "This paper proves the method works.",
                  "evidenceBasedClaims": [
                    {
                      "claim": "This paper proves the method works.",
                      "evidenceRefs": ["ev_fake"]
                    }
                  ],
                  "stateClaims": [],
                  "limitations": [],
                  "nonEvidenceNotes": [],
                  "missingFields": [],
                  "reason": ""
                }
                """);

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.ANSWER_SCHEMA_INVALID, result.stopReason());
        assertTrue(result.finalAnswerMarkdown().contains("no Source Quotes"));
        assertTrue(result.references().isEmpty());
    }

    @Test
    void rejectsProductStateEnvelopeWithEvidenceClaims() {
        ProductTurnResult result = runWithFinalEnvelope("""
                {
                  "answerType": "PRODUCT_STATE",
                  "answer": "This paper proves the method works.",
                  "evidenceBasedClaims": [
                    {
                      "claim": "This paper proves the method works.",
                      "evidenceRefs": ["ev_fake"]
                    }
                  ],
                  "stateClaims": [],
                  "limitations": [],
                  "nonEvidenceNotes": [],
                  "missingFields": [],
                  "reason": ""
                }
                """);

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.ANSWER_SCHEMA_INVALID, result.stopReason());
        assertTrue(result.finalAnswerMarkdown().contains("no Source Quotes"));
        assertTrue(result.references().isEmpty());
    }

    @Test
    void rejectsCitationMarkersBecausePhaseOneReturnsNoReferences() {
        ProductTurnResult result = runWithFinalEnvelope("""
                {
                  "answerType": "PRODUCT_STATE",
                  "answer": "找到候选论文 [1]。",
                  "evidenceBasedClaims": [],
                  "stateClaims": [],
                  "limitations": [],
                  "nonEvidenceNotes": [],
                  "missingFields": [],
                  "reason": ""
                }
                """);

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.ANSWER_SCHEMA_INVALID, result.stopReason());
        assertTrue(result.references().isEmpty());
    }

    @Test
    void rejectsInternalFieldNamesInFinalAnswer() {
        ProductTurnResult result = runWithFinalEnvelope("""
                {
                  "answerType": "PRODUCT_STATE",
                  "answer": "候选论文的 paperId 是 raw-1。",
                  "evidenceBasedClaims": [],
                  "stateClaims": [],
                  "limitations": [],
                  "nonEvidenceNotes": [],
                  "missingFields": [],
                  "reason": ""
                }
                """);

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.ANSWER_SCHEMA_INVALID, result.stopReason());
        assertTrue(result.references().isEmpty());
    }

    @Test
    void rejectsNonReadingToolSurfaceBeforeCallingLlm() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        when(registry.listTools()).thenReturn(List.of(tool("search_paper_candidates")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("推荐 Agentic eval 相关论文"));

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(traceRecorder).recordReadingTurn(any(), any(), any(), any(), any(Instant.class), any(Instant.class));
    }

    private ProductTurnResult runWithFinalEnvelope(String finalEnvelope) {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any()))
                .thenReturn(searchResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(finalTurn(finalEnvelope));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        return harness.run(request("说明推荐理由"));
    }

    private ProductTurnRequest request(String message) {
        return new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-1",
                message,
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        );
    }

    private ProductToolResult searchResult() {
        return new ProductToolResult(
                "search_paper_candidates",
                true,
                Map.of(
                        "status", "OK",
                        "items", List.of(Map.of(
                                "ordinal", 1,
                                "paperHandle", "paper_handle_abc",
                                "title", "Agentic Eval Benchmark",
                                "preview", "navigation preview"
                        )),
                        "constraints", Map.of(
                                "previewIsSourceQuote", false,
                                "paperContentClaimsAllowed", false
                        )
                ),
                ProductToolEffect.PAPER_DISCOVERY
        );
    }

    private List<AgentToolRegistry.AgentTool> readingTools() {
        return List.of(tool("search_paper_candidates"), tool("find_reading_locations"));
    }

    private AgentToolRegistry.AgentTool tool(String name) {
        return new AgentToolRegistry.AgentTool(
                name,
                "tool",
                Map.of("type", "object", "additionalProperties", false)
        );
    }

    private String productStateEnvelope(String answer) {
        return """
                {
                  "answerType": "PRODUCT_STATE",
                  "answer": "%s",
                  "evidenceBasedClaims": [],
                  "stateClaims": [
                    {
                      "claim": "%s",
                      "sourceTool": "search_paper_candidates"
                    }
                  ],
                  "limitations": ["source-quoted 推荐理由需要 read_locations / Source Quotes。"],
                  "nonEvidenceNotes": [],
                  "missingFields": [],
                  "reason": ""
                }
                """.formatted(answer, answer);
    }

    private LlmProviderRouter.ReActTurn toolCallTurn(String id, String name, Map<String, Object> arguments) {
        return new LlmProviderRouter.ReActTurn(
                "",
                List.of(new LlmProviderRouter.ToolCallDecision(id, name, arguments)),
                Map.of("role", "assistant", "tool_calls", List.of()),
                "tool_calls",
                10,
                5
        );
    }

    private LlmProviderRouter.ReActTurn finalTurn(String content) {
        return new LlmProviderRouter.ReActTurn(
                content,
                List.of(),
                Map.of("role", "assistant", "content", content),
                "stop",
                10,
                5
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
