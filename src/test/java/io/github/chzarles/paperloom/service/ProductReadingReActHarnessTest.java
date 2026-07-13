package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void passesExactlyReadingToolsToLlmAndPromptContainsNoLegacyToolNames() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(llm.completeReActTurn(eq("7"), any(), any(), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(finalTurn(productStateEnvelope("找到候选论文：Agentic Eval Benchmark。")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        harness.run(request("推荐 Agentic eval 相关论文"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolDefinition>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).completeReActTurn(eq("7"), messagesCaptor.capture(), toolsCaptor.capture(), anyInt());
        for (List<ToolDefinition> capturedTools : toolsCaptor.getAllValues()) {
            List<String> names = capturedTools.stream().map(ToolDefinition::name).toList();
            assertEquals(List.of(
                    "get_session_state",
                    "list_papers",
                    "search_paper_candidates",
                    "find_papers_by_identity",
                    "get_paper_outline",
                    "list_paper_locations",
                    "find_reading_locations",
                    "read_locations",
                    "trace_source_quotes"
            ), names);
            assertFalse(names.contains("retrieve_evidence"));
            assertFalse(names.contains("inspect_reference"));
            assertFalse(names.contains("inspect_page"));
            assertFalse(names.contains("get_paper_metadata"));
            assertFalse(names.contains("resolve_papers"));
            assertFalse(names.contains("answer_without_product_state"));
        }
        String promptMessages = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(0));
        assertFalse(promptMessages.contains("retrieve_evidence"));
        assertFalse(promptMessages.contains("inspect_reference"));
        assertTrue(promptMessages.contains("get_session_state"));
        assertTrue(promptMessages.contains("list_papers"));
        assertTrue(promptMessages.contains("search_paper_candidates"));
        assertTrue(promptMessages.contains("find_papers_by_identity"));
        assertTrue(promptMessages.contains("get_paper_outline"));
        assertTrue(promptMessages.contains("list_paper_locations"));
        assertTrue(promptMessages.contains("find_reading_locations"));
        assertTrue(promptMessages.contains("read_locations"));
        assertTrue(promptMessages.contains("trace_source_quotes"));
        assertTrue(promptMessages.contains("parserQuality"));
        assertTrue(promptMessages.contains("deterministic"));
        assertTrue(promptMessages.contains("semantic"));
        assertTrue(promptMessages.contains("list_papers is not semantic search"));
        assertTrue(promptMessages.contains("find_papers_by_identity is not semantic search"));
        assertTrue(promptMessages.contains("If find_papers_by_identity returns AMBIGUOUS"));
        assertTrue(promptMessages.contains("disclosed by list_papers"));
        assertTrue(promptMessages.contains("trace_source_quotes returned locationRef values are metadata"));
    }

    @Test
    void paperDiscoveryWithTooFewCandidatesReturnsPreciseIncomplete() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(finalTurn(productStateEnvelope("找到候选论文：Agentic Eval Benchmark。")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("推荐 Agentic eval 相关论文"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.ANSWER_SCHEMA_INVALID, result.stopReason());
        assertEquals(AnswerType.INSUFFICIENT_EVIDENCE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("Agentic Eval Benchmark"));
        assertTrue(result.finalAnswerMarkdown().contains("Start here: Agentic Eval Benchmark"));
        assertTrue(result.finalAnswerMarkdown().contains("3 to 5 beginner shortlist papers"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
        assertTrue(result.references().isEmpty());
        assertEquals(1, result.productStateItems().size());
        Map<String, Object> item = result.productStateItems().get(0);
        assertEquals("READING_PAPER_CHOICE", item.get("kind"));
        assertEquals("search_paper_candidates", item.get("sourceTool"));
        assertEquals("paper_handle_abc", item.get("paperHandle"));
        assertEquals("Agentic Eval Benchmark", item.get("title"));
        assertEquals(List.of("Ada Lovelace"), item.get("authors"));
        assertEquals(2025, item.get("year"));
        assertEquals("NeurIPS", item.get("venue"));
        assertFalse(item.containsKey("preview"));
        assertFalse(item.containsKey("ordinal"));
        assertFalse(item.containsKey("paperId"));
        assertFalse(item.containsKey("score"));
        assertFalse(item.containsKey("rank"));
        assertFalse(item.containsKey("locationRef"));
        assertFalse(item.containsKey("sourceQuoteRef"));
        assertFalse(item.containsKey("identityStatus"));
        assertFalse(item.containsKey("ambiguous"));
        verify(registry).execute(eq("search_paper_candidates"), any(), any());
        verify(traceRecorder).recordReadingTurn(any(), any(), any(), any(), any(Instant.class), any(Instant.class));
    }

    @Test
    void paperDiscoveryWithThreeExplicitBeginnerRolesCompletes() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResultWithRows(List.of(
                paperChoiceRow("paper_handle_survey", "Agent Evaluation Survey", "survey"),
                paperChoiceRow("paper_handle_bench", "AgentBench", "benchmark"),
                paperChoiceRow("paper_handle_method", "Agent Evaluation Method", "method")
        )));
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(finalTurn(productStateEnvelope("找到三篇入门候选论文。")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("推荐 Agentic eval 相关论文"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(ProductStopReason.COMPLETED, result.stopReason());
        assertEquals(3, result.readingArtifacts().paperShortlist().items().size());
        assertEquals(List.of("survey", "benchmark", "method"),
                result.readingArtifacts().paperShortlist().items().stream()
                        .map(ReadingTurnArtifacts.PaperShortlistItem::role)
                        .toList());
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
    }

    @Test
    void paperDiscoveryWithMissingExplicitRoleReturnsPreciseIncomplete() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        Map<String, Object> missingRole = paperChoiceRow("paper_handle_unknown", "Unclassified Agent Paper");
        missingRole.remove("paperTypes");
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResultWithRows(List.of(
                paperChoiceRow("paper_handle_survey", "Agent Evaluation Survey", "survey"),
                missingRole,
                paperChoiceRow("paper_handle_method", "Agent Evaluation Method", "method")
        )));
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(finalTurn(productStateEnvelope("找到三篇入门候选论文。")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("推荐 Agentic eval 相关论文"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertTrue(result.finalAnswerMarkdown().contains("explicit beginner paper roles"));
        assertTrue(result.finalAnswerMarkdown().contains("explicit role metadata is missing"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
    }

    @Test
    void paperDiscoveryRejectsModelDeclaredBeginnerRolesWhenToolMetadataHasNoRoles() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        Map<String, Object> survey = paperChoiceRow("paper_handle_survey", "Agent Evaluation Survey");
        survey.remove("paperTypes");
        Map<String, Object> benchmark = paperChoiceRow("paper_handle_bench", "AgentBench");
        benchmark.remove("paperTypes");
        Map<String, Object> critique = paperChoiceRow("paper_handle_critique", "AI Agents That Matter");
        critique.remove("paperTypes");
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResultWithRows(List.of(
                survey,
                benchmark,
                critique
        )));
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "agent evaluation")))
                .thenReturn(finalTurn(productStateEnvelopeWithRoleClaims()));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("推荐适合入门的 AI agent 评测论文"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(List.of("", "", ""),
                result.readingArtifacts().paperShortlist().items().stream()
                        .map(ReadingTurnArtifacts.PaperShortlistItem::role)
                        .toList());
        assertEquals(List.of("missing_role_metadata", "missing_role_metadata", "missing_role_metadata"),
                result.readingArtifacts().paperShortlist().items().stream()
                        .map(ReadingTurnArtifacts.PaperShortlistItem::roleEvidenceSource)
                        .toList());
        assertTrue(result.finalAnswerMarkdown().contains("beginner paper roles from product metadata or quote-backed evidence"));
        assertTrue(result.finalAnswerMarkdown().contains("explicit beginner paper roles"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
    }

    @Test
    void quoteBackedBeginnerRolesCanPopulateShortlistWhenMetadataIsMissing() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        Map<String, Object> survey = paperChoiceRow("paper_handle_survey", "Agent Evaluation Survey");
        survey.remove("paperTypes");
        survey.put("paperId", "paper-survey");
        Map<String, Object> benchmark = paperChoiceRow("paper_handle_bench", "AgentBench");
        benchmark.remove("paperTypes");
        benchmark.put("paperId", "paper-bench");
        Map<String, Object> critique = paperChoiceRow("paper_handle_critique", "AI Agents That Matter");
        critique.remove("paperTypes");
        critique.put("paperId", "paper-critique");
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResultWithRows(List.of(
                survey,
                benchmark,
                critique
        )));
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationResultWithRows(List.of(
                locationRow("section_ref_survey", "paper_handle_survey", "Agent Evaluation Survey", "Survey abstract"),
                locationRow("section_ref_bench", "paper_handle_bench", "AgentBench", "Benchmark abstract"),
                locationRow("section_ref_critique", "paper_handle_critique", "AI Agents That Matter", "Critique abstract")
        )));
        when(registry.execute(eq("read_locations"), any(), any())).thenReturn(readResultWithQuotes(List.of(
                quoteRow("source_quote_survey", "paper-survey", "paper_handle_survey", "section_ref_survey",
                        "Agent Evaluation Survey", "This survey organizes agent evaluation work into a map."),
                quoteRow("source_quote_bench", "paper-bench", "paper_handle_bench", "section_ref_bench",
                        "AgentBench", "AgentBench is a benchmark for evaluating agents."),
                quoteRow("source_quote_critique", "paper-critique", "paper_handle_critique", "section_ref_critique",
                        "AI Agents That Matter", "This paper critiques common agent evaluation practices.")
        )));
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "agent evaluation")))
                .thenReturn(toolCallTurn("call_2", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_survey", "paper_handle_bench", "paper_handle_critique"),
                        "queryPlan", locationQueryPlan("abstract role evidence", "GENERAL", List.of("ABSTRACT"))
                )))
                .thenReturn(toolCallTurn("call_3", "read_locations", Map.of(
                        "locationRefs", List.of("section_ref_survey", "section_ref_bench", "section_ref_critique")
                )))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "The survey gives the beginner map {{sourceQuoteRef:source_quote_survey}}; AgentBench is the benchmark example {{sourceQuoteRef:source_quote_bench}}; AI Agents That Matter is the critique paper {{sourceQuoteRef:source_quote_critique}}.",
                          "evidenceBasedClaims": [
                            {
                              "claim": "Agent Evaluation Survey gives the beginner map.",
                              "sourceQuoteRefs": ["source_quote_survey"],
                              "beginnerRole": "survey"
                            },
                            {
                              "claim": "AgentBench is the benchmark example.",
                              "sourceQuoteRefs": ["source_quote_bench"],
                              "beginnerRole": "benchmark"
                            },
                            {
                              "claim": "AI Agents That Matter is the critique paper.",
                              "sourceQuoteRefs": ["source_quote_critique"],
                              "beginnerRole": "critique"
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

        ProductTurnResult result = harness.run(request("Recommend beginner AI agent evaluation papers with evidence"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(List.of("survey", "benchmark", "critique"),
                result.readingArtifacts().paperShortlist().items().stream()
                        .map(ReadingTurnArtifacts.PaperShortlistItem::role)
                        .toList());
        assertEquals(List.of("quote_backed_evidence", "quote_backed_evidence", "quote_backed_evidence"),
                result.readingArtifacts().paperShortlist().items().stream()
                        .map(ReadingTurnArtifacts.PaperShortlistItem::roleEvidenceSource)
                        .toList());
        assertEquals(List.of(
                        "beginner role backed by quoted evidence; other claims need separate quotes",
                        "beginner role backed by quoted evidence; other claims need separate quotes",
                        "beginner role backed by quoted evidence; other claims need separate quotes"
                ),
                result.readingArtifacts().paperShortlist().items().stream()
                        .map(ReadingTurnArtifacts.PaperShortlistItem::evidenceStatus)
                        .toList());
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
    }

    @Test
    void paperDiscoveryRejectsModelDeclaredBeginnerRoleForUndisclosedPaperHandle() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        Map<String, Object> survey = paperChoiceRow("paper_handle_survey", "Agent Evaluation Survey");
        survey.remove("paperTypes");
        Map<String, Object> benchmark = paperChoiceRow("paper_handle_bench", "AgentBench");
        benchmark.remove("paperTypes");
        Map<String, Object> critique = paperChoiceRow("paper_handle_critique", "AI Agents That Matter");
        critique.remove("paperTypes");
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResultWithRows(List.of(
                survey,
                benchmark,
                critique
        )));
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "agent evaluation")))
                .thenReturn(finalTurn(productStateEnvelopeWithUndisclosedRoleClaim()));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("推荐适合入门的 AI agent 评测论文"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertTrue(result.finalAnswerMarkdown().contains("beginner paper roles from product metadata or quote-backed evidence"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
    }

    @Test
    void paperDiscoveryRejectsModelDeclaredBeginnerRoleOutsideTaxonomy() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        Map<String, Object> survey = paperChoiceRow("paper_handle_survey", "Agent Evaluation Survey");
        survey.remove("paperTypes");
        Map<String, Object> benchmark = paperChoiceRow("paper_handle_bench", "AgentBench");
        benchmark.remove("paperTypes");
        Map<String, Object> critique = paperChoiceRow("paper_handle_critique", "AI Agents That Matter");
        critique.remove("paperTypes");
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResultWithRows(List.of(
                survey,
                benchmark,
                critique
        )));
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "agent evaluation")))
                .thenReturn(finalTurn(productStateEnvelopeWithInvalidRoleClaim()));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("推荐适合入门的 AI agent 评测论文"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertTrue(result.finalAnswerMarkdown().contains("beginner paper roles from product metadata or quote-backed evidence"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
    }

    @Test
    void paperChoiceResultsReturnPreciseIncompleteArtifactsWhenFinalEnvelopeIsInvalid() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(finalTurn("Here are papers, but not JSON."));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("推荐 Agentic eval 相关论文"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.ANSWER_SCHEMA_INVALID, result.stopReason());
        assertEquals(AnswerType.INSUFFICIENT_EVIDENCE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("Agentic Eval Benchmark"));
        assertTrue(result.finalAnswerMarkdown().contains("Start here: Agentic Eval Benchmark"));
        assertTrue(result.finalAnswerMarkdown().contains("metadata-only"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
        assertEquals(1, result.productStateItems().size());
        assertEquals("ANSWER_SCHEMA_INVALID", result.envelope().reason());
        assertEquals(1, result.readingArtifacts().paperShortlist().items().size());
        assertEquals("paper_handle_abc", result.readingStatePatch().selectedPaper().paperHandle());
    }

    @Test
    void readingToolFailureReturnsPreciseIncompleteWithoutLeakingToolName() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "search_paper_candidates",
                        false,
                        Map.of("error", "provider_down"),
                        ProductToolEffect.ERROR
                ));
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("推荐 Agentic eval 相关论文"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        assertFalse(result.finalAnswerMarkdown().contains("Product reading tool failed"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
    }

    @Test
    void firstToolCorrectionRecoversFromPersistedHiddenPaperHandle() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResultWithRows(List.of(
                paperChoiceRow("paper_handle_survey", "Agent Evaluation Survey", "survey"),
                paperChoiceRow("paper_handle_bench", "AgentBench", "benchmark"),
                paperChoiceRow("paper_handle_method", "Agent Evaluation Method", "method")
        )));
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(finalTurn(productStateEnvelope("Here are candidate papers.", "list_papers")))
                .thenReturn(toolCallTurn("call_hidden", "get_paper_outline", Map.of(
                        "paperHandles", List.of("paper_handle_from_old_session")
                )))
                .thenReturn(toolCallTurn("call_search", "search_paper_candidates", Map.of(
                        "queryText", "AI agent evaluation"
                )))
                .thenReturn(finalTurn(productStateEnvelope("Found three current-turn candidates.")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("Find beginner-friendly papers about AI agent evaluation."));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(ProductStopReason.COMPLETED, result.stopReason());
        assertEquals(3, result.readingArtifacts().paperShortlist().items().size());
        verify(registry, never()).execute(eq("get_paper_outline"), any(), any());
        verify(registry).execute(eq("search_paper_candidates"), any(), any());
        verify(llm, times(4)).completeReActTurn(eq("7"), any(), eq(tools), anyInt());
    }

    @Test
    void locationResultsReturnPreciseIncompleteArtifactsWhenFinalEnvelopeIsInvalid() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("list_paper_locations"), any(), any())).thenReturn(listLocationsResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "list_paper_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc")
                )))
                .thenReturn(finalTurn("Found locations, but not JSON."));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedPaperHandles(
                "列出这篇论文可阅读的位置",
                List.of("paper_handle_abc")
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.ANSWER_SCHEMA_INVALID, result.stopReason());
        assertEquals(AnswerType.INSUFFICIENT_EVIDENCE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("Page 3"));
        assertFalse(result.finalAnswerMarkdown().contains("page_ref_abc"));
        assertTrue(result.finalAnswerMarkdown().contains("Start here: Page 3"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
        assertTrue(result.references().isEmpty());
        assertTrue(result.productStateItems().isEmpty());
        assertEquals("ANSWER_SCHEMA_INVALID", result.envelope().reason());
        assertEquals(1, result.readingArtifacts().readingPlan().steps().size());
        assertEquals("page_ref_abc", result.readingStatePatch().selectedLocation().locationRef());
    }

    @Test
    void sourceQuoteResultsReturnPreciseIncompleteStateWhenFinalEnvelopeIsInvalid() {
        ProductTurnResult result = runAfterReadWithFinalEnvelope("""
                {"answerType":"EVIDENCE_ANSWER","answer":"This answer was truncated
                """);

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.ANSWER_SCHEMA_INVALID, result.stopReason());
        assertEquals(AnswerType.INSUFFICIENT_EVIDENCE, result.envelope().answerType());
        assertFalse(result.finalAnswerMarkdown().contains("sourceQuoteRef"));
        assertFalse(result.finalAnswerMarkdown().contains("Source Quote"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
        assertTrue(result.references().isEmpty());
        assertEquals("ANSWER_SCHEMA_INVALID", result.envelope().reason());
    }

    @Test
    void sourceQuoteResultsReturnPreciseIncompleteStateWhenRoundBudgetEnds() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("get_session_state"), any(), any())).thenReturn(sessionStateResult());
        when(registry.execute(eq("get_paper_outline"), any(), any())).thenReturn(outlineResult());
        when(registry.execute(eq("read_locations"), any(), any())).thenReturn(sectionReadResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(multiToolCallTurn(
                        new LlmProviderRouter.ToolCallDecision("call_session", "get_session_state", Map.of()),
                        new LlmProviderRouter.ToolCallDecision("call_outline", "get_paper_outline", Map.of(
                                "paperHandles", List.of("paper_handle_abc")
                        ))
                ))
                .thenReturn(toolCallTurn("call_read", "read_locations", Map.of(
                        "locationRefs", List.of("section_ref_methods")
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-1",
                "阅读这篇论文的方法部分并用 Source Quote 回答",
                SourceScope.auto(),
                List.of(),
                Map.of("readingTurnAnchors", Map.of("clickedPaperHandles", List.of("paper_handle_abc"))),
                new ProductModelContext(2, 1600)
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(AnswerType.INSUFFICIENT_EVIDENCE, result.envelope().answerType());
        assertEquals("MAX_REACT_ROUNDS", result.envelope().reason());
        assertTrue(result.references().isEmpty());
        assertFalse(result.finalAnswerMarkdown().contains("sourceQuoteRef"));
    }

    @Test
    void sessionStateQuestionCallsSessionToolAndAcceptsProductStateAnswer() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("get_session_state"), any(), any())).thenReturn(sessionStateResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "get_session_state", Map.of()))
                .thenReturn(finalTurn(productStateEnvelope(
                        "当前范围有 2 篇可读论文。",
                        "get_session_state"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("当前范围有多少论文"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("2 篇"));
        verify(registry).execute(eq("get_session_state"), any(), any());
        verify(registry, never()).execute(eq("get_paper_outline"), any(), any());
    }

    @Test
    void sessionStateResultReturnsPreciseIncompleteProductStateWhenFinalEnvelopeIsInvalid() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("get_session_state"), any(), any())).thenReturn(sessionStateResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "get_session_state", Map.of()))
                .thenReturn(finalTurn("当前范围有 2 篇可读论文。"));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("当前范围有多少论文"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.ANSWER_SCHEMA_INVALID, result.stopReason());
        assertEquals(AnswerType.INSUFFICIENT_EVIDENCE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("All readable papers"));
        assertTrue(result.finalAnswerMarkdown().contains("2"));
        assertTrue(result.finalAnswerMarkdown().contains("locked"));
        assertFalse(result.finalAnswerMarkdown().contains("AUTO_SOURCE"));
        assertFalse(result.finalAnswerMarkdown().contains("get_session_state"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
        assertTrue(result.references().isEmpty());
        assertTrue(result.productStateItems().isEmpty());
        assertEquals("ANSWER_SCHEMA_INVALID", result.envelope().reason());
        assertTrue(result.envelope().stateClaims().isEmpty());
    }

    @Test
    void finalPaperRecommendationWithInternalIdsReturnsPreciseIncompleteAnswer() {
        ProductTurnResult result = runAfterSearchWithFinalEnvelope("""
                {
                  "answerType": "PRODUCT_STATE",
                  "answer": "Start with paper_handle_abc from search_paper_candidates.",
                  "evidenceBasedClaims": [],
                  "stateClaims": [],
                  "limitations": [],
                  "nonEvidenceNotes": [],
                  "missingFields": [],
                  "reason": ""
                }
                """);

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.ANSWER_SCHEMA_INVALID, result.stopReason());
        assertEquals("ANSWER_SCHEMA_INVALID", result.envelope().reason());
        assertTrue(result.finalAnswerMarkdown().contains("Start here: Agentic Eval Benchmark"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
    }

    @Test
    void finalEvidenceAnswerIsRenderedWithQuoteProofBoundary() {
        ProductTurnResult result = runAfterReadWithFinalEnvelope("""
                {
                  "answerType": "EVIDENCE_ANSWER",
                  "answer": "The selected location reports an improved score. {{sourceQuoteRef:source_quote_abc}}",
                  "evidenceBasedClaims": [
                    {
                      "claim": "The selected location reports an improved score.",
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

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.EVIDENCE_ANSWER, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("[1]"));
        assertTrue(result.finalAnswerMarkdown().contains("This quote proves"));
        assertTrue(result.finalAnswerMarkdown().contains("This quote cannot prove"));
        assertTrue(result.readingArtifacts().traceSummary().verification().valid());
        assertEquals(1, result.readingArtifacts().traceSummary().evidence().acceptedCount());
        assertEquals(1, result.readingArtifacts().traceSummary().claims().supportedCount());
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
    }

    @Test
    void evidenceAnswerWithBlankAtomicClaimFailsResearchTraceGate() {
        ProductTurnResult result = runAfterReadWithFinalEnvelope("""
                {
                  "answerType": "EVIDENCE_ANSWER",
                  "answer": "The selected location reports an improved score. {{sourceQuoteRef:source_quote_abc}}",
                  "evidenceBasedClaims": [
                    {
                      "claim": "",
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

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.CITATION_VALIDATION_FAILED, result.stopReason());
        assertEquals(AnswerType.INSUFFICIENT_EVIDENCE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("research trace"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
    }

    @Test
    void evidenceAnswerWithMissingPaperIdentityReturnsPreciseIncomplete() {
        ProductTurnResult result = runAfterReadWithFinalEnvelope("""
                {
                  "answerType": "EVIDENCE_ANSWER",
                  "answer": "The selected location reports an improved score. {{sourceQuoteRef:source_quote_abc}}",
                  "evidenceBasedClaims": [
                    {
                      "claim": "The selected location reports an improved score.",
                      "sourceQuoteRefs": ["source_quote_abc"]
                    }
                  ],
                  "stateClaims": [],
                  "limitations": [],
                  "nonEvidenceNotes": [],
                  "missingFields": [],
                  "reason": ""
                }
                """, readResultWithoutPaperId());

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.CITATION_VALIDATION_FAILED, result.stopReason());
        assertEquals(AnswerType.INSUFFICIENT_EVIDENCE, result.envelope().answerType());
        assertTrue(result.references().isEmpty());
        assertTrue(result.finalAnswerMarkdown().contains("paper identity"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
    }

    @Test
    void firstNoToolProductStateAnswerIsCorrectedThenCallsSessionTool() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("get_session_state"), any(), any())).thenReturn(sessionStateResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "需要先调用 get_session_state 获取当前范围内的可读论文数量。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [
                            {
                              "claim": "需要调用 get_session_state 获取 READY 论文数量",
                              "sourceTool": "get_session_state"
                            }
                          ],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": ["readablePaperCount"],
                          "reason": "需要先调用 get_session_state"
                        }
                        """))
                .thenReturn(toolCallTurn("call_1", "get_session_state", Map.of()))
                .thenReturn(finalTurn(productStateEnvelope(
                        "当前范围有 2 篇可读论文。",
                        "get_session_state"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("现在库里有多少论文"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("2 篇"));
        verify(registry).execute(eq("get_session_state"), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(3)).completeReActTurn(eq("7"), messagesCaptor.capture(), eq(tools), anyInt());
        String retryPrompt = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(1));
        assertTrue(retryPrompt.contains("previous response did not call a PaperLoom reading tool"));
        assertTrue(retryPrompt.contains("get_session_state"));
    }

    @Test
    void sessionStateQuestionAcceptsAnswerModeProductStateAlias() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("get_session_state"), any(), any())).thenReturn(sessionStateResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "get_session_state", Map.of()))
                .thenReturn(finalTurn("""
                        ```json
                        {
                          "answerMode": "PRODUCT_STATE",
                          "productState": {
                            "searchScope": {
                              "scopeMode": "AUTO_SOURCE",
                              "label": "All readable papers",
                              "readablePaperCountKnown": true,
                              "readablePaperCount": 0,
                              "immutable": true
                            },
                            "status": "OK"
                          },
                          "paperContentClaimsAllowed": false,
                          "candidateList": [],
                          "evidenceBasedClaims": [],
                          "answer": "当前库中（搜索范围：All readable papers）的可读论文数量为 0 篇。"
                        }
                        ```
                        """));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("现在库里有多少论文"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("0 篇"));
    }

    @Test
    void sessionStateQuestionAcceptsNestedAnswerEnvelopeAlias() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("get_session_state"), any(), any())).thenReturn(sessionStateResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "get_session_state", Map.of()))
                .thenReturn(finalTurn("""
                        ```json
                        {
                          "answerEnvelope": {
                            "answerKind": "PRODUCT_STATE",
                            "summary": "当前搜索范围（All readable papers）内可读论文数量为 0 篇。",
                            "scopeLabel": "All readable papers",
                            "readablePaperCount": 0,
                            "evidenceBasedClaims": [],
                            "nextStepHint": "如需进一步筛选或发现论文，可调用 list_papers 浏览或 search_paper_candidates 进行主题检索。"
                          }
                        }
                        ```
                        """));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("现在库里有多少论文"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("0 篇"));
    }

    @Test
    void listPapersQuestionCallsBrowseToolAndAcceptsProductStateAnswer() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("list_papers"), any(), any())).thenReturn(listPapersResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "list_papers", Map.of(
                        "filters", Map.of("titleContains", "Agentic"),
                        "sort", "TITLE"
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到论文：Agentic Eval Benchmark。",
                        "list_papers"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("列出 Agentic 相关标题论文"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("Agentic Eval Benchmark"));
        assertEquals(1, result.productStateItems().size());
        Map<String, Object> item = result.productStateItems().get(0);
        assertEquals("READING_PAPER_CHOICE", item.get("kind"));
        assertEquals("list_papers", item.get("sourceTool"));
        assertEquals("paper_handle_abc", item.get("paperHandle"));
        assertEquals("Agentic Eval Benchmark", item.get("title"));
        assertEquals("agentic-eval.pdf", item.get("originalFilename"));
        assertEquals(List.of("Ada Lovelace"), item.get("authors"));
        assertEquals(2025, item.get("year"));
        assertEquals("NeurIPS", item.get("venue"));
        assertFalse(item.containsKey("preview"));
        assertFalse(item.containsKey("ordinal"));
        assertFalse(item.containsKey("paperId"));
        assertFalse(item.containsKey("catalogTopics"));
        assertFalse(item.containsKey("paperTypes"));
        assertFalse(item.containsKey("facets"));
        assertFalse(item.containsKey("identityStatus"));
        assertFalse(item.containsKey("ambiguous"));
        verify(registry).execute(eq("list_papers"), any(), any());
    }

    @Test
    void directPaperQuestionCallsIdentityToolAndAcceptsProductStateAnswer() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("find_papers_by_identity"), any(), any())).thenReturn(identityResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_papers_by_identity", Map.of(
                        "identityHints", Map.of("titleContains", "LoRA")
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到论文：Agentic Eval Benchmark。",
                        "find_papers_by_identity"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("看 LoRA 那篇"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("Agentic Eval Benchmark"));
        assertEquals(1, result.productStateItems().size());
        Map<String, Object> item = result.productStateItems().get(0);
        assertEquals("READING_PAPER_CHOICE", item.get("kind"));
        assertEquals("find_papers_by_identity", item.get("sourceTool"));
        assertEquals("paper_handle_abc", item.get("paperHandle"));
        assertEquals("OK", item.get("identityStatus"));
        assertEquals(false, item.get("ambiguous"));
        assertFalse(item.containsKey("ordinal"));
        assertFalse(item.containsKey("paperId"));
        assertFalse(item.containsKey("preview"));
        verify(registry).execute(eq("find_papers_by_identity"), any(), any());
        verify(registry, never()).execute(eq("search_paper_candidates"), any(), any());
    }

    @Test
    void exactFilenameIdentityLookupBuildsCanonicalPaperCardAndTraceSummary() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("find_papers_by_identity"), any(), any())).thenReturn(ruleArenaIdentityResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_papers_by_identity", Map.of(
                        "identityHints", Map.of("filenameExact", "2412.08972.pdf")
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到这篇论文：RULEARENA: A Benchmark for Rule-Guided Reasoning with LLMs in Real-World Scenarios。",
                        "find_papers_by_identity"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("打开 2412.08972.pdf"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        assertEquals(1, result.productStateItems().size());
        assertEquals("paper_handle_rulearena", result.productStateItems().get(0).get("paperHandle"));
        assertEquals("RULEARENA: A Benchmark for Rule-Guided Reasoning with LLMs in Real-World Scenarios",
                result.productStateItems().get(0).get("title"));
        assertEquals("2412.08972.pdf", result.productStateItems().get(0).get("originalFilename"));
        assertEquals(1, result.readingArtifacts().paperShortlist().items().size());
        assertEquals("ready-rulearena", result.readingArtifacts().paperShortlist().items().get(0).paperId());
        assertEquals("2412.08972.pdf", result.readingArtifacts().paperShortlist().items().get(0).originalFilename());
        assertTrue(result.readingArtifacts().traceSummary().verification().valid());
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
        verify(registry).execute(eq("find_papers_by_identity"), any(), any());
        verify(registry, never()).execute(eq("search_paper_candidates"), any(), any());
    }

    @Test
    void ambiguousIdentityResultReturnsPaperChoiceProductStateWithoutAuthorizingSameTurnReading() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("find_papers_by_identity"), any(), any())).thenReturn(ambiguousIdentityResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_papers_by_identity", Map.of(
                        "identityHints", Map.of("authorName", "Hu", "year", 2021)
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到两篇可能的论文，请选择。",
                        "find_papers_by_identity"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("看 Hu 2021 那篇"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertTrue(result.finalAnswerMarkdown().contains("multiple possible papers"));
        assertTrue(result.finalAnswerMarkdown().contains("Choose one paper card"));
        assertFalse(result.finalAnswerMarkdown().contains("Start here: Agentic Eval Benchmark"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
        assertEquals(2, result.productStateItems().size());
        Map<String, Object> first = result.productStateItems().get(0);
        assertEquals("READING_PAPER_CHOICE", first.get("kind"));
        assertEquals("find_papers_by_identity", first.get("sourceTool"));
        assertEquals("paper_handle_abc", first.get("paperHandle"));
        assertEquals("Agentic Eval Benchmark", first.get("title"));
        assertEquals("AMBIGUOUS", first.get("identityStatus"));
        assertEquals(true, first.get("ambiguous"));
        assertFalse(first.containsKey("ordinal"));
        assertFalse(first.containsKey("paperId"));
        assertFalse(first.containsKey("preview"));
        assertFalse(first.containsKey("score"));
        assertFalse(first.containsKey("rank"));
        assertFalse(first.containsKey("locationRef"));
        assertFalse(first.containsKey("sourceQuoteRef"));
        assertEquals(null, result.readingStatePatch().selectedPaper());
        assertEquals(2, result.readingStatePatch().latestShortlist().size());
    }

    @Test
    void identityProductStateSkipsInvalidHandlesDedupesAndCapsSanitizedItems() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        List<Map<String, Object>> matches = new ArrayList<>();
        matches.add(identityMatch("paper_handle_000", "First"));
        matches.add(identityMatch("paper_handle_000", "Duplicate"));
        matches.add(identityMatch("not_a_handle", "Invalid"));
        for (int index = 1; index <= 12; index++) {
            matches.add(identityMatch("paper_handle_" + index, "Paper " + index));
        }
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("find_papers_by_identity"), any(), any()))
                .thenReturn(identityResultWithMatches("AMBIGUOUS", true, matches));
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_papers_by_identity", Map.of(
                        "identityHints", Map.of("authorName", "Ada")
                )))
                .thenReturn(finalTurn(productStateEnvelope("请选择论文。", "find_papers_by_identity")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("看 Ada 的论文"));

        List<Map<String, Object>> items = result.productStateItems();
        assertEquals(10, items.size());
        assertEquals("paper_handle_000", items.get(0).get("paperHandle"));
        assertEquals("First", items.get(0).get("title"));
        assertEquals("paper_handle_9", items.get(9).get("paperHandle"));
        assertFalse(items.toString().contains("not_a_handle"));
        assertFalse(items.toString().contains("Duplicate"));
        for (Map<String, Object> item : items) {
            assertEquals("READING_PAPER_CHOICE", item.get("kind"));
            assertFalse(item.containsKey("ordinal"));
            assertFalse(item.containsKey("paperId"));
            assertFalse(item.containsKey("preview"));
            assertFalse(item.containsKey("score"));
            assertFalse(item.containsKey("rank"));
            assertFalse(item.containsKey("locationRef"));
            assertFalse(item.containsKey("sourceQuoteRef"));
        }
    }

    @Test
    void productStateItemsAreDefensiveCopiesOfRawIdentityToolMatches() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        Map<String, Object> rawMatch = new LinkedHashMap<>(identityMatch("paper_handle_abc", "Original"));
        List<Map<String, Object>> matches = new ArrayList<>();
        matches.add(rawMatch);
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("find_papers_by_identity"), any(), any()))
                .thenReturn(identityResultWithMatches("OK", false, matches));
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_papers_by_identity", Map.of(
                        "identityHints", Map.of("titleContains", "Original")
                )))
                .thenReturn(finalTurn(productStateEnvelope("找到论文。", "find_papers_by_identity")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("看 Original"));
        rawMatch.put("title", "Mutated");
        rawMatch.put("paperId", "paper-raw");

        Map<String, Object> item = result.productStateItems().get(0);
        assertEquals("Original", item.get("title"));
        assertFalse(item.containsKey("paperId"));
    }

    @Test
    void paperChoiceProductStateDedupesAcrossPaperLevelToolsAndCapsAtTen() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        List<Map<String, Object>> listRows = new ArrayList<>();
        listRows.add(paperChoiceRow("paper_handle_000", "First"));
        listRows.add(paperChoiceRow("paper_handle_000", "Duplicate"));
        listRows.add(paperChoiceRow("not_a_handle", "Invalid"));
        List<Map<String, Object>> searchRows = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            searchRows.add(paperChoiceRow("paper_handle_" + index, "Paper " + index));
        }
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("list_papers"), any(), any())).thenReturn(paperLevelResult("list_papers", listRows));
        when(registry.execute(eq("search_paper_candidates"), any(), any()))
                .thenReturn(paperLevelResult("search_paper_candidates", searchRows));
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "list_papers", Map.of()))
                .thenReturn(toolCallTurn("call_2", "search_paper_candidates", Map.of("queryText", "agent eval")))
                .thenReturn(finalTurn(productStateEnvelope("请选择论文。", "search_paper_candidates")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("列出再搜索论文"));

        List<Map<String, Object>> items = result.productStateItems();
        assertEquals(10, items.size());
        assertEquals(5, result.readingArtifacts().paperShortlist().items().size());
        assertEquals(5, result.readingStatePatch().latestShortlist().size());
        assertEquals("paper_handle_000", items.get(0).get("paperHandle"));
        assertEquals("list_papers", items.get(0).get("sourceTool"));
        assertEquals("First", items.get(0).get("title"));
        assertEquals("paper_handle_9", items.get(9).get("paperHandle"));
        assertEquals("search_paper_candidates", items.get(9).get("sourceTool"));
        assertFalse(items.toString().contains("Duplicate"));
        assertFalse(items.toString().contains("not_a_handle"));
        for (Map<String, Object> item : items) {
            assertEquals("READING_PAPER_CHOICE", item.get("kind"));
            assertFalse(item.containsKey("preview"));
            assertFalse(item.containsKey("ordinal"));
            assertFalse(item.containsKey("paperId"));
            assertFalse(item.containsKey("score"));
            assertFalse(item.containsKey("rank"));
            assertFalse(item.containsKey("locationRef"));
            assertFalse(item.containsKey("sourceQuoteRef"));
            assertFalse(item.containsKey("catalogTopics"));
            assertFalse(item.containsKey("paperTypes"));
            assertFalse(item.containsKey("facets"));
        }
    }

    @Test
    void nonPaperChoiceToolsDoNotCreatePaperChoiceProductState() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("get_paper_outline"), any(), any())).thenReturn(outlineResult());
        when(registry.execute(eq("list_paper_locations"), any(), any())).thenReturn(listLocationsResult());
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "get_paper_outline", Map.of(
                        "paperHandles", List.of("paper_handle_abc")
                )))
                .thenReturn(toolCallTurn("call_2", "list_paper_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc")
                )))
                .thenReturn(toolCallTurn("call_3", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryPlan", locationQueryPlan("methods", "METHOD", List.of("METHOD"))
                )))
                .thenReturn(finalTurn(productStateEnvelope("这些是导航结果。")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedPaperHandles(
                "从已点击论文查看导航信息",
                List.of("paper_handle_abc")
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertTrue(result.productStateItems().isEmpty());
    }

    @Test
    void navigationQuestionRequiresDisclosedPaperHandleBeforeLocationSearch() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(toolCallTurn("call_2", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryPlan", locationQueryPlan("methods", "METHOD", List.of("METHOD"))
                )))
                .thenReturn(finalTurn(productStateEnvelope("找到候选阅读位置：page_ref_abc。")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("在候选论文里找方法部分"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(AnswerType.INSUFFICIENT_EVIDENCE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("Start here:"));
        assertFalse(result.finalAnswerMarkdown().contains("page_ref_abc"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
        assertTrue(result.references().isEmpty());
        verify(registry).execute(eq("search_paper_candidates"), any(), any());
        verify(registry).execute(eq("find_reading_locations"), any(), any());
    }

    @Test
    void typedLocationNoMatchDoesNotDowngradeToOutlineNavigation() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationNoMatchResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(toolCallTurn("call_2", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryPlan", locationQueryPlan("methods", "METHOD", List.of("METHOD"))
                )))
                .thenReturn(toolCallTurn("call_3", "get_paper_outline", Map.of(
                        "paperHandles", List.of("paper_handle_abc")
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("在候选论文里找方法部分"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        assertTrue(result.readingArtifacts().readingPlan().steps().isEmpty());
        assertTrue(result.readingArtifacts().missingEvidence().missing().contains("semantic_location_evidence"));
        assertTrue(result.finalAnswerMarkdown().contains("typed in-paper search found no matching passage"));
        assertFalse(result.finalAnswerMarkdown().contains("paper_handle_abc"));
        assertEquals("semantic_search", result.researchTrace().retrievalPlan().strategySteps().get(1).retrievalStrategy());
        assertEquals("missing", result.researchTrace().retrievalPlan().strategySteps().get(1).status());
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
        verify(registry, never()).execute(eq("get_paper_outline"), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(3)).completeReActTurn(eq("7"), messagesCaptor.capture(), eq(tools), anyInt());
    }

    @Test
    void listPapersReturnedHandlesCanFeedOutline() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("list_papers"), any(), any())).thenReturn(listPapersResult());
        when(registry.execute(eq("get_paper_outline"), any(), any())).thenReturn(outlineResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "list_papers", Map.of(
                        "filters", Map.of("titleContains", "Agentic")
                )))
                .thenReturn(toolCallTurn("call_2", "get_paper_outline", Map.of(
                        "paperHandles", List.of("paper_handle_abc")
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "Methods section is available as navigation.",
                        "get_paper_outline"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("列出论文并看大纲"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        verify(registry).execute(eq("list_papers"), any(), any());
        verify(registry).execute(eq("get_paper_outline"), any(), any());
    }

    @Test
    void listPapersReturnedHandlesCanFeedListPaperLocations() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("list_papers"), any(), any())).thenReturn(listPapersResult());
        when(registry.execute(eq("list_paper_locations"), any(), any())).thenReturn(listLocationsResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "list_papers", Map.of(
                        "filters", Map.of("titleContains", "Agentic")
                )))
                .thenReturn(toolCallTurn("call_2", "list_paper_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "locationTypes", List.of("SECTION")
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到可导航位置：page_ref_abc。",
                        "list_paper_locations"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("列出论文并看位置"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        verify(registry).execute(eq("list_papers"), any(), any());
        verify(registry).execute(eq("list_paper_locations"), any(), any());
    }

    @Test
    void listPapersReturnedHandlesCanFeedReadingLocationSearch() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("list_papers"), any(), any())).thenReturn(listPapersResult());
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "list_papers", Map.of(
                        "filters", Map.of("titleContains", "Agentic")
                )))
                .thenReturn(toolCallTurn("call_2", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryPlan", locationQueryPlan("methods", "METHOD", List.of("METHOD"))
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到候选阅读位置：page_ref_abc。",
                        "find_reading_locations"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("列出论文并找方法"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        verify(registry).execute(eq("list_papers"), any(), any());
        verify(registry).execute(eq("find_reading_locations"), any(), any());
    }

    @Test
    void unambiguousIdentityHandleCanFeedOutline() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("find_papers_by_identity"), any(), any())).thenReturn(identityResult());
        when(registry.execute(eq("get_paper_outline"), any(), any())).thenReturn(outlineResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_papers_by_identity", Map.of(
                        "identityHints", Map.of("titleContains", "LoRA")
                )))
                .thenReturn(toolCallTurn("call_2", "get_paper_outline", Map.of(
                        "paperHandles", List.of("paper_handle_abc")
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "Methods section is available as navigation.",
                        "get_paper_outline"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("看 LoRA 那篇的大纲"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        verify(registry).execute(eq("find_papers_by_identity"), any(), any());
        verify(registry).execute(eq("get_paper_outline"), any(), any());
    }

    @Test
    void unambiguousIdentityHandleCanFeedListPaperLocations() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("find_papers_by_identity"), any(), any())).thenReturn(identityResult());
        when(registry.execute(eq("list_paper_locations"), any(), any())).thenReturn(listLocationsResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_papers_by_identity", Map.of(
                        "identityHints", Map.of("titleContains", "LoRA")
                )))
                .thenReturn(toolCallTurn("call_2", "list_paper_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "locationTypes", List.of("SECTION")
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到可导航位置：page_ref_abc。",
                        "list_paper_locations"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("看 LoRA 那篇有哪些 section"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        verify(registry).execute(eq("find_papers_by_identity"), any(), any());
        verify(registry).execute(eq("list_paper_locations"), any(), any());
    }

    @Test
    void unambiguousIdentityHandleCanFeedReadingLocationSearch() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("find_papers_by_identity"), any(), any())).thenReturn(identityResult());
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_papers_by_identity", Map.of(
                        "identityHints", Map.of("titleContains", "LoRA")
                )))
                .thenReturn(toolCallTurn("call_2", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryPlan", locationQueryPlan("methods", "METHOD", List.of("METHOD"))
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到候选阅读位置：page_ref_abc。",
                        "find_reading_locations"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("在 LoRA 那篇里找方法"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        verify(registry).execute(eq("find_papers_by_identity"), any(), any());
        verify(registry).execute(eq("find_reading_locations"), any(), any());
    }

    @Test
    void ambiguousIdentityHandlesDoNotAuthorizeOutlineLocationsOrSearch() {
        assertAmbiguousIdentityDoesNotAuthorize("get_paper_outline", Map.of(
                "paperHandles", List.of("paper_handle_abc")
        ));
        assertAmbiguousIdentityDoesNotAuthorize("list_paper_locations", Map.of(
                "paperHandles", List.of("paper_handle_abc"),
                "locationTypes", List.of("SECTION")
        ));
        assertAmbiguousIdentityDoesNotAuthorize("find_reading_locations", Map.of(
                "paperHandles", List.of("paper_handle_abc"),
                "queryPlan", locationQueryPlan("methods", "METHOD", List.of("METHOD"))
        ));
    }

    @Test
    void rejectsHiddenPaperHandleBeforeLocationSearch() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_hidden"),
                        "queryPlan", locationQueryPlan("methods", "METHOD", List.of("METHOD"))
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("在 paper_handle_hidden 里找方法"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("find_reading_locations"), any(), any());
    }

    @Test
    void clickedPaperHandleCanFeedOutlineAndAppearsInPromptAsNavigationOnlyAnchor() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("get_paper_outline"), any(), any())).thenReturn(outlineResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "get_paper_outline", Map.of(
                        "paperHandles", List.of("paper_handle_abc")
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "Methods section is available as navigation.",
                        "get_paper_outline"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedPaperHandles(
                "看这篇论文的大纲",
                List.of("paper_handle_abc")
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        verify(registry).execute(eq("get_paper_outline"), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).completeReActTurn(eq("7"), messagesCaptor.capture(), eq(tools), anyInt());
        String firstPrompt = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(0));
        assertTrue(firstPrompt.contains("Explicit clicked paper anchors"));
        assertTrue(firstPrompt.contains("paper_handle_abc"));
        assertTrue(firstPrompt.contains("Clicked paper anchors are navigation only"));
    }

    @Test
    void persistedCurrentPaperTargetCanFeedOutlineWithoutKeywordRouting() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("get_paper_outline"), any(), any())).thenReturn(outlineResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "get_paper_outline", Map.of(
                        "paperHandles", List.of("paper_handle_abc")
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "Methods section is available as navigation.",
                        "get_paper_outline"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-2",
                "解释这篇论文的大纲",
                SourceScope.auto(),
                List.of(),
                Map.of("readingStatePatch", Map.of(
                        "selectedPaper", Map.of(
                                "paperHandle", "paper_handle_abc",
                                "title", "Agentic Eval Benchmark"
                        )
                )),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        verify(registry).execute(eq("get_paper_outline"), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).completeReActTurn(eq("7"), messagesCaptor.capture(), eq(tools), anyInt());
        String firstPrompt = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(0));
        assertTrue(firstPrompt.contains("Persisted current reading target"));
        assertTrue(firstPrompt.contains("paper_handle_abc"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
    }

    @Test
    void clickedPaperHandleCanFeedListPaperLocations() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("list_paper_locations"), any(), any())).thenReturn(listLocationsResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "list_paper_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "locationTypes", List.of("SECTION")
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到可导航位置：page_ref_abc。",
                        "list_paper_locations"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedPaperHandles(
                "看这篇论文的位置",
                List.of("paper_handle_abc")
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        verify(registry).execute(eq("list_paper_locations"), any(), any());
    }

    @Test
    void clickedPaperHandleCanFeedReadingLocationSearch() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryPlan", locationQueryPlan("methods", "METHOD", List.of("METHOD"))
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到候选阅读位置：page_ref_abc。",
                        "find_reading_locations"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedPaperHandlesAndAction(
                "在这篇论文里找方法",
                List.of("paper_handle_abc"),
                "FIND_LOCATIONS"
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        verify(registry).execute(eq("find_reading_locations"), any(), any());
        assertEquals(List.of("METHOD"), result.readingArtifacts().intentFrame().locationIntents());
        assertEquals(List.of("METHOD"), result.readingArtifacts().intentFrame().sectionRoles());
        assertEquals(1, result.readingArtifacts().intentFrame().locationQueryPlans().size());
        assertEquals("methods", result.readingArtifacts().intentFrame().locationQueryPlans().get(0).queryText());
        assertEquals("en", result.readingArtifacts().intentFrame().locationQueryPlans().get(0).retrievalLanguage());
        assertEquals("typed_location_query_plan_observed", result.readingArtifacts().intentFrame().planningStatus());
    }

    @Test
    void explicitFindLocationsActionRejectsRawQueryTextWithoutTypedQueryPlan() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryText", "methods"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedPaperHandlesAndAction(
                "在这篇论文里找方法",
                List.of("paper_handle_abc"),
                "FIND_LOCATIONS"
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals("typed_location_query_plan_missing", result.readingArtifacts().intentFrame().planningStatus());
        assertTrue(result.finalAnswerMarkdown().contains("validated typed query plan"));
        verify(registry, never()).execute(eq("find_reading_locations"), any(), any());
    }

    @Test
    void explicitFindLocationsActionRejectsIncompleteTypedQueryPlanBeforeToolExecution() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryPlan", Map.of(
                                "queryText", "methods",
                                "intent", "METHOD",
                                "sectionRoles", List.of("METHOD")
                        )
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedPaperHandlesAndAction(
                "在这篇论文里找方法",
                List.of("paper_handle_abc"),
                "FIND_LOCATIONS"
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals("typed_location_query_plan_missing", result.readingArtifacts().intentFrame().planningStatus());
        assertTrue(result.finalAnswerMarkdown().contains("validated typed query plan"));
        verify(registry, never()).execute(eq("find_reading_locations"), any(), any());
    }

    @Test
    void semanticLocationSearchWithoutExplicitFindActionMustReadBeforeFinalAnswer() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationResult());
        when(registry.execute(eq("read_locations"), any(), any())).thenReturn(readResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryPlan", locationQueryPlan("methods", "METHOD", List.of("METHOD"))
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到候选阅读位置：page_ref_abc。",
                        "find_reading_locations"
                )))
                .thenReturn(toolCallTurn("call_read", "read_locations", Map.of(
                        "locationRefs", List.of("page_ref_abc")
                )))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "The selected location reports an improved score. {{sourceQuoteRef:source_quote_abc}}",
                          "evidenceBasedClaims": [
                            {
                              "claim": "The selected location reports an improved score.",
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

        ProductTurnResult result = harness.run(requestWithClickedPaperHandles(
                "阅读这篇论文中方法相关的位置，并用 Source Quote 给出证据回答。",
                List.of("paper_handle_abc")
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.EVIDENCE_ANSWER, result.envelope().answerType());
        assertEquals(1, result.references().size());
        verify(registry).execute(eq("find_reading_locations"), any(), any());
        verify(registry).execute(eq("read_locations"), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void semanticLocationSearchWithoutExplicitFindActionDoesNotAutoReadDisclosedRefsWhenModelStopsAtNavigation() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryPlan", locationQueryPlan("methods", "METHOD", List.of("METHOD"))
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到候选阅读位置：page_ref_abc。",
                        "find_reading_locations"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-2",
                "阅读这篇论文中方法相关的位置，并用 Source Quote 给出证据回答。",
                SourceScope.auto(),
                List.of(),
                Map.of("readingTurnAnchors", Map.of("clickedPaperHandles", List.of("paper_handle_abc"))),
                new ProductModelContext(2, 1600)
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(AnswerType.INSUFFICIENT_EVIDENCE, result.envelope().answerType());
        verify(registry, never()).execute(eq("read_locations"), any(), any());
        verify(llm, times(2)).completeReActTurn(eq("7"), any(), eq(tools), anyInt());
    }

    @Test
    void explicitFindLocationsActionCorrectsWrongFirstToolWithoutKeywordRouting() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_wrong", "get_paper_outline", Map.of(
                        "paperHandles", List.of("paper_handle_abc")
                )))
                .thenReturn(toolCallTurn("call_right", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryPlan", locationQueryPlan(
                                "experiment settings",
                                "EXPERIMENT_SETUP",
                                List.of("EXPERIMENT")
                        )
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到候选阅读位置：page_ref_abc。",
                        "find_reading_locations"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedPaperHandlesAndAction(
                "在这篇论文里查找实验设置相关位置",
                List.of("paper_handle_abc"),
                "FIND_LOCATIONS"
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        verify(registry, never()).execute(eq("get_paper_outline"), any(), any());
        verify(registry).execute(eq("find_reading_locations"), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(3)).completeReActTurn(eq("7"), messagesCaptor.capture(), eq(tools), anyInt());
        String firstPrompt = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(0));
        String secondPrompt = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(1));
        assertTrue(firstPrompt.contains("Explicit Product Reading UI action"));
        assertTrue(firstPrompt.contains("FIND_LOCATIONS"));
        assertTrue(firstPrompt.contains("queryPlan"));
        assertTrue(secondPrompt.contains("explicit_product_action_requires_find_reading_locations"));
    }

    @Test
    void explicitListLocationsActionCorrectsSessionStateBeforeListLocations() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("get_session_state"), any(), any())).thenReturn(sessionStateResult());
        when(registry.execute(eq("list_paper_locations"), any(), any())).thenReturn(listLocationsResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_wrong", "get_session_state", Map.of()))
                .thenReturn(toolCallTurn("call_right", "list_paper_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "locationTypes", List.of("SECTION")
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到可导航位置：page_ref_abc。",
                        "list_paper_locations"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedPaperHandlesAndAction(
                "列出这篇论文可阅读的位置",
                List.of("paper_handle_abc"),
                "LIST_LOCATIONS"
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        verify(registry, never()).execute(eq("get_session_state"), any(), any());
        verify(registry).execute(eq("list_paper_locations"), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitSearchPapersActionExecutesRequiredToolWhenModelReturnsNoTool() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(finalTurn("I can answer without a tool."))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到候选论文：Agentic Eval Benchmark。",
                        "search_paper_candidates"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithReadingAction(
                "找几篇 agentic retrieval 或 reasoning 相关论文",
                "SEARCH_PAPERS"
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(registry).execute(eq("search_paper_candidates"), argumentsCaptor.capture(), any());
        assertEquals("找几篇 agentic retrieval 或 reasoning 相关论文", argumentsCaptor.getValue().get("queryText"));
    }

    @Test
    void explicitFindLocationsActionReturnsPreciseIncompleteWhenModelOmitsTypedQueryPlan() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(finalTurn("I can answer without a tool."));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedPaperHandlesAndAction(
                "在这篇论文里查找实验设置相关位置",
                List.of("paper_handle_abc"),
                "FIND_LOCATIONS"
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertTrue(result.finalAnswerMarkdown().contains("validated typed query plan"));
        verify(registry, never()).execute(eq("find_reading_locations"), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitListLocationsActionExecutesRequiredToolWhenModelReturnsNoTool() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("list_paper_locations"), any(), any())).thenReturn(listLocationsResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(finalTurn("I can answer without a tool."))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到可导航位置：page_ref_abc。",
                        "list_paper_locations"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedPaperHandlesAndAction(
                "列出这篇论文可阅读的位置",
                List.of("paper_handle_abc"),
                "LIST_LOCATIONS"
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(registry).execute(eq("list_paper_locations"), argumentsCaptor.capture(), any());
        assertEquals(List.of("paper_handle_abc"), argumentsCaptor.getValue().get("paperHandles"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitListLocationsActionReturnsReadingPlanWhenModelProviderIsUnavailable() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("list_paper_locations"), any(), any())).thenReturn(listLocationsResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenThrow(new RuntimeException("provider unavailable"));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedPaperHandlesAndAction(
                "列出这篇论文可阅读的位置",
                List.of("paper_handle_abc"),
                "LIST_LOCATIONS"
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertTrue(result.finalAnswerMarkdown().contains("Page 3"));
        assertFalse(result.finalAnswerMarkdown().contains("AI服务暂时不可用"));
        assertFalse(result.finalAnswerMarkdown().contains("provider unavailable"));
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(registry).execute(eq("list_paper_locations"), argumentsCaptor.capture(), any());
        assertEquals(List.of("paper_handle_abc"), argumentsCaptor.getValue().get("paperHandles"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitListLocationsActionCanUsePersistedSelectedPaperWhenModelReturnsNoTool() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("list_paper_locations"), any(), any())).thenReturn(listLocationsResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(finalTurn("I can answer without a tool."))
                .thenReturn(finalTurn(productStateEnvelope(
                        "I found concrete reading locations for the current paper.",
                        "list_paper_locations"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithPersistedSelectedPaperAndAction(
                "列出这篇论文可阅读的位置",
                "paper_handle_abc",
                "LIST_LOCATIONS"
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(registry).execute(eq("list_paper_locations"), argumentsCaptor.capture(), any());
        assertEquals(List.of("paper_handle_abc"), argumentsCaptor.getValue().get("paperHandles"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitReadLocationActionExecutesReadLocationsWithClickedLocationRefWhenModelReturnsNoTool() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("read_locations"), any(), any())).thenReturn(readResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(finalTurn("I can answer without a tool."))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "The clicked location reports a score improvement {{sourceQuoteRef:source_quote_abc}}.",
                          "evidenceBasedClaims": [
                            {
                              "claim": "The clicked location reports a score improvement.",
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

        ProductTurnResult result = harness.run(requestWithClickedLocationRefsAndAction(
                "读取这个位置",
                List.of("page_ref_abc"),
                "READ_LOCATION"
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.EVIDENCE_ANSWER, result.envelope().answerType());
        assertEquals(1, result.references().size());
        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(registry).execute(eq("read_locations"), argumentsCaptor.capture(), any());
        assertEquals(List.of("page_ref_abc"), argumentsCaptor.getValue().get("locationRefs"));
        verify(registry, never()).execute(eq("find_reading_locations"), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitReadLocationActionReturnsDirectQuoteWhenModelProviderIsUnavailable() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("read_locations"), any(), any())).thenReturn(readResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenThrow(new RuntimeException("provider unavailable"));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedLocationRefsAndAction(
                "读取这个位置",
                List.of("page_ref_abc"),
                "READ_LOCATION"
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.EVIDENCE_ANSWER, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("[1]"));
        assertTrue(result.finalAnswerMarkdown().contains("The reported score improves."));
        assertFalse(result.finalAnswerMarkdown().contains("sourceQuoteRef"));
        assertFalse(result.finalAnswerMarkdown().contains("AI服务暂时不可用"));
        assertEquals(1, result.references().size());
        assertEquals("source_quote_abc", result.references().get(0).get("sourceQuoteRef"));
        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(registry).execute(eq("read_locations"), argumentsCaptor.capture(), any());
        assertEquals(List.of("page_ref_abc"), argumentsCaptor.getValue().get("locationRefs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitReadLocationActionUsesReadToolThenWaitsForModelEvidenceSynthesis() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("read_locations"), any(), any())).thenReturn(readResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_read", "read_locations", Map.of(
                        "locationRefs", List.of("page_ref_abc")
                )))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "The selected location reports a score improvement {{sourceQuoteRef:source_quote_abc}}.",
                          "evidenceBasedClaims": [
                            {
                              "claim": "The selected location reports a score improvement.",
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

        ProductTurnResult result = harness.run(requestWithClickedLocationRefsAndAction(
                "读取这个位置",
                List.of("page_ref_abc"),
                "READ_LOCATION"
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.EVIDENCE_ANSWER, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("[1]"));
        assertTrue(result.finalAnswerMarkdown().contains("score improvement"));
        assertFalse(result.finalAnswerMarkdown().contains("sourceQuoteRef"));
        assertEquals(1, result.references().size());
        assertEquals("source_quote_abc", result.references().get(0).get("sourceQuoteRef"));
        verify(llm, times(2)).completeReActTurn(eq("7"), any(), eq(tools), anyInt());
        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(registry).execute(eq("read_locations"), argumentsCaptor.capture(), any());
        assertEquals(List.of("page_ref_abc"), argumentsCaptor.getValue().get("locationRefs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitReadLocationActionCanUsePersistedSelectedLocationWhenModelReturnsNoTool() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("read_locations"), any(), any())).thenReturn(readResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(finalTurn("I can answer without a tool."))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "The persisted location reports a score improvement {{sourceQuoteRef:source_quote_abc}}.",
                          "evidenceBasedClaims": [
                            {
                              "claim": "The persisted location reports a score improvement.",
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

        ProductTurnResult result = harness.run(requestWithPersistedSelectedLocationAndAction(
                "读取这个位置",
                "page_ref_abc",
                "READ_LOCATION"
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.EVIDENCE_ANSWER, result.envelope().answerType());
        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(registry).execute(eq("read_locations"), argumentsCaptor.capture(), any());
        assertEquals(List.of("page_ref_abc"), argumentsCaptor.getValue().get("locationRefs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitTraceSourceQuoteActionExecutesTraceWithClickedSourceQuoteRefWhenModelReturnsNoTool() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("trace_source_quotes"), any(), any())).thenReturn(traceResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(finalTurn("I can answer without a tool."))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "The clicked citation reports a traced score {{sourceQuoteRef:source_quote_abc}}.",
                          "evidenceBasedClaims": [
                            {
                              "claim": "The clicked citation reports a traced score.",
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

        ProductTurnResult result = harness.run(requestWithClickedRefsAndAction(
                "解释这个引用",
                List.of("source_quote_abc"),
                "TRACE_SOURCE_QUOTE"
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(registry).execute(eq("trace_source_quotes"), argumentsCaptor.capture(), any());
        assertEquals(List.of("source_quote_abc"), argumentsCaptor.getValue().get("sourceQuoteRefs"));
        verify(registry, never()).execute(eq("search_paper_candidates"), any(), any());
    }

    @Test
    void invalidClickedLocationRefsDoNotAuthorizeReadLocationAction() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(finalTurn("I can answer without a tool."));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedLocationRefsAndAction(
                "读取这个位置",
                List.of("not_a_location_ref"),
                "READ_LOCATION"
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        assertTrue(result.finalAnswerMarkdown().contains("validated target"));
        verify(registry, never()).execute(eq("read_locations"), any(), any());
    }

    @Test
    void explicitSearchPapersActionCorrectsSessionStateBeforeCandidateSearch() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_wrong", "get_session_state", Map.of()))
                .thenReturn(toolCallTurn("call_right", "search_paper_candidates", Map.of(
                        "queryText", "agentic retrieval reasoning"
                )))
                .thenReturn(finalTurn(productStateEnvelope(
                        "找到候选论文：Agentic Eval Benchmark。",
                        "search_paper_candidates"
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithReadingAction(
                "找几篇 agentic retrieval 或 reasoning 相关论文",
                "SEARCH_PAPERS"
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        verify(registry, never()).execute(eq("get_session_state"), any(), any());
        verify(registry).execute(eq("search_paper_candidates"), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(3)).completeReActTurn(eq("7"), messagesCaptor.capture(), eq(tools), anyInt());
        String secondPrompt = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(1));
        assertTrue(secondPrompt.contains("explicit_product_action_requires_search_paper_candidates"));
    }

    @Test
    void clickedPaperHandleDoesNotAuthorizeDirectReadLocations() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "read_locations", Map.of(
                        "locationRefs", List.of("page_ref_abc")
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedPaperHandles(
                "直接读这篇论文",
                List.of("paper_handle_abc")
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("read_locations"), any(), any());
    }

    @Test
    void invalidClickedPaperHandlesAreIgnored() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "get_paper_outline", Map.of(
                        "paperHandles", List.of("not_a_paper_handle")
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedPaperHandles(
                "看这篇论文的大纲",
                List.of("not_a_paper_handle")
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("get_paper_outline"), any(), any());
    }

    @Test
    void sourceQuotedQuestionReadsLocationsAndRendersSourceQuoteReferences() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationResult());
        when(registry.execute(eq("read_locations"), any(), any())).thenReturn(readResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(toolCallTurn("call_2", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryPlan", locationQueryPlan("score", "METRIC", List.of("RESULT"))
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
        List<ToolDefinition> tools = readingTools();
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
        List<ToolDefinition> tools = readingTools();
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
    void keepsBatchedToolResultsAdjacentBeforePolicyGuidance() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationResult());
        when(registry.execute(eq("read_locations"), any(), any())).thenReturn(readResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(multiToolCallTurn(
                        new LlmProviderRouter.ToolCallDecision("call_find", "find_reading_locations", Map.of(
                                "paperHandles", List.of("paper_handle_abc"),
                                "queryPlan", locationQueryPlan("methods", "METHOD", List.of("METHOD"))
                        )),
                        new LlmProviderRouter.ToolCallDecision("call_read", "read_locations", Map.of(
                                "locationRefs", List.of("page_ref_abc")
                        ))
                ))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "The method is supported by the reported evidence {{sourceQuoteRef:source_quote_abc}}.",
                          "evidenceBasedClaims": [
                            {
                              "claim": "The method is supported by the reported evidence.",
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

        ProductTurnResult result = harness.run(requestWithClickedPaperHandles(
                "Read the methods evidence",
                List.of("paper_handle_abc")
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).completeReActTurn(eq("7"), messagesCaptor.capture(), eq(tools), anyInt());
        List<Map<String, Object>> secondRoundMessages = messagesCaptor.getAllValues().get(1);
        assertEquals(List.of("system", "user", "assistant", "tool", "tool", "user"), roles(secondRoundMessages));
        assertEquals("call_find", secondRoundMessages.get(3).get("tool_call_id"));
        assertEquals("call_read", secondRoundMessages.get(4).get("tool_call_id"));
    }

    @Test
    void rejectsHiddenPaperHandleBeforeOutline() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "get_paper_outline", Map.of(
                        "paperHandles", List.of("paper_handle_hidden")
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("Outline paper_handle_hidden"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("get_paper_outline"), any(), any());
    }

    @Test
    void rejectsHiddenLocationRefBeforeReadLocations() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(toolCallTurn("call_2", "read_locations", Map.of(
                        "locationRefs", List.of("page_ref_hidden")
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("阅读隐藏位置"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("read_locations"), any(), any());
    }

    @Test
    void followUpQuestionTracesClickedSourceQuoteAndRendersReferences() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
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
    @SuppressWarnings("unchecked")
    void persistedSelectedSourceQuoteCanFeedTraceWithoutExplicitClickedAnchor() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("trace_source_quotes"), any(), any())).thenReturn(traceResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "trace_source_quotes", Map.of(
                        "sourceQuoteRefs", List.of("source_quote_abc")
                )))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "The persisted citation reports a traced score {{sourceQuoteRef:source_quote_abc}}.",
                          "evidenceBasedClaims": [
                            {
                              "claim": "The persisted citation reports a traced score.",
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

        ProductTurnResult result = harness.run(requestWithPersistedSelectedSourceQuote(
                "Explain this citation",
                "source_quote_abc"
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(registry).execute(eq("trace_source_quotes"), argumentsCaptor.capture(), any());
        assertEquals(List.of("source_quote_abc"), argumentsCaptor.getValue().get("sourceQuoteRefs"));

        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).completeReActTurn(eq("7"), messagesCaptor.capture(), eq(tools), anyInt());
        String firstPrompt = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(0));
        assertTrue(firstPrompt.contains("persisted selected Source Quote target"));
        assertTrue(firstPrompt.contains("source_quote_abc"));
    }

    @Test
    void rejectsHiddenSourceQuoteRefBeforeTraceSourceQuotes() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
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

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("trace_source_quotes"), any(), any());
    }

    @Test
    void rejectsClickedSourceQuoteAsFinalSupportBeforeTraceReturnsIt() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
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

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.CITATION_VALIDATION_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("trace_source_quotes"), any(), any());
    }

    @Test
    void traceOutputDoesNotDiscloseLocationRefsForReadLocations() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
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

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("read_locations"), any(), any());
    }

    @Test
    void tracePaperHandleCanBeUsedForListButTraceLocationRefStillCannotBeReadDirectly() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
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

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry).execute(eq("list_paper_locations"), any(), any());
        verify(registry, never()).execute(eq("read_locations"), any(), any());
    }

    @Test
    void tracePaperHandleDoesNotAuthorizeFindReadingLocations() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("trace_source_quotes"), any(), any())).thenReturn(traceResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "trace_source_quotes", Map.of(
                        "sourceQuoteRefs", List.of("source_quote_abc")
                )))
                .thenReturn(toolCallTurn("call_2", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryPlan", locationQueryPlan("nearby context", "GENERAL", List.of("METHOD"))
                )));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(requestWithClickedRefs(
                "Search around this source",
                List.of("source_quote_abc")
        ));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry, never()).execute(eq("find_reading_locations"), any(), any());
    }

    @Test
    void tracePaperHandleCanBeUsedForOutline() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
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

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
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

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.CITATION_VALIDATION_FAILED, result.stopReason());
        assertTrue(result.references().isEmpty());
    }

    @Test
    void completesEvidenceAnswerWhenClaimSupportHasReturnedSourceQuoteWithoutVisibleMarker() {
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

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(ProductStopReason.COMPLETED, result.stopReason());
        assertEquals(AnswerType.EVIDENCE_ANSWER, result.envelope().answerType());
        assertEquals("source_quote_abc", result.references().get(0).get("sourceQuoteRef"));
        assertTrue(result.finalAnswerMarkdown().contains("[1]"));
        assertEquals(1, result.readingArtifacts().claimEvidencePanel().rows().size());
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

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
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

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
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

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, numbered.resultStatus());
        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, internal.resultStatus());
        assertEquals("ANSWER_SCHEMA_INVALID", internal.envelope().reason());
        assertNoviceVisibleAnswer(internal.finalAnswerMarkdown());
    }

    @Test
    void rejectsVisiblePaperIdValueEvenWhenFieldNameIsHidden() {
        ProductTurnResult result = runAfterReadWithFinalEnvelope("""
                {
                  "answerType": "EVIDENCE_ANSWER",
                  "answer": "paper-raw reports a score improvement {{sourceQuoteRef:source_quote_abc}}.",
                  "evidenceBasedClaims": [
                    {
                      "claim": "paper-raw reports a score improvement.",
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

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.ANSWER_SCHEMA_INVALID, result.stopReason());
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
    }

    @Test
    void rejectsNonReadingToolSurfaceBeforeCallingLlm() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        when(registry.listTools()).thenReturn(List.of(tool("search_paper_candidates")));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("推荐 Agentic eval 相关论文"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        assertNoviceVisibleAnswer(result.finalAnswerMarkdown());
        verify(traceRecorder).recordReadingTurn(any(), any(), any(), any(), any(Instant.class), any(Instant.class));
    }

    private ProductTurnResult runAfterSearchWithFinalEnvelope(String finalEnvelope) {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(finalTurn(finalEnvelope));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        return harness.run(request("说明推荐理由"));
    }

    private ProductTurnResult runAfterReadWithFinalEnvelope(String finalEnvelope) {
        return runAfterReadWithFinalEnvelope(finalEnvelope, readResult());
    }

    private ProductTurnResult runAfterReadWithFinalEnvelope(String finalEnvelope, ProductToolResult readToolResult) {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
        when(registry.execute(eq("find_reading_locations"), any(), any())).thenReturn(locationResult());
        when(registry.execute(eq("read_locations"), any(), any())).thenReturn(readToolResult);
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
                .thenReturn(toolCallTurn("call_2", "find_reading_locations", Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryPlan", locationQueryPlan("score", "METRIC", List.of("RESULT"))
                )))
                .thenReturn(toolCallTurn("call_3", "read_locations", Map.of("locationRefs", List.of("page_ref_abc"))))
                .thenReturn(finalTurn(finalEnvelope));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        return harness.run(request("说明推荐理由"));
    }

    private void assertAmbiguousIdentityDoesNotAuthorize(String followUpTool, Map<String, Object> followUpArguments) {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
        ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
        List<ToolDefinition> tools = readingTools();
        when(registry.listTools()).thenReturn(tools);
        when(registry.execute(eq("find_papers_by_identity"), any(), any())).thenReturn(ambiguousIdentityResult());
        when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_papers_by_identity", Map.of(
                        "identityHints", Map.of("authorName", "Hu", "year", 2021)
                )))
                .thenReturn(toolCallTurn("call_2", followUpTool, followUpArguments));
        ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(request("看 Hu 2021 那篇"));

        assertEquals(ProductResultStatus.INCOMPLETE_PRECISE, result.resultStatus());
        assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
        verify(registry).execute(eq("find_papers_by_identity"), any(), any());
        verify(registry, never()).execute(eq(followUpTool), any(), any());
        assertTrue(ambiguousIdentityResult().data().toString().contains("paper_handle_abc"));
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

    private ProductTurnRequest requestWithClickedRefsAndAction(String message,
                                                               List<String> clickedRefs,
                                                               String readingAction) {
        return new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-2",
                message,
                SourceScope.auto(),
                List.of(),
                Map.of(
                        "readingTurnAnchors", Map.of("clickedSourceQuoteRefs", clickedRefs),
                        "readingTurnAction", readingAction
                ),
                ProductModelContext.defaults()
        );
    }

    private ProductTurnRequest requestWithPersistedSelectedSourceQuote(String message, String sourceQuoteRef) {
        return new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-2",
                message,
                SourceScope.auto(),
                List.of(),
                Map.of(
                        "readingStatePatch", Map.of(
                                "selectedSourceQuote", Map.of(
                                        "sourceQuoteRef", sourceQuoteRef,
                                        "paperHandle", "paper_handle_abc",
                                        "locationRef", "page_ref_old",
                                        "citationMarker", "[1]"
                                )
                        )
                ),
                ProductModelContext.defaults()
        );
    }

    private ProductTurnRequest requestWithPersistedSelectedPaperAndAction(String message,
                                                                          String paperHandle,
                                                                          String readingAction) {
        return new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-2",
                message,
                SourceScope.auto(),
                List.of(),
                Map.of(
                        "readingStatePatch", Map.of(
                                "selectedPaper", Map.of(
                                        "paperHandle", paperHandle,
                                        "title", "Agentic Eval Benchmark"
                                )
                        ),
                        "readingTurnAction", readingAction
                ),
                ProductModelContext.defaults()
        );
    }

    private ProductTurnRequest requestWithPersistedSelectedLocationAndAction(String message,
                                                                             String locationRef,
                                                                             String readingAction) {
        return new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-2",
                message,
                SourceScope.auto(),
                List.of(),
                Map.of(
                        "readingStatePatch", Map.of(
                                "selectedLocation", Map.of(
                                        "locationRef", locationRef,
                                        "paperHandle", "paper_handle_abc",
                                        "locationLabel", "Page 3"
                                )
                        ),
                        "readingTurnAction", readingAction
                ),
                ProductModelContext.defaults()
        );
    }

    private ProductTurnRequest requestWithClickedPaperHandles(String message, List<String> clickedPaperHandles) {
        return new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-2",
                message,
                SourceScope.auto(),
                List.of(),
                Map.of("readingTurnAnchors", Map.of("clickedPaperHandles", clickedPaperHandles)),
                ProductModelContext.defaults()
        );
    }

    private ProductTurnRequest requestWithClickedPaperHandlesAndAction(String message,
                                                                       List<String> clickedPaperHandles,
                                                                       String readingAction) {
        return new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-2",
                message,
                SourceScope.auto(),
                List.of(),
                Map.of(
                        "readingTurnAnchors", Map.of("clickedPaperHandles", clickedPaperHandles),
                        "readingTurnAction", readingAction
                ),
                ProductModelContext.defaults()
        );
    }

    private ProductTurnRequest requestWithClickedLocationRefsAndAction(String message,
                                                                       List<String> clickedLocationRefs,
                                                                       String readingAction) {
        return new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-2",
                message,
                SourceScope.auto(),
                List.of(),
                Map.of(
                        "readingTurnAnchors", Map.of("clickedLocationRefs", clickedLocationRefs),
                        "readingTurnAction", readingAction
                ),
                ProductModelContext.defaults()
        );
    }

    private ProductTurnRequest requestWithReadingAction(String message, String readingAction) {
        return new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-2",
                message,
                SourceScope.auto(),
                List.of(),
                Map.of("readingTurnAction", readingAction),
                ProductModelContext.defaults()
        );
    }

    private Map<String, Object> locationQueryPlan(String queryText, String intent, List<String> sectionRoles) {
        return Map.of(
                "queryText", queryText,
                "intent", intent,
                "sourceLanguage", "zh",
                "retrievalLanguage", "en",
                "sectionRoles", sectionRoles
        );
    }

    private void assertNoviceVisibleAnswer(String answer) {
        assertTrue(answer.contains("I understand your goal as:"), answer);
        assertTrue(answer.contains("Short answer:"), answer);
        assertTrue(answer.contains("Start here:"), answer);
        assertTrue(answer.contains("How to verify:"), answer);
        assertTrue(answer.contains("Not verified yet:"), answer);
        assertTrue(answer.contains("Next step:"), answer);
        assertNoInternalVisibleLeaks(answer);
    }

    private void assertNoInternalVisibleLeaks(String answer) {
        List<String> forbiddenVisibleTokens = List.of(
                "paper_handle_",
                "page_ref_",
                "section_ref_",
                "location_ref_",
                "source_quote_",
                "paperHandle",
                "locationRef",
                "sourceQuoteRef",
                "parserQuality",
                "parserName",
                "parserVersion",
                "AUTO_SOURCE",
                "AUTO_LIBRARY",
                "SOURCE_SET_SNAPSHOT",
                "immutable=true",
                "Source Quote",
                "get_session_state",
                "list_papers",
                "search_paper_candidates",
                "find_papers_by_identity",
                "get_paper_outline",
                "list_paper_locations",
                "find_reading_locations",
                "read_locations",
                "trace_source_quotes",
                "failed validation",
                "citation linkage",
                "generated interpretation",
                "I will not present"
        );
        for (String token : forbiddenVisibleTokens) {
            assertFalse(answer.contains(token), token + " leaked in: " + answer);
        }
    }

    private ProductToolResult searchResult() {
        return searchResultWithRows(List.of(paperChoiceRow("paper_handle_abc", "Agentic Eval Benchmark")));
    }

    private ProductToolResult searchResultWithRows(List<Map<String, Object>> rows) {
        return new ProductToolResult(
                "search_paper_candidates",
                true,
                Map.of(
                        "status", "OK",
                        "items", rows,
                        "constraints", Map.of(
                                "previewIsSourceQuote", false,
                                "paperContentClaimsAllowed", false
                        )
                ),
                ProductToolEffect.PAPER_DISCOVERY
        );
    }

    private ProductToolResult sessionStateResult() {
        return new ProductToolResult(
                "get_session_state",
                true,
                Map.of(
                        "status", "OK",
                        "searchScope", Map.of(
                                "scopeMode", "AUTO_SOURCE",
                                "label", "All readable papers",
                                "readablePaperCountKnown", true,
                                "readablePaperCount", 2,
                                "immutable", true
                        ),
                        "constraints", Map.of(
                                "stateIsSourceQuote", false,
                                "paperContentClaimsAllowed", false
                        )
                ),
                ProductToolEffect.PRODUCT_STATE
        );
    }

    private ProductToolResult listPapersResult() {
        return new ProductToolResult(
                "list_papers",
                true,
                Map.of(
                        "status", "OK",
                        "total", 1,
                        "returned", 1,
                        "items", List.of(Map.of(
                                "ordinal", 1,
                                "paperId", "paper-raw",
                                "paperHandle", "paper_handle_abc",
                                "title", "Agentic Eval Benchmark",
                                "originalFilename", "agentic-eval.pdf",
                                "authors", List.of("Ada Lovelace"),
                                "year", 2025,
                                "venue", "NeurIPS",
                                "catalogTopics", List.of(),
                                "paperTypes", List.of()
                        )),
                        "facets", Map.of(),
                        "constraints", Map.of(
                                "paperCardIsSourceQuote", false,
                                "paperContentClaimsAllowed", false
                        )
                ),
                ProductToolEffect.PAPER_LIST
        );
    }

    private ProductToolResult identityResult() {
        return new ProductToolResult(
                "find_papers_by_identity",
                true,
                Map.of(
                        "status", "OK",
                        "ambiguous", false,
                        "total", 1,
                        "returned", 1,
                        "matches", List.of(Map.ofEntries(
                                Map.entry("ordinal", 1),
                                Map.entry("paperId", "paper-raw"),
                                Map.entry("paperHandle", "paper_handle_abc"),
                                Map.entry("title", "Agentic Eval Benchmark"),
                                Map.entry("originalFilename", "agentic-eval.pdf"),
                                Map.entry("authors", List.of("Ada Lovelace")),
                                Map.entry("year", 2025),
                                Map.entry("venue", "NeurIPS"),
                                Map.entry("matchReasons", List.of("TITLE_CONTAINS")),
                                Map.entry("catalogTopics", List.of()),
                                Map.entry("paperTypes", List.of())
                        )),
                        "constraints", Map.of(
                                "paperCardIsSourceQuote", false,
                                "paperContentClaimsAllowed", false,
                                "ambiguousMatchesAuthorizeReading", false
                        )
                ),
                ProductToolEffect.PAPER_RESOLUTION
        );
    }

    private ProductToolResult ruleArenaIdentityResult() {
        return new ProductToolResult(
                "find_papers_by_identity",
                true,
                Map.of(
                        "status", "OK",
                        "ambiguous", false,
                        "total", 1,
                        "returned", 1,
                        "matches", List.of(Map.ofEntries(
                                Map.entry("ordinal", 1),
                                Map.entry("paperId", "ready-rulearena"),
                                Map.entry("paperHandle", "paper_handle_rulearena"),
                                Map.entry("title", "RULEARENA: A Benchmark for Rule-Guided Reasoning with LLMs in Real-World Scenarios"),
                                Map.entry("originalFilename", "2412.08972.pdf"),
                                Map.entry("authors", List.of("Ada Lovelace")),
                                Map.entry("year", 2024),
                                Map.entry("venue", "arXiv"),
                                Map.entry("matchReasons", List.of("Filename exactly matches \"2412.08972.pdf\".")),
                                Map.entry("catalogTopics", List.of()),
                                Map.entry("paperTypes", List.of("benchmark"))
                        )),
                        "constraints", Map.of(
                                "paperCardIsSourceQuote", false,
                                "paperContentClaimsAllowed", false,
                                "ambiguousMatchesAuthorizeReading", false
                        )
                ),
                ProductToolEffect.PAPER_RESOLUTION
        );
    }

    private ProductToolResult ambiguousIdentityResult() {
        return new ProductToolResult(
                "find_papers_by_identity",
                true,
                Map.of(
                        "status", "AMBIGUOUS",
                        "ambiguous", true,
                        "total", 2,
                        "returned", 2,
                        "matches", List.of(
                                Map.ofEntries(
                                        Map.entry("ordinal", 1),
                                        Map.entry("paperId", "paper-raw"),
                                        Map.entry("paperHandle", "paper_handle_abc"),
                                        Map.entry("title", "Agentic Eval Benchmark"),
                                        Map.entry("originalFilename", "agentic-eval.pdf"),
                                        Map.entry("authors", List.of("Ada Lovelace")),
                                        Map.entry("year", 2025),
                                        Map.entry("venue", "NeurIPS"),
                                        Map.entry("matchReasons", List.of("AUTHOR_NAME", "YEAR")),
                                        Map.entry("catalogTopics", List.of()),
                                        Map.entry("paperTypes", List.of())
                                ),
                                Map.ofEntries(
                                        Map.entry("ordinal", 2),
                                        Map.entry("paperId", "paper-other"),
                                        Map.entry("paperHandle", "paper_handle_other"),
                                        Map.entry("title", "Agentic Eval Followup"),
                                        Map.entry("originalFilename", "agentic-followup.pdf"),
                                        Map.entry("authors", List.of("Ada Lovelace")),
                                        Map.entry("year", 2025),
                                        Map.entry("venue", "ICML"),
                                        Map.entry("matchReasons", List.of("AUTHOR_NAME", "YEAR")),
                                        Map.entry("catalogTopics", List.of()),
                                        Map.entry("paperTypes", List.of())
                                )
                        ),
                        "constraints", Map.of(
                                "paperCardIsSourceQuote", false,
                                "paperContentClaimsAllowed", false,
                                "ambiguousMatchesAuthorizeReading", false
                        )
                ),
                ProductToolEffect.PAPER_RESOLUTION
        );
    }

    private ProductToolResult identityResultWithMatches(String status,
                                                        boolean ambiguous,
                                                        List<Map<String, Object>> matches) {
        return new ProductToolResult(
                "find_papers_by_identity",
                true,
                Map.of(
                        "status", status,
                        "ambiguous", ambiguous,
                        "total", matches == null ? 0 : matches.size(),
                        "returned", matches == null ? 0 : matches.size(),
                        "matches", matches == null ? List.of() : matches
                ),
                ProductToolEffect.PAPER_RESOLUTION
        );
    }

    private ProductToolResult paperLevelResult(String toolName, List<Map<String, Object>> rows) {
        return new ProductToolResult(
                toolName,
                true,
                Map.of(
                        "status", rows == null || rows.isEmpty() ? "NO_MATCH" : "OK",
                        "items", rows == null ? List.of() : rows,
                        "facets", Map.of(),
                        "constraints", Map.of(
                                "paperCardIsSourceQuote", false,
                                "paperContentClaimsAllowed", false
                        )
                ),
                "list_papers".equals(toolName) ? ProductToolEffect.PAPER_LIST : ProductToolEffect.PAPER_DISCOVERY
        );
    }

    private Map<String, Object> paperChoiceRow(String paperHandle, String title) {
        return paperChoiceRow(paperHandle, title, "benchmark");
    }

    private Map<String, Object> paperChoiceRow(String paperHandle, String title, String role) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ordinal", 1);
        row.put("paperHandle", paperHandle);
        row.put("title", title);
        row.put("originalFilename", title.toLowerCase().replace(' ', '-') + ".pdf");
        row.put("authors", List.of("Ada Lovelace"));
        row.put("year", 2025);
        row.put("venue", "NeurIPS");
        row.put("preview", "not evidence");
        row.put("paperId", "paper-raw");
        row.put("score", 0.9);
        row.put("rank", 1);
        row.put("locationRef", "page_ref_hidden");
        row.put("sourceQuoteRef", "source_quote_hidden");
        row.put("catalogTopics", List.of("Agents"));
        row.put("paperTypes", List.of(role));
        row.put("facets", Map.of("years", List.of()));
        return row;
    }

    private Map<String, Object> identityMatch(String paperHandle, String title) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("ordinal", 1);
        match.put("paperHandle", paperHandle);
        match.put("title", title);
        match.put("originalFilename", title.toLowerCase().replace(' ', '-') + ".pdf");
        match.put("authors", List.of("Ada Lovelace"));
        match.put("year", 2025);
        match.put("venue", "NeurIPS");
        match.put("matchReasons", List.of("TITLE_CONTAINS"));
        match.put("paperId", "paper-raw");
        match.put("preview", "not evidence");
        match.put("score", 0.9);
        match.put("rank", 1);
        match.put("locationRef", "page_ref_hidden");
        match.put("sourceQuoteRef", "source_quote_hidden");
        return match;
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

    private ProductToolResult locationResultWithRows(List<Map<String, Object>> rows) {
        return new ProductToolResult(
                "find_reading_locations",
                true,
                Map.of(
                        "status", rows == null || rows.isEmpty() ? "NO_MATCH" : "OK",
                        "candidates", rows == null ? List.of() : rows,
                        "constraints", Map.of(
                                "previewIsSourceQuote", false,
                                "locationRefIsSourceQuote", false,
                                "paperContentClaimsAllowed", false
                        )
                ),
                ProductToolEffect.PAPER_DISCOVERY
        );
    }

    private Map<String, Object> locationRow(String locationRef,
                                            String paperHandle,
                                            String title,
                                            String preview) {
        return Map.of(
                "locationRef", locationRef,
                "paperHandle", paperHandle,
                "title", title,
                "locationType", "SECTION",
                "sectionTitle", "Abstract",
                "pageNumber", 1,
                "preview", preview
        );
    }

    private ProductToolResult locationNoMatchResult() {
        return new ProductToolResult(
                "find_reading_locations",
                true,
                Map.of(
                        "status", "NO_MATCH",
                        "candidates", List.of(),
                        "constraints", Map.of(
                                "previewIsSourceQuote", false,
                                "locationRefIsSourceQuote", false,
                                "paperContentClaimsAllowed", false,
                                "paperContentAbsenceClaimAllowed", false
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
                                "paperId", "paper-raw",
                                "paperVersion", "model-v1",
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

    private ProductToolResult readResultWithQuotes(List<Map<String, Object>> rows) {
        List<Map<String, Object>> readStatus = new ArrayList<>();
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            readStatus.add(Map.of(
                    "locationRef", row.get("locationRef"),
                    "status", "OK"
            ));
        }
        return new ProductToolResult(
                "read_locations",
                true,
                Map.of(
                        "sourceQuotes", rows == null ? List.of() : rows,
                        "readStatus", readStatus
                ),
                ProductToolEffect.EVIDENCE
        );
    }

    private Map<String, Object> quoteRow(String sourceQuoteRef,
                                         String paperId,
                                         String paperHandle,
                                         String locationRef,
                                         String paperTitle,
                                         String content) {
        return Map.ofEntries(
                Map.entry("sourceQuoteRef", sourceQuoteRef),
                Map.entry("paperId", paperId),
                Map.entry("paperVersion", "model-v1"),
                Map.entry("locationRef", locationRef),
                Map.entry("paperHandle", paperHandle),
                Map.entry("paperTitle", paperTitle),
                Map.entry("locationType", "SECTION"),
                Map.entry("pageNumber", 1),
                Map.entry("sectionTitle", "Abstract"),
                Map.entry("contentKind", "TEXT"),
                Map.entry("content", content)
        );
    }

    private ProductToolResult readResultWithoutPaperId() {
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
                        "sourceQuotes", List.of(Map.ofEntries(
                                Map.entry("sourceQuoteRef", "source_quote_abc"),
                                Map.entry("paperId", "paper-raw"),
                                Map.entry("paperVersion", "model-v1"),
                                Map.entry("locationRef", "section_ref_methods"),
                                Map.entry("paperHandle", "paper_handle_abc"),
                                Map.entry("paperTitle", "Agentic Eval Benchmark"),
                                Map.entry("locationType", "SECTION"),
                                Map.entry("pageNumber", 3),
                                Map.entry("sectionTitle", "Methods"),
                                Map.entry("contentKind", "TEXT"),
                                Map.entry("content", "The Methods section describes the evaluation setup.")
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
                                "paperId", "paper-raw",
                                "paperVersion", "model-v1",
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

    private List<ToolDefinition> readingTools() {
        return List.of(
                tool("get_session_state"),
                tool("list_papers"),
                tool("search_paper_candidates"),
                tool("find_papers_by_identity"),
                tool("get_paper_outline"),
                tool("list_paper_locations"),
                tool("find_reading_locations"),
                tool("read_locations"),
                tool("trace_source_quotes")
        );
    }

    private ToolDefinition tool(String name) {
        return new ToolDefinition(
                name,
                "tool",
                Map.of("type", "object", "additionalProperties", false)
        );
    }

    private String productStateEnvelope(String answer) {
        return productStateEnvelope(answer, "search_paper_candidates");
    }

    private String productStateEnvelope(String answer, String sourceTool) {
        return """
                {
                  "answerType": "PRODUCT_STATE",
                  "answer": "%s",
                  "evidenceBasedClaims": [],
                  "stateClaims": [
                    {
                      "claim": "%s",
                      "sourceTool": "%s"
                    }
                  ],
                  "limitations": ["source-quoted 推荐理由需要 read_locations / Source Quotes。"],
                  "nonEvidenceNotes": [],
                  "missingFields": [],
                  "reason": ""
                }
                """.formatted(answer, answer, sourceTool);
    }

    private String productStateEnvelopeWithRoleClaims() {
        return """
                {
                  "answerType": "PRODUCT_STATE",
                  "answer": "Start with the survey, then inspect AgentBench, then read the critique paper.",
                  "evidenceBasedClaims": [],
                  "stateClaims": [
                    {
                      "claim": "Agent Evaluation Survey is the map paper for a beginner shortlist.",
                      "sourceTool": "search_paper_candidates",
                      "paperHandle": "paper_handle_survey",
                      "beginnerRole": "survey",
                      "roleReason": "The title frames it as an evaluation survey."
                    },
                    {
                      "claim": "AgentBench is the benchmark example in the beginner shortlist.",
                      "sourceTool": "search_paper_candidates",
                      "paperHandle": "paper_handle_bench",
                      "beginnerRole": "benchmark",
                      "roleReason": "The title names a benchmark."
                    },
                    {
                      "claim": "AI Agents That Matter is the critique paper in the beginner shortlist.",
                      "sourceTool": "search_paper_candidates",
                      "paperHandle": "paper_handle_critique",
                      "beginnerRole": "critique",
                      "roleReason": "The title signals evaluation caveats and critique."
                    }
                  ],
                  "limitations": ["The roles are metadata-only until a passage is read."],
                  "nonEvidenceNotes": [],
                  "missingFields": [],
                  "reason": ""
                }
                """;
    }

    private String productStateEnvelopeWithUndisclosedRoleClaim() {
        return """
                {
                  "answerType": "PRODUCT_STATE",
                  "answer": "Start with the survey, then inspect AgentBench, then read the critique paper.",
                  "evidenceBasedClaims": [],
                  "stateClaims": [
                    {
                      "claim": "Agent Evaluation Survey is the map paper for a beginner shortlist.",
                      "sourceTool": "search_paper_candidates",
                      "paperHandle": "paper_handle_survey",
                      "beginnerRole": "survey",
                      "roleReason": "The title frames it as an evaluation survey."
                    },
                    {
                      "claim": "AgentBench is the benchmark example in the beginner shortlist.",
                      "sourceTool": "search_paper_candidates",
                      "paperHandle": "paper_handle_bench",
                      "beginnerRole": "benchmark",
                      "roleReason": "The title names a benchmark."
                    },
                    {
                      "claim": "A hidden paper is the critique paper in the beginner shortlist.",
                      "sourceTool": "search_paper_candidates",
                      "paperHandle": "paper_handle_hidden",
                      "beginnerRole": "critique",
                      "roleReason": "The title signals evaluation caveats and critique."
                    }
                  ],
                  "limitations": ["The roles are metadata-only until a passage is read."],
                  "nonEvidenceNotes": [],
                  "missingFields": [],
                  "reason": ""
                }
                """;
    }

    private String productStateEnvelopeWithInvalidRoleClaim() {
        return """
                {
                  "answerType": "PRODUCT_STATE",
                  "answer": "Start with the survey, then inspect AgentBench, then read the critique paper.",
                  "evidenceBasedClaims": [],
                  "stateClaims": [
                    {
                      "claim": "Agent Evaluation Survey is the map paper for a beginner shortlist.",
                      "sourceTool": "search_paper_candidates",
                      "paperHandle": "paper_handle_survey",
                      "beginnerRole": "map",
                      "roleReason": "The title frames it as an evaluation survey."
                    },
                    {
                      "claim": "AgentBench is the benchmark example in the beginner shortlist.",
                      "sourceTool": "search_paper_candidates",
                      "paperHandle": "paper_handle_bench",
                      "beginnerRole": "benchmark",
                      "roleReason": "The title names a benchmark."
                    },
                    {
                      "claim": "AI Agents That Matter is the critique paper in the beginner shortlist.",
                      "sourceTool": "search_paper_candidates",
                      "paperHandle": "paper_handle_critique",
                      "beginnerRole": "critique",
                      "roleReason": "The title signals evaluation caveats and critique."
                    }
                  ],
                  "limitations": ["The roles are metadata-only until a passage is read."],
                  "nonEvidenceNotes": [],
                  "missingFields": [],
                  "reason": ""
                }
                """;
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

    private LlmProviderRouter.ReActTurn multiToolCallTurn(LlmProviderRouter.ToolCallDecision... decisions) {
        List<LlmProviderRouter.ToolCallDecision> toolCallDecisions = List.of(decisions);
        List<Map<String, Object>> assistantToolCalls = new ArrayList<>();
        for (LlmProviderRouter.ToolCallDecision decision : toolCallDecisions) {
            assistantToolCalls.add(Map.of(
                    "id", decision.id(),
                    "type", "function",
                    "function", Map.of(
                            "name", decision.name(),
                            "arguments", argumentsJson(decision.arguments())
                    )
            ));
        }
        return new LlmProviderRouter.ReActTurn(
                "",
                toolCallDecisions,
                Map.of("role", "assistant", "content", "", "tool_calls", assistantToolCalls),
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

    private String argumentsJson(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private List<String> roles(List<Map<String, Object>> messages) {
        List<String> roles = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            roles.add(String.valueOf(message.get("role")));
        }
        return roles;
    }

}
