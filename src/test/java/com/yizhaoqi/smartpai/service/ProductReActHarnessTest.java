package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.yizhaoqi.smartpai.model.PaperConversationReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductReActHarnessTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path traceDir;

    @Test
    void productStateTurnCallsToolAndParsesFixedAnswerEnvelope() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("get_system_state"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("get_system_state"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "get_system_state",
                        true,
                        Map.of(
                                "productPaperCount", 2,
                                "searchablePaperCount", 2,
                                "papersByProcessingStatus", Map.of("AVAILABLE", 2)
                        ),
                        ProductToolEffect.PRODUCT_STATE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "get_system_state", Map.of("include", List.of("library"))))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "当前 session scope 内有 2 篇可检索论文。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [
                            {
                              "claim": "当前 session scope 内有 2 篇可检索论文。",
                              "sourceTool": "get_system_state"
                            }
                          ],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "现在有多少论文可以检索",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(ProductStopReason.COMPLETED, result.stopReason());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("2 篇可检索论文"));
        assertFalse(result.finalAnswerMarkdown().contains("Sources used"));
        assertTrue(result.references().isEmpty());
        assertEquals(List.of(new ToolProgressEvent("calling_tool", "get_system_state")), result.progressEvents());
        verify(toolRegistry).execute(eq("get_system_state"), any(), any());
    }

    @Test
    void writesAsyncDiskTraceForCompletedTurn() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("get_system_state"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("get_system_state"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "get_system_state",
                        true,
                        Map.of("searchablePaperCount", 2),
                        ProductToolEffect.PRODUCT_STATE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "get_system_state", Map.of()))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "当前 session scope 内有 2 篇可检索论文。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductTraceRecorder traceRecorder = new ProductTraceRecorder(objectMapper, traceDir);
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "现在有多少论文可以检索",
                SourceScope.auto(),
                List.of(),
                Map.of("userGoals", List.of("read papers")),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        Path traceFile = traceDir.resolve("conversation-conversation-1").resolve("turn-generation-1.json");
        awaitFile(traceFile);
        Map<?, ?> trace = objectMapper.readValue(traceFile.toFile(), Map.class);
        assertEquals("PRODUCT_REACT_TURN", trace.get("artifactType"));
        assertEquals("conversation-1", trace.get("conversationId"));
        assertEquals("generation-1", trace.get("generationId"));
        assertEquals("COMPLETED", trace.get("stopReason"));
        assertTrue(trace.containsKey("llmCalls"));
        assertTrue(trace.containsKey("toolCalls"));
        assertTrue(trace.containsKey("answerEnvelope"));

        assertTrue(traceRecorder.recordMemoryUpdate(
                "conversation-1",
                "generation-1",
                new ProductMemoryService.MemoryUpdateResult(
                        true,
                        Map.of("userGoals", List.of("read papers")),
                        Map.of("purpose", "MEMORY_COMPRESSION"),
                        ""
                )
        ));
        Path memoryTraceFile = awaitSingleMemoryTrace("conversation-1", "generation-1");
        Map<?, ?> memoryTrace = objectMapper.readValue(memoryTraceFile.toFile(), Map.class);
        assertEquals("PRODUCT_MEMORY_COMPRESSION", memoryTrace.get("artifactType"));
        assertEquals("conversation-1", memoryTrace.get("conversationId"));
        assertEquals("generation-1", memoryTrace.get("generationId"));
        assertTrue(memoryTrace.containsKey("memoryCompressionCall"));

        Map<?, ?> unchangedTurnTrace = objectMapper.readValue(traceFile.toFile(), Map.class);
        assertFalse(unchangedTurnTrace.containsKey("memoryCompressionCalls"));
    }

    @Test
    void memoryTraceDoesNotRequireExistingTurnTrace() throws Exception {
        ProductTraceRecorder traceRecorder = new ProductTraceRecorder(objectMapper, traceDir);

        assertTrue(traceRecorder.recordMemoryUpdate(
                "conversation-standalone",
                "generation-standalone",
                new ProductMemoryService.MemoryUpdateResult(
                        true,
                        Map.of("userGoals", List.of("read papers")),
                        Map.of("purpose", "MEMORY_COMPRESSION"),
                        ""
                )
        ));

        Path memoryTraceFile = awaitSingleMemoryTrace("conversation-standalone", "generation-standalone");
        Map<?, ?> memoryTrace = objectMapper.readValue(memoryTraceFile.toFile(), Map.class);
        assertEquals("PRODUCT_MEMORY_COMPRESSION", memoryTrace.get("artifactType"));
        assertEquals("conversation-standalone", memoryTrace.get("conversationId"));
        assertEquals("generation-standalone", memoryTrace.get("generationId"));
    }

    @Test
    void traceRecorderFailureDoesNotDegradeHarnessResult() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        ProductTraceRecorder traceRecorder = mock(ProductTraceRecorder.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("get_system_state"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("get_system_state"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "get_system_state",
                        true,
                        Map.of("searchablePaperCount", 2),
                        ProductToolEffect.PRODUCT_STATE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "get_system_state", Map.of()))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "当前 session scope 内有 2 篇可检索论文。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        when(traceRecorder.record(any(), any(), any(), any(), any(), any())).thenReturn(false);
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper, traceRecorder);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "现在有多少论文可以检索",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(ProductStopReason.COMPLETED, result.stopReason());
    }

    @Test
    void rejectsEvidenceAnswerWithoutValidEvidenceRefs() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("retrieve_evidence"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("retrieve_evidence"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "retrieve_evidence",
                        true,
                        Map.of(
                                "evidence", List.of(Map.of("evidenceRef", "ev_1", "paperTitle", "LoRA"))
                        ),
                        ProductToolEffect.EVIDENCE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "retrieve_evidence", Map.of("question", "LoRA 的方法是什么")))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "LoRA 使用低秩适配。",
                          "evidenceBasedClaims": [
                            {
                              "claim": "LoRA 使用低秩适配。",
                              "evidenceRefs": ["ev_missing"]
                            }
                          ],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "LoRA 的方法是什么",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.CITATION_VALIDATION_FAILED, result.stopReason());
    }

    @Test
    void rejectsProductStateAnswerWithoutProductStateTool() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        when(toolRegistry.listTools()).thenReturn(List.of(tool("get_system_state")));
        when(llm.completeReActTurn(eq("1"), any(), any(), anyInt()))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "当前有 2 篇可检索论文。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "现在有多少论文可以检索",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.ANSWER_SCHEMA_INVALID, result.stopReason());
    }

    @Test
    void requiresAProductToolBeforeAcceptingFinalAnswer() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("answer_without_product_state"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("answer_without_product_state"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "answer_without_product_state",
                        true,
                        Map.of("allowed", true, "reason", "smalltalk"),
                        ProductToolEffect.NO_PRODUCT_STATE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "NON_EVIDENCE",
                          "answer": "你好。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """))
                .thenReturn(toolCallTurn("call_1", "answer_without_product_state", Map.of(
                        "reason", "smalltalk",
                        "answerDraft", "你好。"
                )))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "NON_EVIDENCE",
                          "answer": "你好。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "你好",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.NON_EVIDENCE, result.envelope().answerType());
        assertEquals(List.of(new ToolProgressEvent("calling_tool", "answer_without_product_state")),
                result.progressEvents());
        verify(toolRegistry).execute(eq("answer_without_product_state"), any(), any());
        verify(llm, times(3)).completeReActTurn(eq("1"), any(), eq(tools), anyInt());
    }

    @Test
    void firstToolRetryPinsCurrentRequestInsteadOfHistoryOrMemory() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(
                tool("answer_without_product_state"),
                tool("get_session_scope"),
                tool("list_papers")
        );
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("answer_without_product_state"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "answer_without_product_state",
                        true,
                        Map.of("allowed", true, "reason", "current request needs no product state"),
                        ProductToolEffect.NO_PRODUCT_STATE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(finalTurn("你好。"))
                .thenReturn(toolCallTurn("call_1", "answer_without_product_state", Map.of(
                        "reason", "current request needs no product state",
                        "answerDraft", "你好。"
                )))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "NON_EVIDENCE",
                          "answer": "你好。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "hi",
                SourceScope.auto(),
                List.of(
                        Map.of("role", "user", "content", "有多少论文可以检索"),
                        Map.of("role", "assistant", "content", "当前锁定检索范围内可检索的论文数量为 0 篇。")
                ),
                Map.of("openQuestions", List.of("当前检索范围内为何没有可检索的论文？")),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.NON_EVIDENCE, result.envelope().answerType());
        verify(toolRegistry).execute(eq("answer_without_product_state"), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(3)).completeReActTurn(eq("1"), messagesCaptor.capture(), eq(tools), anyInt());
        String firstRoundMessages = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(0));
        String secondRoundMessages = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(1));
        assertTrue(firstRoundMessages.contains("<current_user_request>"));
        assertTrue(firstRoundMessages.contains("hi"));
        assertTrue(firstRoundMessages.contains("Earlier history and memory are context only"));
        assertTrue(firstRoundMessages.contains("must not change the intent of the current request"));
        assertTrue(secondRoundMessages.contains("current user request only, not for older history or memory"));
        assertTrue(secondRoundMessages.contains("Do not call product-state or evidence tools"));
    }

    @Test
    void repeatsToolCallCorrectionUntilModelUsesAProductFunction() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("find_papers"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("find_papers"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "find_papers",
                        true,
                        Map.of("total", 2, "papers", List.of()),
                        ProductToolEffect.PAPER_DISCOVERY
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(finalTurn("Here are the papers from memory."))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "Here are the papers from memory.",
                          "evidenceBasedClaims": [],
                          "stateClaims": [
                            {
                              "claim": "Memory listed papers.",
                              "sourceTool": "memory"
                            }
                          ],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """))
                .thenReturn(toolCallTurn("call_1", "find_papers", Map.of("query", "agent eval")))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "当前检索范围内有 2 篇相关论文。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [
                            {
                              "claim": "当前检索范围内有 2 篇相关论文。",
                              "sourceTool": "find_papers"
                            }
                          ],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "推荐一下和agent eval有关的论文",
                SourceScope.auto(),
                List.of(Map.of("role", "assistant", "content", "Here are older recommendations.")),
                Map.of("papersDiscussed", List.of("older paper")),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        verify(toolRegistry).execute(eq("find_papers"), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(4)).completeReActTurn(eq("1"), messagesCaptor.capture(), eq(tools), anyInt());
        String secondRoundMessages = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(1));
        String thirdRoundMessages = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(2));
        assertTrue(secondRoundMessages.contains("Your next response must contain tool_calls only"));
        assertTrue(thirdRoundMessages.contains("Your next response must contain tool_calls only"));
        assertTrue(thirdRoundMessages.contains("History and memory cannot ground the final answer"));
    }

    @Test
    void correctionAfterNoProductStateToolRequiresNonEvidenceEnvelope() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("answer_without_product_state"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("answer_without_product_state"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "answer_without_product_state",
                        true,
                        Map.of("allowed", true, "reason", "smalltalk", "answerDraft", "Hi."),
                        ProductToolEffect.NO_PRODUCT_STATE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "answer_without_product_state", Map.of(
                        "reason", "smalltalk",
                        "answerDraft", "Hi."
                )))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "Hi.",
                          "evidenceBasedClaims": [],
                          "stateClaims": [
                            {
                              "claim": "Smalltalk; no product state was retrieved.",
                              "sourceTool": "answer_without_product_state"
                            }
                          ],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "NON_EVIDENCE",
                          "answer": "Hi.",
                          "evidenceBasedClaims": [],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": ["Smalltalk response; no product state or paper evidence was used."],
                          "missingFields": [],
                          "reason": "Handled with answer_without_product_state."
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "hi",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.NON_EVIDENCE, result.envelope().answerType());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(3)).completeReActTurn(eq("1"), messagesCaptor.capture(), eq(tools), anyInt());
        String thirdRoundMessages = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(2));
        assertTrue(thirdRoundMessages.contains("NO_PRODUCT_STATE"));
        assertTrue(thirdRoundMessages.contains("NON_EVIDENCE"));
        assertTrue(thirdRoundMessages.contains("stateClaims"));
    }

    @Test
    void noProductStateToolDoesNotGroundProductStateAnswer() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("answer_without_product_state"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("answer_without_product_state"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "answer_without_product_state",
                        true,
                        Map.of("allowed", true, "reason", "smalltalk", "answerDraft", "Hi."),
                        ProductToolEffect.NO_PRODUCT_STATE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "answer_without_product_state", Map.of(
                        "reason", "smalltalk",
                        "answerDraft", "Hi."
                )))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "Hi.",
                          "evidenceBasedClaims": [],
                          "stateClaims": [
                            {
                              "claim": "Smalltalk; no product state was retrieved.",
                              "sourceTool": "answer_without_product_state"
                            }
                          ],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "Hi.",
                          "evidenceBasedClaims": [],
                          "stateClaims": [
                            {
                              "claim": "Smalltalk; no product state was retrieved.",
                              "sourceTool": "answer_without_product_state"
                            }
                          ],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "hi",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                new ProductModelContext(3, 1600)
        ));

        assertEquals(ProductResultStatus.FAILED, result.resultStatus());
        assertEquals(ProductStopReason.CITATION_VALIDATION_FAILED, result.stopReason());
    }

    @Test
    void retriesInvalidFinalTextAfterToolResultAndRequiresAnswerEnvelope() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("list_papers"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("list_papers"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "list_papers",
                        true,
                        Map.of("total", 2, "papers", List.of()),
                        ProductToolEffect.PAPER_LIST
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "list_papers", Map.of("status", "AVAILABLE")))
                .thenReturn(finalTurn("当前会话中，共有 2 篇论文可以检索。"))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "当前会话中，共有 2 篇论文可以检索。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [
                            {
                              "claim": "当前会话中，共有 2 篇论文可以检索。",
                              "sourceTool": "list_papers"
                            }
                          ],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "现在有多少论文可以检索",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(ProductStopReason.COMPLETED, result.stopReason());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        assertEquals("当前会话中，共有 2 篇论文可以检索。", result.finalAnswerMarkdown());
        verify(llm, times(3)).completeReActTurn(eq("1"), any(), eq(tools), anyInt());
    }

    @Test
    void retriesMixedMarkdownAndJsonSoStructuredAnswerStaysInsideEnvelope() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("find_papers"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("find_papers"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "find_papers",
                        true,
                        Map.of(
                                "total", 12,
                                "page", 1,
                                "pageSize", 50,
                                "papers", List.of(
                                        Map.of(
                                                "paperRef", "paper_survey",
                                                "title", "Evaluation and Benchmarking of LLM Agents: A Survey",
                                                "originalFilename", "2507.21504.pdf"
                                        ),
                                        Map.of(
                                                "paperRef", "paper_mcpeval",
                                                "title", "MCPEval: Automatic MCP-based Deep Evaluation for AI Agent Models",
                                                "originalFilename", "2507.12806.pdf"
                                        )
                                )
                        ),
                        ProductToolEffect.PAPER_DISCOVERY
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "find_papers", Map.of("query", "agent eval", "limit", 12)))
                .thenReturn(finalTurn("""
                        Here are papers in your library related to agent evaluation:

                        | # | Title | File |
                        |---|-------|------|
                        | 1 | Evaluation and Benchmarking of LLM Agents: A Survey | 2507.21504.pdf |

                        ```json
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "List of 12 papers from your library related to agent evaluation: Evaluation and Benchmarking of LLM Agents: A Survey (2507.21504.pdf); MCPEval: Automatic MCP-based Deep Evaluation for AI Agent Models (2507.12806.pdf). Recommendations: start with the survey.",
                          "evidenceBasedClaims": [],
                          "stateClaims": [
                            {
                              "claim": "Listed agent-evaluation-related papers.",
                              "sourceTool": "find_papers"
                            }
                          ],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        ```
                        """))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "Here are papers in your library related to agent evaluation:\\n\\n| # | Title | File |\\n|---|-------|------|\\n| 1 | Evaluation and Benchmarking of LLM Agents: A Survey | 2507.21504.pdf |\\n| 2 | MCPEval: Automatic MCP-based Deep Evaluation for AI Agent Models | 2507.12806.pdf |\\n\\nRecommended starting points:\\n- Start with the survey for the overview.\\n- Use MCPEval for MCP-based agent evaluation.",
                          "evidenceBasedClaims": [],
                          "stateClaims": [
                            {
                              "claim": "Listed agent-evaluation-related papers.",
                              "sourceTool": "find_papers"
                            }
                          ],
                          "limitations": [
                            "The list uses product metadata only; no paper content evidence was retrieved."
                          ],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "推荐一下和agent eval有关的论文",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("| # | Title | File |"));
        assertTrue(result.finalAnswerMarkdown().contains("Recommended starting points:"));
        assertFalse(result.finalAnswerMarkdown().startsWith("List of 12 papers"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(3)).completeReActTurn(eq("1"), messagesCaptor.capture(), eq(tools), anyInt());
        String thirdRoundMessages = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(2));
        assertTrue(thirdRoundMessages.contains("Re-output only one JSON object"));
        assertTrue(thirdRoundMessages.contains("answer field"));
        assertTrue(thirdRoundMessages.contains("stateClaims must be arrays of JSON objects"));
    }

    @Test
    void retriesIncompleteFinalJsonAfterToolResultAndRequiresAnswerType() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("list_papers"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("list_papers"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "list_papers",
                        true,
                        Map.of("total", 2, "papers", List.of()),
                        ProductToolEffect.PAPER_LIST
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "list_papers", Map.of("status", "AVAILABLE")))
                .thenReturn(finalTurn("""
                        {
                          "answer": "当前会话中，共有 2 篇论文可以检索。"
                        }
                        """))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "当前会话中，共有 2 篇论文可以检索。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [
                            {
                              "claim": "当前会话中，共有 2 篇论文可以检索。",
                              "sourceTool": "list_papers"
                            }
                          ],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "现在有多少论文可以检索",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        verify(llm, times(3)).completeReActTurn(eq("1"), any(), eq(tools), anyInt());
    }

    @Test
    void retriesNonEvidenceAnswerAfterProductStateToolResult() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("list_papers"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("list_papers"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "list_papers",
                        true,
                        Map.of("total", 2, "papers", List.of()),
                        ProductToolEffect.PAPER_LIST
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "list_papers", Map.of("status", "AVAILABLE")))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "NON_EVIDENCE",
                          "answer": "当前会话中，共有 2 篇论文可以检索。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "当前会话中，共有 2 篇论文可以检索。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [
                            {
                              "claim": "当前会话中，共有 2 篇论文可以检索。",
                              "sourceTool": "list_papers"
                            }
                          ],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "现在有多少论文可以检索",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        verify(llm, times(3)).completeReActTurn(eq("1"), any(), eq(tools), anyInt());
    }

    @Test
    void appendsToolResultPolicyBeforeNextReActRound() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("get_system_state"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("get_system_state"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "get_system_state",
                        true,
                        Map.of("searchablePaperCount", 2),
                        ProductToolEffect.PRODUCT_STATE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "get_system_state", Map.of("include", List.of("library"))))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "当前会话中有 2 篇论文可以检索。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [
                            {
                              "claim": "当前会话中有 2 篇论文可以检索。",
                              "sourceTool": "get_system_state"
                            }
                          ],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "现在有多少论文可以检索",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).completeReActTurn(eq("1"), messagesCaptor.capture(), eq(tools), anyInt());
        List<Map<String, Object>> secondRoundMessages = messagesCaptor.getAllValues().get(1);
        String serializedMessages = objectMapper.writeValueAsString(secondRoundMessages);
        assertTrue(serializedMessages.contains("If the current user request is already answered by the tool results"));
        assertTrue(serializedMessages.contains("This product-state result supports answerType PRODUCT_STATE"));
        assertTrue(serializedMessages.contains("Do not turn a product-state, scope, paper-count, paper-list, paper-resolution, or metadata request into a paper-content retrieval task"));
    }

    @Test
    void sessionScopePolicyDoesNotTreatAutoScopeAsSearchablePaperCount() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("get_session_scope"), tool("get_system_state"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("get_session_scope"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "get_session_scope",
                        true,
                        Map.of(
                                "scopeMode", "AUTO_SOURCE",
                                "scopeLocked", true,
                                "sourcePaperCountKnown", false
                        ),
                        ProductToolEffect.SESSION_SCOPE
                ));
        when(toolRegistry.execute(eq("get_system_state"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "get_system_state",
                        true,
                        Map.of("searchablePaperCount", 2),
                        ProductToolEffect.PRODUCT_STATE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "get_session_scope", Map.of()))
                .thenReturn(toolCallTurn("call_2", "get_system_state", Map.of("include", List.of("library"))))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "PRODUCT_STATE",
                          "answer": "当前有 2 篇论文可以检索。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [
                            {
                              "claim": "当前有 2 篇论文可以检索。",
                              "sourceTool": "get_system_state"
                            }
                          ],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "有多少论文可以检索",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.PRODUCT_STATE, result.envelope().answerType());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(3)).completeReActTurn(eq("1"), messagesCaptor.capture(), eq(tools), anyInt());
        String secondRoundMessages = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(1));
        assertTrue(secondRoundMessages.contains("does not provide the actual searchable paper count"));
        verify(toolRegistry).execute(eq("get_session_scope"), any(), any());
        verify(toolRegistry).execute(eq("get_system_state"), any(), any());
    }

    @Test
    void returnsVisibleLimitMessageWhenLastRoundStillRequestsAnotherTool() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("list_papers"), tool("retrieve_evidence"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("list_papers"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "list_papers",
                        true,
                        Map.of("total", 2, "papers", List.of()),
                        ProductToolEffect.PAPER_LIST
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "list_papers", Map.of("status", "AVAILABLE")))
                .thenReturn(toolCallTurn("call_2", "retrieve_evidence", Map.of("question", "extra content")));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "现在有多少论文可以检索",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                new ProductModelContext(2, 1600)
        ));

        assertEquals(ProductResultStatus.DEGRADED, result.resultStatus());
        assertEquals(ProductStopReason.MAX_REACT_ROUNDS, result.stopReason());
        assertEquals(AnswerType.CLARIFICATION_NEEDED, result.envelope().answerType());
        assertTrue(result.finalAnswerMarkdown().contains("需要缩小问题或继续新一轮"));
        verify(toolRegistry).execute(eq("list_papers"), any(), any());
        verify(toolRegistry, never()).execute(eq("retrieve_evidence"), any(), any());
    }

    @Test
    void persistsReferenceRegistryForValidEvidenceAnswer() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("retrieve_evidence"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("retrieve_evidence"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "retrieve_evidence",
                        true,
                        Map.of(
                                "evidence", List.of(Map.of(
                                        "evidenceRef", "ev_1",
                                        "paperRef", "paper_1",
                                        "paperTitle", "LoRA",
                                        "pageNumber", 3,
                                        "snippet", "Low-rank adaptation"
                                ))
                        ),
                        ProductToolEffect.EVIDENCE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "retrieve_evidence", Map.of("question", "LoRA 的方法是什么")))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "LoRA 使用低秩适配。",
                          "evidenceBasedClaims": [
                            {
                              "claim": "LoRA 使用低秩适配。",
                              "evidenceRefs": ["ev_1"]
                            }
                          ],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper, null, referenceRegistry);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "LoRA 的方法是什么",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(1, result.references().size());
        ArgumentCaptor<ConversationReferenceRegistry.ReferenceInput> captor =
                ArgumentCaptor.forClass(ConversationReferenceRegistry.ReferenceInput.class);
        verify(referenceRegistry, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        ConversationReferenceRegistry.ReferenceInput saved = captor.getAllValues().stream()
                .filter(input -> "ev_1".equals(input.refId()))
                .findFirst()
                .orElseThrow();
        assertEquals("conversation-1", saved.conversationId());
        assertEquals("generation-1", saved.turnId());
        assertEquals("ev_1", saved.refId());
        assertEquals(PaperConversationReference.RefType.EVIDENCE, saved.refType());
    }

    @Test
    void rejectsAnswerWithoutProductStateAfterEvidenceToolAndContinuesToFinalEnvelope() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(
                tool("retrieve_evidence"),
                tool("answer_without_product_state")
        );
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("retrieve_evidence"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "retrieve_evidence",
                        true,
                        Map.of(
                                "evidence", List.of(Map.of(
                                        "evidenceRef", "E1",
                                        "paperRef", "paper_lora",
                                        "paperTitle", "LoRA",
                                        "pageNumber", 1,
                                        "snippet", "LoRA freezes pretrained weights and injects trainable low-rank matrices."
                                ))
                        ),
                        ProductToolEffect.EVIDENCE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "retrieve_evidence", Map.of("question", "LoRA 的核心方法是什么")))
                .thenReturn(toolCallTurn("call_2", "answer_without_product_state", Map.of("reason", "already_have_answer")))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "LoRA 的核心方法是冻结预训练权重，并注入可训练的低秩矩阵 {{evidenceRef:E1}}。",
                          "evidenceBasedClaims": [
                            {
                              "claim": "LoRA 冻结预训练权重，并注入可训练的低秩矩阵。",
                              "evidenceRefs": ["E1"]
                            }
                          ],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper, null, referenceRegistry);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "LoRA 的核心方法是什么",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.EVIDENCE_ANSWER, result.envelope().answerType());
        assertEquals("LoRA 的核心方法是冻结预训练权重，并注入可训练的低秩矩阵 [1]。", result.finalAnswerMarkdown());
        assertEquals(List.of(new ToolProgressEvent("calling_tool", "retrieve_evidence")), result.progressEvents());
        verify(toolRegistry).execute(eq("retrieve_evidence"), any(), any());
        verify(toolRegistry, never()).execute(eq("answer_without_product_state"), any(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(3)).completeReActTurn(eq("1"), messagesCaptor.capture(), eq(tools), anyInt());
        String thirdRoundMessages = objectMapper.writeValueAsString(messagesCaptor.getAllValues().get(2));
        assertTrue(thirdRoundMessages.contains("tool_call_rejected"));
        assertTrue(thirdRoundMessages.contains("answer_without_product_state"));
    }

    @Test
    void rejectsCurrentTurnEvidenceInspectReferenceAndRecordsDecisionInTrace() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        ProductTraceRecorder traceRecorder = new ProductTraceRecorder(objectMapper, traceDir);
        List<AgentToolRegistry.AgentTool> tools = List.of(
                tool("retrieve_evidence"),
                tool("inspect_reference")
        );
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("retrieve_evidence"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "retrieve_evidence",
                        true,
                        Map.of(
                                "evidence", List.of(Map.of(
                                        "evidenceRef", "E1",
                                        "paperRef", "paper_lora",
                                        "paperTitle", "LoRA",
                                        "pageNumber", 1,
                                        "snippet", "LoRA freezes pretrained weights and injects trainable low-rank matrices."
                                ))
                        ),
                        ProductToolEffect.EVIDENCE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "retrieve_evidence", Map.of("question", "LoRA 的核心方法是什么")))
                .thenReturn(toolCallTurn("call_2", "inspect_reference", Map.of("evidenceRef", "E1")))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "LoRA 的核心方法是冻结预训练权重，并注入可训练的低秩矩阵 {{evidenceRef:E1}}。",
                          "evidenceBasedClaims": [
                            {
                              "claim": "LoRA 冻结预训练权重，并注入可训练的低秩矩阵。",
                              "evidenceRefs": ["E1"]
                            }
                          ],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(
                llm,
                toolRegistry,
                objectMapper,
                traceRecorder,
                referenceRegistry
        );

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "LoRA 的核心方法是什么",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(List.of(new ToolProgressEvent("calling_tool", "retrieve_evidence")), result.progressEvents());
        verify(toolRegistry).execute(eq("retrieve_evidence"), any(), any());
        verify(toolRegistry, never()).execute(eq("inspect_reference"), any(), any());

        Path traceFile = traceDir.resolve("conversation-conversation-1").resolve("turn-generation-1.json");
        awaitFile(traceFile);
        Map<?, ?> trace = objectMapper.readValue(traceFile.toFile(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) trace.get("toolCalls");
        Map<String, Object> rejectedInspect = toolCalls.stream()
                .filter(call -> "inspect_reference".equals(call.get("toolName")))
                .findFirst()
                .orElseThrow();
        assertEquals(false, rejectedInspect.get("executed"));
        assertEquals(true, rejectedInspect.get("rejected"));
        assertFalse(objectMapper.writeValueAsString(rejectedInspect).contains("reference_not_found"));
    }

    @Test
    void rejectsNonOpaquePaperRefBeforeRetrieveEvidenceAndRecordsTraceDiagnostic() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        ProductTraceRecorder traceRecorder = new ProductTraceRecorder(objectMapper, traceDir);
        List<AgentToolRegistry.AgentTool> tools = List.of(
                tool("resolve_papers"),
                tool("retrieve_evidence")
        );
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("resolve_papers"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "resolve_papers",
                        true,
                        Map.of(
                                "resolved", true,
                                "total", 1,
                                "papers", List.of(Map.of(
                                        "paperRef", "paper_dfc0e064a5ba42bf",
                                        "title", "Evaluation and Benchmarking of LLM Agents: A Survey",
                                        "originalFilename", "2507.21504.pdf"
                                ))
                        ),
                        ProductToolEffect.PAPER_RESOLUTION
                ));
        when(toolRegistry.execute(eq("retrieve_evidence"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "retrieve_evidence",
                        true,
                        Map.of(
                                "evidence", List.of(Map.of(
                                        "evidenceRef", "E1",
                                        "paperRef", "paper_dfc0e064a5ba42bf",
                                        "paperTitle", "Evaluation and Benchmarking of LLM Agents: A Survey",
                                        "pageNumber", 2,
                                        "matchedText", "This survey evaluates LLM agents."
                                ))
                        ),
                        ProductToolEffect.EVIDENCE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_bad", "retrieve_evidence", Map.of(
                        "question", "详细介绍这篇 agent eval survey",
                        "paperConstraints", List.of(Map.of("paperRef", "2507.21504"))
                )))
                .thenReturn(toolCallTurn("call_resolve", "resolve_papers", Map.of(
                        "userMention", "Evaluation and Benchmarking of LLM Agents: A Survey 2507.21504"
                )))
                .thenReturn(toolCallTurn("call_good", "retrieve_evidence", Map.of(
                        "question", "详细介绍 Evaluation and Benchmarking of LLM Agents: A Survey",
                        "paperConstraints", List.of(Map.of("paperRef", "paper_dfc0e064a5ba42bf"))
                )))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "这篇综述讨论 LLM agent 的评估与基准 {{evidenceRef:E1}}。",
                          "evidenceBasedClaims": [
                            {
                              "claim": "这篇综述讨论 LLM agent 的评估与基准。",
                              "evidenceRefs": ["E1"]
                            }
                          ],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(
                llm,
                toolRegistry,
                objectMapper,
                traceRecorder,
                referenceRegistry
        );

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "详细介绍一下 Evaluation and Benchmarking of LLM Agents: A Survey",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.EVIDENCE_ANSWER, result.envelope().answerType());
        assertEquals(List.of(
                new ToolProgressEvent("calling_tool", "resolve_papers"),
                new ToolProgressEvent("calling_tool", "retrieve_evidence")
        ), result.progressEvents());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> retrieveArgsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(toolRegistry, times(1)).execute(eq("retrieve_evidence"), retrieveArgsCaptor.capture(), any());
        String executedRetrieveArgs = objectMapper.writeValueAsString(retrieveArgsCaptor.getValue());
        assertTrue(executedRetrieveArgs.contains("paper_dfc0e064a5ba42bf"));
        assertFalse(executedRetrieveArgs.contains("2507.21504"));

        Path traceFile = traceDir.resolve("conversation-conversation-1").resolve("turn-generation-1.json");
        awaitFile(traceFile);
        Map<?, ?> trace = objectMapper.readValue(traceFile.toFile(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) trace.get("toolCalls");
        Map<String, Object> rejectedRetrieve = toolCalls.stream()
                .filter(call -> "retrieve_evidence".equals(call.get("toolName")))
                .filter(call -> Boolean.TRUE.equals(call.get("rejected")))
                .findFirst()
                .orElseThrow();
        assertEquals(false, rejectedRetrieve.get("executed"));
        String traceJson = objectMapper.writeValueAsString(trace);
        assertTrue(traceJson.contains("non_opaque_paper_ref_in_tool_args"));
        assertTrue(traceJson.contains("use resolve_papers"));
    }

    @Test
    void unresolvedPaperConstraintDoesNotGroundInsufficientEvidenceAnswer() throws Exception {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        ProductTraceRecorder traceRecorder = new ProductTraceRecorder(objectMapper, traceDir);
        List<AgentToolRegistry.AgentTool> tools = List.of(
                tool("resolve_papers"),
                tool("retrieve_evidence")
        );
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("retrieve_evidence"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "retrieve_evidence",
                        true,
                        Map.of(
                                "sourceCount", 0,
                                "papers", List.of(),
                                "evidence", List.of(),
                                "missingPaperRefs", List.of("paper_missing"),
                                "reason", "unresolved_paper_constraints"
                        ),
                        ProductToolEffect.EVIDENCE
                ))
                .thenReturn(new ProductToolResult(
                        "retrieve_evidence",
                        true,
                        Map.of(
                                "evidence", List.of(Map.of(
                                        "evidenceRef", "E1",
                                        "paperRef", "paper_dfc0e064a5ba42bf",
                                        "paperTitle", "Evaluation and Benchmarking of LLM Agents: A Survey",
                                        "pageNumber", 2,
                                        "matchedText", "This survey evaluates LLM agents."
                                ))
                        ),
                        ProductToolEffect.EVIDENCE
                ));
        when(toolRegistry.execute(eq("resolve_papers"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "resolve_papers",
                        true,
                        Map.of(
                                "resolved", true,
                                "total", 1,
                                "papers", List.of(Map.of(
                                        "paperRef", "paper_dfc0e064a5ba42bf",
                                        "title", "Evaluation and Benchmarking of LLM Agents: A Survey",
                                        "originalFilename", "2507.21504.pdf"
                                ))
                        ),
                        ProductToolEffect.PAPER_RESOLUTION
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_unresolved", "retrieve_evidence", Map.of(
                        "question", "详细介绍 Evaluation and Benchmarking of LLM Agents: A Survey",
                        "paperConstraints", List.of(Map.of("paperRef", "paper_missing"))
                )))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "INSUFFICIENT_EVIDENCE",
                          "answer": "无法在当前可访问的论文全文中解析到该论文。",
                          "evidenceBasedClaims": [],
                          "stateClaims": [],
                          "limitations": ["unresolved paper ref"],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """))
                .thenReturn(toolCallTurn("call_resolve", "resolve_papers", Map.of(
                        "userMention", "Evaluation and Benchmarking of LLM Agents: A Survey"
                )))
                .thenReturn(toolCallTurn("call_good", "retrieve_evidence", Map.of(
                        "question", "详细介绍 Evaluation and Benchmarking of LLM Agents: A Survey",
                        "paperConstraints", List.of(Map.of("paperRef", "paper_dfc0e064a5ba42bf"))
                )))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "这篇综述讨论 LLM agent 的评估与基准 {{evidenceRef:E1}}。",
                          "evidenceBasedClaims": [
                            {
                              "claim": "这篇综述讨论 LLM agent 的评估与基准。",
                              "evidenceRefs": ["E1"]
                            }
                          ],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(
                llm,
                toolRegistry,
                objectMapper,
                traceRecorder,
                referenceRegistry
        );

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "详细介绍一下 Evaluation and Benchmarking of LLM Agents: A Survey",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(AnswerType.EVIDENCE_ANSWER, result.envelope().answerType());
        assertFalse(result.finalAnswerMarkdown().contains("无法在当前可访问的论文全文中解析到该论文"));
        verify(toolRegistry).execute(eq("resolve_papers"), any(), any());

        Path traceFile = traceDir.resolve("conversation-conversation-1").resolve("turn-generation-1.json");
        awaitFile(traceFile);
        String traceJson = Files.readString(traceFile);
        assertTrue(traceJson.contains("tool_result_unresolved_paper_ref"));
    }

    @Test
    void harnessGeneratesFinalCitationNumbersFromEvidenceMarkers() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("retrieve_evidence"));
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("retrieve_evidence"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "retrieve_evidence",
                        true,
                        Map.of(
                                "evidence", List.of(Map.of(
                                        "evidenceRef", "ev_lora_method",
                                        "paperRef", "paper_lora",
                                        "paperId", "paper-raw-hidden-from-llm-contract",
                                        "paperTitle", "LoRA",
                                        "pageNumber", 3,
                                        "snippet", "Low-rank adaptation freezes the pretrained weights."
                                ))
                        ),
                        ProductToolEffect.EVIDENCE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "retrieve_evidence", Map.of("question", "LoRA 的方法是什么")))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "LoRA 冻结预训练权重，只训练低秩适配矩阵 {{evidenceRef:ev_lora_method}}。",
                          "evidenceBasedClaims": [
                            {
                              "claim": "LoRA 冻结预训练权重，只训练低秩适配矩阵。",
                              "evidenceRefs": ["ev_lora_method"]
                            }
                          ],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper, null, referenceRegistry);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "LoRA 的方法是什么",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals("LoRA 冻结预训练权重，只训练低秩适配矩阵 [1]。", result.finalAnswerMarkdown());
        assertEquals(1, result.references().size());
        assertEquals(1, result.references().get(0).get("referenceNumber"));
        assertEquals("citation_generation-1_1", result.references().get(0).get("citationRef"));
        assertEquals("ev_lora_method", result.references().get(0).get("evidenceRef"));

        ArgumentCaptor<ConversationReferenceRegistry.ReferenceInput> captor =
                ArgumentCaptor.forClass(ConversationReferenceRegistry.ReferenceInput.class);
        verify(referenceRegistry, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        List<ConversationReferenceRegistry.ReferenceInput> saved = captor.getAllValues();
        assertTrue(saved.stream().anyMatch(input ->
                "citation_generation-1_1".equals(input.refId())
                        && input.refType() == PaperConversationReference.RefType.CITATION));
        assertTrue(saved.stream().anyMatch(input ->
                "ev_lora_method".equals(input.refId())
                        && input.refType() == PaperConversationReference.RefType.EVIDENCE));
    }

    @Test
    void citationRenderingAcceptsEvidencePayloadsWithNullFields() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        ProductToolRegistry toolRegistry = mock(ProductToolRegistry.class);
        ConversationReferenceRegistry referenceRegistry = mock(ConversationReferenceRegistry.class);
        List<AgentToolRegistry.AgentTool> tools = List.of(tool("retrieve_evidence"));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evidenceRef", "E1");
        evidence.put("paperRef", "paper_lora");
        evidence.put("paperTitle", "LoRA");
        evidence.put("pageNumber", 1);
        evidence.put("matchedText", "LoRA freezes weights and injects trainable low-rank matrices.");
        evidence.put("tableText", null);
        evidence.put("figureId", null);
        when(toolRegistry.listTools()).thenReturn(tools);
        when(toolRegistry.execute(eq("retrieve_evidence"), any(), any()))
                .thenReturn(new ProductToolResult(
                        "retrieve_evidence",
                        true,
                        Map.of("evidence", List.of(evidence)),
                        ProductToolEffect.EVIDENCE
                ));
        when(llm.completeReActTurn(eq("1"), any(), eq(tools), anyInt()))
                .thenReturn(toolCallTurn("call_1", "retrieve_evidence", Map.of("question", "LoRA 的方法是什么")))
                .thenReturn(finalTurn("""
                        {
                          "answerType": "EVIDENCE_ANSWER",
                          "answer": "LoRA 的核心方法是冻结预训练权重，并注入可训练的低秩矩阵。",
                          "evidenceBasedClaims": [
                            {
                              "claim": "LoRA 冻结预训练权重，并注入可训练的低秩矩阵。",
                              "evidenceRefs": ["E1"]
                            }
                          ],
                          "stateClaims": [],
                          "limitations": [],
                          "nonEvidenceNotes": [],
                          "missingFields": [],
                          "reason": ""
                        }
                        """));
        ProductReActHarness harness = new ProductReActHarness(llm, toolRegistry, objectMapper, null, referenceRegistry);

        ProductTurnResult result = harness.run(new ProductTurnRequest(
                1L,
                "conversation-1",
                "generation-1",
                "LoRA 的方法是什么",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        ));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(ProductStopReason.COMPLETED, result.stopReason());
        assertTrue(result.finalAnswerMarkdown().contains("[1]"));
        assertEquals(1, result.references().size());
        assertTrue(result.references().get(0).containsKey("tableText"));
        assertEquals(null, result.references().get(0).get("tableText"));
    }

    private AgentToolRegistry.AgentTool tool(String name) {
        return new AgentToolRegistry.AgentTool(
                name,
                "tool " + name,
                Map.of("type", "object", "properties", Map.of(), "required", List.of())
        );
    }

    private LlmProviderRouter.ReActTurn toolCallTurn(String id, String name, Map<String, Object> arguments) {
        Map<String, Object> assistantMessage = new LinkedHashMap<>();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", "");
        assistantMessage.put("tool_calls", List.of(Map.of(
                "id", id,
                "type", "function",
                "function", Map.of("name", name, "arguments", "{}")
        )));
        return new LlmProviderRouter.ReActTurn(
                "",
                List.of(new LlmProviderRouter.ToolCallDecision(id, name, arguments)),
                assistantMessage,
                "tool_calls",
                20,
                5
        );
    }

    private LlmProviderRouter.ReActTurn finalTurn(String content) {
        return new LlmProviderRouter.ReActTurn(
                content,
                List.of(),
                Map.of("role", "assistant", "content", content),
                "stop",
                20,
                20
        );
    }

    private void awaitFile(Path path) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path)) {
                return;
            }
            Thread.sleep(25);
        }
        fail("file was not written: " + path);
    }

    private Path awaitSingleMemoryTrace(String conversationId, String generationId) throws Exception {
        Path directory = traceDir.resolve("conversation-" + conversationId);
        String prefix = "turn-" + generationId + ".memory-";
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(directory)) {
                try (var paths = Files.list(directory)) {
                    List<Path> matches = paths
                            .filter(path -> {
                                String filename = path.getFileName().toString();
                                return filename.startsWith(prefix) && filename.endsWith(".json");
                            })
                            .toList();
                    if (!matches.isEmpty()) {
                        return matches.get(0);
                    }
                }
            }
            Thread.sleep(25);
        }
        fail("memory trace was not written for " + conversationId + "/" + generationId);
        return directory.resolve(prefix + "missing.json");
    }
}
