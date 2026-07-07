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
import static org.mockito.Mockito.never;
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
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
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
            assertEquals(List.of(
                    "search_paper_candidates",
                    "get_paper_outline",
                    "list_paper_locations",
                    "find_reading_locations",
                    "read_locations",
                    "trace_source_quotes"
            ), names);
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
        assertTrue(promptMessages.contains("get_paper_outline"));
        assertTrue(promptMessages.contains("list_paper_locations"));
        assertTrue(promptMessages.contains("find_reading_locations"));
        assertTrue(promptMessages.contains("read_locations"));
        assertTrue(promptMessages.contains("trace_source_quotes"));
        assertTrue(promptMessages.contains("parserQuality"));
        assertTrue(promptMessages.contains("deterministic"));
        assertTrue(promptMessages.contains("semantic"));
        assertTrue(promptMessages.contains("trace_source_quotes returned locationRef values are metadata"));
    }

    @Test
    void candidateListQuestionCallsSearchToolAndAcceptsProductStateAnswer() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
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
    void navigationQuestionRequiresDisclosedPaperHandleBeforeLocationSearch() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(toolCallTurn("call_2", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryText", "methods"
                )))
                .thenReturn(finalTurn(productStateEnvelope("找到候选阅读位置：page_ref_abc。")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("在候选论文里找方法部分"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("page_ref_abc"));
        assertTrue(result.references().isEmpty());
        verify(registry).execute(eq("search_paper_candidates"), any(), any());
        verify(registry).execute(eq("find_reading_locations"), any(), any());
    }

    @Test
    void rejectsHiddenPaperHandleBeforeLocationSearch() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_hidden"),
                        "queryText", "methods"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("在 paper_handle_hidden 里找方法"));

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("find_reading_locations"), any(), any());
    }

    @Test
    void sourceQuotedQuestionReadsLocationsAndRendersSourceQuoteReferences() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationResult());
        when(registry.execute(eq("read_locations"), any(), any())).thenReturn(readResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(toolCallTurn("call_2", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryText", "score"
                )))
                .thenReturn(toolCallTurn("call_3", "read_locations", Map.of(
                        "locationRefs", List.of("page_ref_abc")
                )))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "该论文报告的 score 有提升 {{sourceQuoteRef:source_quote_abc}}。",
                          "evidenceBasedClaims": [
                            {
                              "claim": "该论文报告的 score 有提升。",
                              "sourceQuoteRefs": ["source_quote_abc"]
                            }
                          ],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("这篇论文的 score 说明了什么"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.EVIDENCE_ANSWER, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("[1]"));
        assertFalse(result.finalAnswerMarkdown().contains("sourceQuoteRef"));
        assertEquals(1, result.references().size());
        assertEquals("source_quote_abc", result.references().get(0).get("sourceQuoteRef"));
        assertEquals(1, result.references().get(0).get("referenceNumber"));
    }

    @Test
    void readLocationsCanUseRefsDisclosedByListPaperLocations() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(registry.execute(eq("list_paper_locations"), any(), any())).thenReturn(listLocationsResult());
        when(registry.execute(eq("read_locations"), any(), any())).thenReturn(readResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(toolCallTurn("call_2", "list_paper_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "pageRange", Map.of("from", 3, "to", 3),
                        "locationTypes", List.of("PAGE")
                )))
                .thenReturn(toolCallTurn("call_3", "read_locations", Map.of(
                        "locationRefs", List.of("page_ref_abc")
                )))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "Page 3 reports a score improvement {{sourceQuoteRef:source_quote_abc}}.",
                          "evidenceBasedClaims": [
                            {
                              "claim": "Page 3 reports a score improvement.",
                              "sourceQuoteRefs": ["source_quote_abc"]
                            }
                          ],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("Read page 3"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        verify(registry).execute(eq("list_paper_locations"), any(), any());
        verify(registry).execute(eq("read_locations"), any(), any());
    }

    @Test
    void outlineReturnedSectionRefsCanBeRead() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(registry.execute(eq("get_paper_outline"), any(), any())).thenReturn(outlineResult());
        when(registry.execute(eq("read_locations"), any(), any())).thenReturn(sectionReadResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(toolCallTurn("call_2", "get_paper_outline", Map.of(
                        "paperHandles", List.of("paper_handle_abc")
                )))
                .thenReturn(toolCallTurn("call_3", "read_locations", Map.of(
                        "locationRefs", List.of("section_ref_methods")
                )))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "The Methods section describes the evaluation setup {{sourceQuoteRef:source_quote_abc}}.",
                          "evidenceBasedClaims": [
                            {
                              "claim": "The Methods section describes the evaluation setup.",
                              "sourceQuoteRefs": ["source_quote_abc"]
                            }
                          ],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("Read the Methods section"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        verify(registry).execute(eq("get_paper_outline"), any(), any());
        verify(registry).execute(eq("read_locations"), any(), any());
    }

    @Test
    void rejectsHiddenPaperHandleBeforeOutline() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "get_paper_outline", Map.of(
                        "paperHandles", List.of("paper_handle_hidden")
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("Outline paper_handle_hidden"));

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("get_paper_outline"), any(), any());
    }

    @Test
    void rejectsHiddenLocationRefBeforeReadLocations() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(toolCallTurn("call_2", "read_locations", Map.of(
                        "locationRefs", List.of("page_ref_hidden")
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("阅读隐藏位置"));

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("read_locations"), any(), any());
    }

    @Test
    void followUpQuestionTracesClickedSourceQuoteAndRendersReferences() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("trace_source_quotes"), any(), any())).thenReturn(traceResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "trace_source_quotes", Map.of(
                        "sourceQuoteRefs", List.of("source_quote_abc")
                )))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "这个来源说明 traced score 有提升 {{sourceQuoteRef:source_quote_abc}}。",
                          "evidenceBasedClaims": [
                            {
                              "claim": "这个来源说明 traced score 有提升。",
                              "sourceQuoteRefs": ["source_quote_abc"]
                            }
                          ],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedRefs(
                "解释这个来源",
                List.of("source_quote_abc")
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.EVIDENCE_ANSWER, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("[1]"));
        assertEquals("source_quote_abc", result.references().get(0).get("sourceQuoteRef"));
        verify(registry).execute(eq("trace_source_quotes"), any(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).completeReActTurn(eq("7"), messagesCaptor.capture(), eq(tools), anyInt());
        String firstPrompt = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(0));
        assertTrue(firstPrompt.contains("source_quote_abc"));
        assertFalse(firstPrompt.contains("Traced source quote content"));
    }

    @Test
    void rejectsHiddenSourceQuoteRefBeforeTraceSourceQuotes() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "trace_source_quotes", Map.of(
                        "sourceQuoteRefs", List.of("source_quote_hidden")
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedRefs(
                "解释这个来源",
                List.of("source_quote_abc")
        ));

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("trace_source_quotes"), any(), any());
    }

    @Test
    void rejectsClickedSourceQuoteAsFinalSupportBeforeTraceReturnsIt() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "This clicked source says the score improves {{sourceQuoteRef:source_quote_abc}}.",
                          "evidenceBasedClaims": [
                            {
                              "claim": "This clicked source says the score improves.",
                              "sourceQuoteRefs": ["source_quote_abc"]
                            }
                          ],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedRefs(
                "解释这个来源",
                List.of("source_quote_abc")
        ));

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.CITATION_VALIDATION_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("trace_source_quotes"), any(), any());
    }

    @Test
    void traceOutputDoesNotDiscloseLocationRefsForReadLocations() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("trace_source_quotes"), any(), any())).thenReturn(traceResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "trace_source_quotes", Map.of(
                        "sourceQuoteRefs", List.of("source_quote_abc")
                )))
                .thenReturn(toolCallTurn("call_2", "read_locations", Map.of(
                        "locationRefs", List.of("page_ref_old")
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedRefs(
                "继续读这个来源周边",
                List.of("source_quote_abc")
        ));

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("read_locations"), any(), any());
    }

    @Test
    void tracePaperHandleCanBeUsedForListButTraceLocationRefStillCannotBeReadDirectly() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("trace_source_quotes"), any(), any())).thenReturn(traceResult());
        when(registry.execute(eq("list_paper_locations"), any(), any())).thenReturn(listLocationsResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "trace_source_quotes", Map.of(
                        "sourceQuoteRefs", List.of("source_quote_abc")
                )))
                .thenReturn(toolCallTurn("call_2", "list_paper_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "pageRange", Map.of("from", 3, "to", 3),
                        "locationTypes", List.of("PAGE")
                )))
                .thenReturn(toolCallTurn("call_3", "read_locations", Map.of(
                        "locationRefs", List.of("page_ref_old")
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedRefs(
                "Continue around this source",
                List.of("source_quote_abc")
        ));

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry).execute(eq("list_paper_locations"), any(), any());
        verify(registry, never()).execute(eq("read_locations"), any(), any());
    }

    @Test
    void tracePaperHandleDoesNotAuthorizeFindReadingLocations() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("trace_source_quotes"), any(), any())).thenReturn(traceResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "trace_source_quotes", Map.of(
                        "sourceQuoteRefs", List.of("source_quote_abc")
                )))
                .thenReturn(toolCallTurn("call_2", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryText", "nearby context"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedRefs(
                "Search around this source",
                List.of("source_quote_abc")
        ));

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("find_reading_locations"), any(), any());
    }

    @Test
    void tracePaperHandleCanBeUsedForOutline() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("trace_source_quotes"), any(), any())).thenReturn(traceResult());
        when(registry.execute(eq("get_paper_outline"), any(), any())).thenReturn(outlineResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "trace_source_quotes", Map.of(
                        "sourceQuoteRefs", List.of("source_quote_abc")
                )))
                .thenReturn(toolCallTurn("call_2", "get_paper_outline", Map.of(
                        "paperHandles", List.of("paper_handle_abc")
                )))
                .thenReturn(finalTurn(productStateEnvelope("Methods section is available as navigation.")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedRefs(
                "Show this paper outline",
                List.of("source_quote_abc")
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        verify(registry).execute(eq("get_paper_outline"), any(), any());
        verify(registry, never()).execute(eq("find_reading_locations"), any(), any());
    }

    @Test
    void rejectsEvidenceAnswerWithoutReadLocationsSupport() {
        ProductTurnResult result = runAfterSearchWithFinalEnvelope("""
                {
                  "answerType": "EVIDENCE_ANSWER",
                  "answer": "This paper proves the method works {{sourceQuoteRef:source_quote_fake}}.",
                  "evidenceBasedClaims": [
                    {
                      "claim": "This paper proves the method works.",
                      "sourceQuoteRefs": ["source_quote_fake"]
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
        assertEquals(ProductStopReason.CITATION_VALIDATION_FAILED, result.stopReason());
        assertTrue(result.references().isEmpty());
    }

    @Test
    void rejectsEvidenceAnswerWithMarkerButNoClaimSupport() {
        ProductTurnResult result = runAfterReadWithFinalEnvelope("""
                {
                  "answerType": "EVIDENCE_ANSWER",
                  "answer": "This paper reports a score improvement {{sourceQuoteRef:source_quote_abc}}.",
                  "evidenceBasedClaims": [],
                  "stateClaims": [],
                  "limitations": [],
                  "nonEvidenceNotes": [],
                  "missingFields": [],
                  "reason": ""
                }
                """);

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.CITATION_VALIDATION_FAILED, result.stopReason());
        assertTrue(result.references().isEmpty());
    }

    @Test
    void rejectsEvidenceAnswerWithClaimSupportButNoVisibleMarker() {
        ProductTurnResult result = runAfterReadWithFinalEnvelope("""
                {
                  "answerType": "EVIDENCE_ANSWER",
                  "answer": "This paper reports a score improvement.",
                  "evidenceBasedClaims": [
                    {
                      "claim": "This paper reports a score improvement.",
                      "sourceQuoteRefs": ["source_quote_abc"]
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
        assertEquals(ProductStopReason.CITATION_VALIDATION_FAILED, result.stopReason());
        assertTrue(result.references().isEmpty());
    }

    @Test
    void rejectsLegacyEvidenceRefsForSourceQuoteAnswers() {
        ProductTurnResult result = runAfterReadWithFinalEnvelope("""
                {
                  "answerType": "EVIDENCE_ANSWER",
                  "answer": "This paper reports a score improvement {{sourceQuoteRef:source_quote_abc}}.",
                  "evidenceBasedClaims": [
                    {
                      "claim": "This paper reports a score improvement.",
                      "evidenceRefs": ["source_quote_abc"]
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
        assertEquals(ProductStopReason.CITATION_VALIDATION_FAILED, result.stopReason());
    }

    @Test
    void rejectsLocationPreviewRefsAsSourceQuoteSupport() {
        ProductTurnResult result = runAfterReadWithFinalEnvelope("""
                {
                  "answerType": "EVIDENCE_ANSWER",
                  "answer": "This paper reports a score improvement {{sourceQuoteRef:source_quote_abc}}.",
                  "evidenceBasedClaims": [
                    {
                      "claim": "This paper reports a score improvement.",
                      "sourceQuoteRefs": ["source_quote_abc"],
                      "locationRefs": ["page_ref_abc"]
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
        assertEquals(ProductStopReason.CITATION_VALIDATION_FAILED, result.stopReason());
    }

    @Test
    void rejectsNumberedCitationsAndInternalFieldNames() {
        ProductTurnResult numbered = runAfterReadWithFinalEnvelope("""
                {
                  "answerType": "EVIDENCE_ANSWER",
                  "answer": "This paper reports a score improvement [1] {{sourceQuoteRef:source_quote_abc}}.",
                  "evidenceBasedClaims": [
                    {
                      "claim": "This paper reports a score improvement.",
                      "sourceQuoteRefs": ["source_quote_abc"]
                    }
                  ],
                  "stateClaims": [],
                  "limitations": [],
                  "nonEvidenceNotes": [],
                  "missingFields": [],
                  "reason": ""
                }
                """);
        ProductTurnResult internal = runAfterSearchWithFinalEnvelope("""
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

        assertEquals(ProductResultStatus.FAILED, numbered.resultStatus());
        assertEquals(ProductResultStatus.FAILED, internal.resultStatus());
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

    private ProductTurnResult runAfterSearchWithFinalEnvelope(String finalEnvelope) {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(finalTurn(finalEnvelope));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        return harness.run(request("说明推荐理由"));
    }

    private ProductTurnResult runAfterReadWithFinalEnvelope(String finalEnvelope) {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationResult());
        when(registry.execute(eq("read_locations"), any(), any())).thenReturn(readResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(toolCallTurn("call_2", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryText", "score"
                )))
                .thenReturn(toolCallTurn("call_3", "read_locations", Map.of("locationRefs", List.of("page_ref_abc"))))
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

    private ProductTurnRequest requestWithClickedRefs(String message, List<String> clickedRefs) {
        return new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-2",
                message,
                SourceScope.auto(),
                List.of(),
                Map.of("readingTurnAnchors", Map.of("clickedSourceQuoteRefs", clickedRefs)),
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

    private ProductToolResult locationResult() {
        return new ProductToolResult(
                "find_reading_locations",
                true,
                Map.of(
                        "status", "OK",
                        "candidates", List.of(Map.of(
                                "locationRef", "page_ref_abc",
                                "paperHandle", "paper_handle_abc",
                                "preview", "navigation preview"
                        )),
                        "constraints", Map.of(
                                "previewIsSourceQuote", false,
                                "locationRefIsSourceQuote", false,
                                "paperContentClaimsAllowed", false
                        )
                ),
                ProductToolEffect.PAPER_DISCOVERY
        );
    }

    private ProductToolResult listLocationsResult() {
        return new ProductToolResult(
                "list_paper_locations",
                true,
                Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "status", "OK",
                        "locations", List.of(Map.of(
                                "locationRef", "page_ref_abc",
                                "paperHandle", "paper_handle_abc",
                                "locationType", "PAGE",
                                "pageNumber", 3,
                                "label", "Page 3"
                        )),
                        "constraints", Map.of(
                                "previewIsSourceQuote", false,
                                "locationRefIsSourceQuote", false,
                                "paperContentClaimsAllowed", false
                        )
                ),
                ProductToolEffect.PAPER_DISCOVERY
        );
    }

    private ProductToolResult outlineResult() {
        return new ProductToolResult(
                "get_paper_outline",
                true,
                Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "status", "OK",
                        "papers", List.of(Map.of(
                                "paperHandle", "paper_handle_abc",
                                "title", "Agentic Eval Benchmark",
                                "originalFilename", "agentic-eval.pdf",
                                "supportedLocationTypes", List.of("PAGE", "SECTION"),
                                "parserQuality", Map.of(
                                        "pageTextCoverage", 1.0,
                                        "outlineConfidence", "HIGH",
                                        "warnings", List.of()
                                ),
                                "sections", List.of(Map.of(
                                        "sectionRef", "section_ref_methods",
                                        "heading", "Methods",
                                        "sectionRole", "METHODS",
                                        "level", 1,
                                        "pageStart", 3,
                                        "pageEnd", 5
                                ))
                        )),
                        "constraints", Map.of(
                                "outlineIsSourceQuote", false,
                                "paperContentClaimsAllowed", false
                        )
                ),
                ProductToolEffect.PAPER_DISCOVERY
        );
    }

    private ProductToolResult readResult() {
        return new ProductToolResult(
                "read_locations",
                true,
                Map.of(
                        "sourceQuotes", List.of(Map.of(
                                "sourceQuoteRef", "source_quote_abc",
                                "locationRef", "page_ref_abc",
                                "paperHandle", "paper_handle_abc",
                                "paperTitle", "Agentic Eval Benchmark",
                                "locationType", "PAGE",
                                "pageNumber", 3,
                                "contentKind", "TEXT",
                                "content", "The reported score improves."
                        )),
                        "readStatus", List.of(Map.of(
                                "locationRef", "page_ref_abc",
                                "status", "OK"
                        ))
                ),
                ProductToolEffect.EVIDENCE
        );
    }

    private ProductToolResult sectionReadResult() {
        return new ProductToolResult(
                "read_locations",
                true,
                Map.of(
                        "sourceQuotes", List.of(Map.of(
                                "sourceQuoteRef", "source_quote_abc",
                                "locationRef", "section_ref_methods",
                                "paperHandle", "paper_handle_abc",
                                "paperTitle", "Agentic Eval Benchmark",
                                "locationType", "SECTION",
                                "pageNumber", 3,
                                "sectionTitle", "Methods",
                                "contentKind", "TEXT",
                                "content", "The Methods section describes the evaluation setup."
                        )),
                        "readStatus", List.of(Map.of(
                                "locationRef", "section_ref_methods",
                                "status", "OK"
                        ))
                ),
                ProductToolEffect.EVIDENCE
        );
    }

    private ProductToolResult traceResult() {
        return new ProductToolResult(
                "trace_source_quotes",
                true,
                Map.of(
                        "sourceQuotes", List.of(Map.of(
                                "sourceQuoteRef", "source_quote_abc",
                                "locationRef", "page_ref_old",
                                "paperHandle", "paper_handle_abc",
                                "paperTitle", "Agentic Eval Benchmark",
                                "locationType", "PAGE",
                                "pageNumber", 3,
                                "contentKind", "TEXT",
                                "content", "Traced source quote content with traced score."
                        )),
                        "traceStatus", List.of(Map.of(
                                "sourceQuoteRef", "source_quote_abc",
                                "status", "OK"
                        ))
                ),
                ProductToolEffect.EVIDENCE
        );
    }

    private List<AgentToolRegistry.AgentTool> readingTools() {
        return List.of(
                tool("search_paper_candidates"),
                tool("get_paper_outline"),
                tool("list_paper_locations"),
                tool("find_reading_locations"),
                tool("read_locations"),
                tool("trace_source_quotes")
        );
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
