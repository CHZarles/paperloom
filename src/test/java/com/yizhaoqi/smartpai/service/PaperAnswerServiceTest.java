package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.exception.RateLimitExceededException;
import com.yizhaoqi.smartpai.model.Paper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaperAnswerServiceTest {

    private PaperRetrievalService retrievalService;
    private PaperService paperService;
    private ConversationService conversationService;
    private LlmProviderRouter llmProviderRouter;
    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOps;
    private PaperAnswerService service;

    @BeforeEach
    void setUp() {
        retrievalService = mock(PaperRetrievalService.class);
        paperService = mock(PaperService.class);
        conversationService = mock(ConversationService.class);
        llmProviderRouter = mock(LlmProviderRouter.class);
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new PaperAnswerService(
                retrievalService,
                paperService,
                conversationService,
                llmProviderRouter,
                redisTemplate,
                new ObjectMapper()
        );
    }

    @Test
    void smalltalkRoutedByTaskRouterDoesNotRetrieve() {
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(smalltalkRoute());

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "hi");

        assertEquals(PaperAnswerService.Intent.SMALLTALK, answer.intent());
        assertEquals(0, answer.referenceMappings().size());
        assertTrue(answer.markdown().contains("我在"));
        verifyNoInteractions(retrievalService);
    }

    @Test
    void recommendationGroupsChunksByPaperAfterPlannerChoosesDiscovery() {
        plannerReturns(PlannerActionType.DISCOVER_PAPERS, "agent");
        when(retrievalService.discoverPapers(anyString(), eq("u1"), any(RetrievalBudget.class))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Agent Harness evidence one", 0.9),
                result("paper-a", 2, "Agent Harness evidence two", 0.8),
                result("paper-a", 3, "Agent Harness evidence three", 0.7)
        )));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "推荐一下和 agent 相关的论文");

        assertEquals(PaperAnswerService.Intent.LIBRARY_SEARCH, answer.intent());
        assertEquals(1, answer.uniquePaperCount());
        assertEquals(1, answer.referenceMappings().size());
        assertTrue(answer.markdown().contains("只找到 1 篇"));
        assertTrue(answer.markdown().contains("《Title paper-a》"));
        assertTrue(answer.markdown().contains("[1]"));
    }

    @Test
    void recommendationKeywordWithoutPaperStillUsesDiscoveryWhenPlannerChoosesIt() {
        plannerReturns(PlannerActionType.DISCOVER_PAPERS, "grep");
        when(retrievalService.discoverPapers(eq("grep"), eq("u1"), any(RetrievalBudget.class))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "grep can be used by agent harnesses as a native bash search primitive.", 0.9)
        )));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "推荐一些grep");

        assertEquals(PaperAnswerService.Intent.LIBRARY_SEARCH, answer.intent());
        assertTrue(answer.markdown().contains("《Title paper-a》"));
    }

    @Test
    void nonPaperSystemQuestionClarifiesWithoutRetrieval() {
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(clarifyRoute());

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "现在的session id是什么");

        assertEquals(PaperAnswerService.Intent.CLARIFY, answer.intent());
        assertEquals(0, answer.referenceMappings().size());
        assertTrue(answer.markdown().contains("论文"));
        verifyNoInteractions(retrievalService);
    }

    @Test
    void taskRoutingFailureDoesNotRetrieveOrRenderEvidence() {
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(turn("not json"));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "介绍一下 LoRA");

        assertEquals(PaperAnswerService.Intent.CLARIFY, answer.intent());
        assertEquals("ROUTING_FAILURE", answer.diagnostics().route());
        assertEquals(0, answer.referenceMappings().size());
        assertEquals(0, answer.evidenceCount());
        assertFalse(answer.fallbackUsed());
        verifyNoInteractions(retrievalService);
    }

    @Test
    void libraryStatusAnswersFromMetadataWithoutRetrievalOrCitations() {
        TaskRouter taskRouter = request -> TaskRoutingResult.routed(new TaskDecision(
                TaskType.LIBRARY_STATUS,
                TaskOperation.COUNT_SEARCHABLE_PAPERS,
                "",
                0.95d,
                "count searchable papers"
        ));
        PaperLibraryStatusService libraryStatusService = mock(PaperLibraryStatusService.class);
        when(libraryStatusService.statusFor(eq("u1"), any(SourceScope.class)))
                .thenReturn(new PaperLibraryStatus(
                        3,
                        2,
                        0,
                        0,
                        1,
                        2,
                        List.of()
                ));
        PaperAnswerService statusService = new PaperAnswerService(
                retrievalService,
                paperService,
                conversationService,
                llmProviderRouter,
                redisTemplate,
                new ObjectMapper(),
                new PaperChatRouter(),
                new EvidencePlanner(llmProviderRouter, new ObjectMapper()),
                new EvidenceLedgerService(),
                new EvidenceAnswerGenerator(llmProviderRouter, new EvidenceVerifier()),
                new EvidenceVerifier(),
                new EvidenceToolExecutor(retrievalService, paperService, conversationService, new EvidenceLedgerService()),
                taskRouter,
                libraryStatusService
        );

        PaperAnswerService.AnswerResult answer = statusService.answer("u1", "c1", "有多少论文可以检索");

        assertEquals(PaperAnswerService.Intent.LIBRARY_STATUS, answer.intent());
        assertEquals("LIBRARY_STATUS", answer.diagnostics().route());
        assertEquals(0, answer.referenceMappings().size());
        assertEquals(0, answer.evidenceCount());
        assertTrue(answer.markdown().contains("2 篇"));
        verifyNoInteractions(retrievalService);
    }

    @Test
    void libraryStatusStoresSearchablePapersForFollowUp() {
        TaskRouter taskRouter = request -> TaskRoutingResult.routed(new TaskDecision(
                TaskType.LIBRARY_STATUS,
                TaskOperation.COUNT_SEARCHABLE_PAPERS,
                "",
                0.95d,
                "count searchable papers"
        ));
        PaperLibraryStatusService libraryStatusService = mock(PaperLibraryStatusService.class);
        when(libraryStatusService.statusFor(eq("u1"), any(SourceScope.class)))
                .thenReturn(new PaperLibraryStatus(
                        2,
                        2,
                        0,
                        0,
                        0,
                        2,
                        List.of(),
                        List.of(
                                new PaperSource("paper-a", "LoRA", "lora.pdf"),
                                new PaperSource("paper-b", "Attention Is All You Need", "attention.pdf")
                        )
                ));
        PaperAnswerService statusService = new PaperAnswerService(
                retrievalService,
                paperService,
                conversationService,
                llmProviderRouter,
                redisTemplate,
                new ObjectMapper(),
                new PaperChatRouter(),
                new EvidencePlanner(llmProviderRouter, new ObjectMapper()),
                new EvidenceLedgerService(),
                new EvidenceAnswerGenerator(llmProviderRouter, new EvidenceVerifier()),
                new EvidenceVerifier(),
                new EvidenceToolExecutor(retrievalService, paperService, conversationService, new EvidenceLedgerService()),
                taskRouter,
                libraryStatusService
        );

        statusService.answer("u1", "c1", "有多少论文可以检索");

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("paperloom:chat:focus:c1"), json.capture(), any(Duration.class));
        assertTrue(json.getValue().contains("\"lastIntent\":\"LIBRARY_STATUS\""));
        assertTrue(json.getValue().contains("paper-a"));
        assertTrue(json.getValue().contains("paper-b"));
        assertTrue(json.getValue().contains("LoRA"));
        assertTrue(json.getValue().contains("Attention Is All You Need"));
        verifyNoInteractions(retrievalService);
    }

    @Test
    void libraryStatusFollowUpPassesConversationHistoryToTaskRouterAndListsPapers() {
        when(valueOps.get("paperloom:chat:focus:c1")).thenReturn("""
                {"lastIntent":"LIBRARY_STATUS","lastPaperIds":["paper-a","paper-b"],"lastPaperTitles":["LoRA","Attention Is All You Need"]}
                """);
        when(conversationService.getMessagesByConversationId(1L, "c1")).thenReturn(List.of(
                Map.of("role", "user", "content", "现在有多少参考论文"),
                Map.of("role", "assistant", "content", "**结论**\n当前可检索论文有 2 篇。")
        ));
        AtomicReference<TaskRoutingRequest> capturedRequest = new AtomicReference<>();
        TaskRouter taskRouter = request -> {
            capturedRequest.set(request);
            return TaskRoutingResult.routed(new TaskDecision(
                    TaskType.LIBRARY_STATUS,
                    TaskOperation.LIST_ACCESSIBLE_PAPERS,
                    "",
                    0.95d,
                    "list previous library status paper set"
            ));
        };
        PaperLibraryStatusService libraryStatusService = mock(PaperLibraryStatusService.class);
        when(libraryStatusService.statusFor(eq("1"), any(SourceScope.class)))
                .thenReturn(new PaperLibraryStatus(
                        2,
                        2,
                        0,
                        0,
                        0,
                        2,
                        List.of(),
                        List.of(
                                new PaperSource("paper-a", "LoRA", "lora.pdf"),
                                new PaperSource("paper-b", "Attention Is All You Need", "attention.pdf")
                        )
                ));
        PaperAnswerService statusService = new PaperAnswerService(
                retrievalService,
                paperService,
                conversationService,
                llmProviderRouter,
                redisTemplate,
                new ObjectMapper(),
                new PaperChatRouter(),
                new EvidencePlanner(llmProviderRouter, new ObjectMapper()),
                new EvidenceLedgerService(),
                new EvidenceAnswerGenerator(llmProviderRouter, new EvidenceVerifier()),
                new EvidenceVerifier(),
                new EvidenceToolExecutor(retrievalService, paperService, conversationService, new EvidenceLedgerService()),
                taskRouter,
                libraryStatusService
        );

        PaperAnswerService.AnswerResult answer = statusService.answer("1", "c1", "我问的你有哪两篇论文");

        assertEquals(2, capturedRequest.get().history().size());
        assertEquals("现在有多少参考论文", capturedRequest.get().history().get(0).get("content"));
        assertTrue(capturedRequest.get().history().get(1).get("content").contains("当前可检索论文有 2 篇"));
        assertEquals(PaperAnswerService.Intent.LIBRARY_STATUS, answer.intent());
        assertEquals(2, answer.uniquePaperCount());
        assertTrue(answer.markdown().contains("1. 《LoRA》"));
        assertTrue(answer.markdown().contains("2. 《Attention Is All You Need》"));
        verifyNoInteractions(retrievalService);
    }

    @Test
    void libraryStatusInsideScopedSessionStillUsesStatusCapabilityWithoutRetrieval() {
        TaskRouter taskRouter = request -> TaskRoutingResult.routed(new TaskDecision(
                TaskType.LIBRARY_STATUS,
                TaskOperation.COUNT_SEARCHABLE_PAPERS,
                "",
                0.95d,
                "count scoped searchable papers"
        ));
        PaperLibraryStatusService libraryStatusService = mock(PaperLibraryStatusService.class);
        when(libraryStatusService.statusFor(eq("u1"), any(SourceScope.class)))
                .thenReturn(new PaperLibraryStatus(
                        3,
                        2,
                        0,
                        0,
                        1,
                        1,
                        List.of()
                ));
        PaperAnswerService statusService = new PaperAnswerService(
                retrievalService,
                paperService,
                conversationService,
                llmProviderRouter,
                redisTemplate,
                new ObjectMapper(),
                new PaperChatRouter(),
                new EvidencePlanner(llmProviderRouter, new ObjectMapper()),
                new EvidenceLedgerService(),
                new EvidenceAnswerGenerator(llmProviderRouter, new EvidenceVerifier()),
                new EvidenceVerifier(),
                new EvidenceToolExecutor(retrievalService, paperService, conversationService, new EvidenceLedgerService()),
                taskRouter,
                libraryStatusService
        );

        PaperAnswerService.AnswerResult answer = statusService.answer(
                "u1",
                "c1",
                "有多少论文可以检索",
                new PaperAnswerService.AnswerScope(
                        List.of("paper-a"),
                        List.of("Title paper-a"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );

        assertEquals(PaperAnswerService.Intent.LIBRARY_STATUS, answer.intent());
        assertEquals("LIBRARY_STATUS", answer.diagnostics().route());
        assertEquals("MANUAL_SOURCE", answer.diagnostics().scopeMode());
        assertEquals(0, answer.referenceMappings().size());
        assertEquals(0, answer.evidenceCount());
        assertTrue(answer.markdown().contains("当前检索范围：1 篇"));
        verifyNoInteractions(retrievalService);
    }

    @Test
    void recommendationDeduplicatesMultiplePapers() {
        plannerReturns(PlannerActionType.DISCOVER_PAPERS, "agent");
        when(retrievalService.discoverPapers(anyString(), eq("u1"), any(RetrievalBudget.class))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Agent Harness evidence one", 0.9),
                result("paper-a", 2, "Agent Harness evidence two", 0.8),
                result("paper-b", 1, "Tool calling evidence describes how agents invoke retrieval tools during multi-step tasks.", 0.7)
        )));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "推荐一下和 agent 相关的论文");

        assertEquals(2, answer.uniquePaperCount());
        assertEquals(2, answer.referenceMappings().size());
        assertTrue(answer.markdown().contains("《Title paper-a》"));
        assertTrue(answer.markdown().contains("《Title paper-b》"));
    }

    @Test
    void recommendationFallsBackToOriginalFilenameWhenTitleIsMissing() {
        plannerReturns(PlannerActionType.DISCOVER_PAPERS, "agent");
        SearchResult result = result("paper-a", 1, "Agent Harness evidence one", 0.9);
        result.setPaperTitle(null);
        result.setOriginalFilename("agent-harness.pdf");
        when(retrievalService.discoverPapers(anyString(), eq("u1"), any(RetrievalBudget.class))).thenReturn(retrievalResult(List.of(result)));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "推荐一下和 agent 相关的论文");

        assertEquals(1, answer.uniquePaperCount());
        assertTrue(answer.markdown().contains("《agent-harness.pdf》"));
        assertEquals("agent-harness.pdf", answer.referenceMappings().get(1).paperTitle());
    }

    @Test
    void recommendationStoresPaperFocusForFollowUp() {
        plannerReturns(PlannerActionType.DISCOVER_PAPERS, "agent");
        when(retrievalService.discoverPapers(anyString(), eq("u1"), any(RetrievalBudget.class))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Agent Harness evidence one", 0.9)
        )));

        service.answer("u1", "c1", "推荐一下和 agent 相关的论文");

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("paperloom:chat:focus:c1"), json.capture(), any(Duration.class));
        assertTrue(json.getValue().contains("paper-a"));
        assertTrue(json.getValue().contains("Title paper-a"));
    }

    @Test
    void followUpWithoutFocusAsksForClarificationAndDoesNotRetrieve() {
        when(valueOps.get("paperloom:chat:focus:c1")).thenReturn(null);
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(followUpRoute("详细讲解"));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "详细讲解");

        assertEquals(PaperAnswerService.Intent.CLARIFY, answer.intent());
        assertTrue(answer.markdown().contains("你想讲哪一篇"));
        verifyNoInteractions(retrievalService);
    }

    @Test
    void followUpWithMultipleFocusAsksForClarificationAndDoesNotRetrieve() {
        when(valueOps.get("paperloom:chat:focus:c1")).thenReturn("""
                {"lastIntent":"PAPER_RECOMMENDATION","lastPaperIds":["paper-a","paper-b"],"lastPaperTitles":["Title paper-a","Title paper-b"]}
                """);
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(followUpRoute("详细讲解"));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "详细讲解");

        assertEquals(PaperAnswerService.Intent.CLARIFY, answer.intent());
        assertTrue(answer.markdown().contains("你想讲第几篇"));
        verifyNoInteractions(retrievalService);
    }

    @Test
    void followUpAfterLibraryStatusListsThePapersFromStructuredFocus() {
        when(valueOps.get("paperloom:chat:focus:c1")).thenReturn("""
                {"lastIntent":"LIBRARY_STATUS","lastPaperIds":["paper-a","paper-b"],"lastPaperTitles":["LoRA","Attention Is All You Need"]}
                """);
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(followUpRoute(""));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "哪两篇");

        assertEquals(PaperAnswerService.Intent.LIBRARY_STATUS, answer.intent());
        assertEquals(2, answer.uniquePaperCount());
        assertTrue(answer.markdown().contains("1. 《LoRA》"));
        assertTrue(answer.markdown().contains("2. 《Attention Is All You Need》"));
        verifyNoInteractions(retrievalService);
    }

    @Test
    void followUpWithSingleFocusScopesQaToFocusedPaper() {
        when(valueOps.get("paperloom:chat:focus:c1")).thenReturn("""
                {"lastIntent":"PAPER_RECOMMENDATION","lastPaperIds":["paper-a"],"lastPaperTitles":["Title paper-a"]}
                """);
        when(retrievalService.retrieve(eq("Title paper-a 详细讲解"), eq("u1"), any(RetrievalBudget.class), eq(List.of("paper-a")))).thenReturn(retrievalResult(List.of(
                result("paper-b", 1, "Distractor evidence from another paper that should be filtered out by focused scope.", 0.95),
                result("paper-a", 1, "Focused paper evidence explains how the agent harness changes retrieval behavior.", 0.8)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(followUpRoute("详细讲解"), new LlmProviderRouter.ReActTurn(
                        "**结论**\n这篇论文讨论 Agent Harness。{{E1}}",
                        List.of(),
                        Map.of("role", "assistant", "content", "**结论**\n这篇论文讨论 Agent Harness。{{E1}}"),
                        "stop",
                        10,
                        5
                ));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "详细讲解");

        assertEquals(PaperAnswerService.Intent.MANUAL_SOURCE_QA, answer.intent());
        assertEquals("paper-a", answer.referenceMappings().get(1).paperId());
        assertTrue(answer.markdown().contains("[1]"));
    }

    @Test
    void explicitNewQuestionDoesNotReusePreviousFocusAsManualScope() {
        when(valueOps.get("paperloom:chat:focus:c1")).thenReturn("""
                {"lastIntent":"LIBRARY_SEARCH","lastPaperIds":["paper-old"],"lastPaperTitles":["Old focused paper"]}
                """);
        when(retrievalService.retrieve(eq("介绍一下grep"), eq("u1"), any(RetrievalBudget.class), eq(List.of()))).thenReturn(retrievalResult(List.of(
                result("paper-grep", 1, "Grep is available as a native bash tool that agents can use for search.", 0.9)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(
                        paperQaRoute(),
                        turn("**结论**\ngrep 在论文中被作为 agent 可调用的搜索工具讨论。{{E1}}")
                );

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "介绍一下grep");

        assertEquals(PaperAnswerService.Intent.PAPER_QA, answer.intent());
        assertEquals("AUTO_SOURCE", answer.diagnostics().scopeMode());
        assertEquals("paper-grep", answer.referenceMappings().get(1).paperId());
        verify(retrievalService, never()).retrieve(anyString(), eq("u1"), any(RetrievalBudget.class), eq(List.of("paper-old")));
    }

    @Test
    void followUpRestoresFocusFromLatestPersistedReferencesWhenRedisMisses() {
        when(valueOps.get("paperloom:chat:focus:c1")).thenReturn(null);
        Map<String, Object> referenceDetail = new java.util.LinkedHashMap<>();
        referenceDetail.put("paperId", "paper-a");
        referenceDetail.put("paperTitle", "Title paper-a");
        referenceDetail.put("originalFilename", "paper-a.pdf");
        referenceDetail.put("matchedChunkText", "Persisted evidence explains how agent harnesses control retrieval decisions.");
        referenceDetail.put("chunkId", 3);
        referenceDetail.put("pageNumber", 4);
        when(conversationService.findLatestReferenceFocus(1L, "c1"))
                .thenReturn(java.util.Optional.of(Map.of("1", referenceDetail)));
        when(retrievalService.retrieve(eq("Title paper-a 进一步解释"), eq("1"), any(RetrievalBudget.class), eq(List.of("paper-a"))))
                .thenReturn(retrievalResult(List.of(
                        result("paper-a", 1, "Focused paper evidence explains how the agent harness changes retrieval behavior.", 0.8)
        )));
        when(llmProviderRouter.completeReActTurn(eq("1"), any(), eq(List.of()), anyInt()))
                .thenReturn(followUpRoute("进一步解释"), new LlmProviderRouter.ReActTurn(
                        "**结论**\n这篇论文讨论 Agent Harness。{{E1}}",
                        List.of(),
                        Map.of("role", "assistant", "content", "**结论**\n这篇论文讨论 Agent Harness。{{E1}}"),
                        "stop",
                        10,
                        5
                ));

        PaperAnswerService.AnswerResult answer = service.answer("1", "c1", "进一步解释");

        assertEquals(PaperAnswerService.Intent.MANUAL_SOURCE_QA, answer.intent());
        assertEquals("paper-a", answer.referenceMappings().get(1).paperId());
    }

    @Test
    void qaGenerationFailureForUnknownEvidenceTokenDoesNotRenderFallbackSummary() {
        when(retrievalService.retrieve(anyString(), eq("u1"), any(RetrievalBudget.class), eq(List.of()))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Real evidence shows that agent harnesses alter retrieval decisions.", 0.9)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperQaRoute(), turn("这是一个无效引用 {{E99}}"));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "这篇论文和 agent 有什么关系");

        assertEquals(PaperAnswerService.Intent.PAPER_QA, answer.intent());
        assertFalse(answer.fallbackUsed());
        assertTrue(answer.markdown().contains("没有生成可验证的答案"));
        assertEquals(0, answer.referenceMappings().size());
    }

    @Test
    void qaGenerationQuotaFailureDoesNotRenderFallbackEvidenceSummary() {
        when(retrievalService.retrieve(eq("介绍一下grep"), eq("u1"), any(RetrievalBudget.class), eq(List.of()))).thenReturn(retrievalResult(List.of(
                result("paper-grep", 1, "Grep is available as a native bash search primitive that agents can use to inspect files and retrieve exact matches.", 0.9),
                result("paper-grep", 2, "The paper compares grep-style search with vector search under different agent harnesses.", 0.8)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperQaRoute())
                .thenThrow(new RateLimitExceededException("LLM Token 余额不足", 60));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "介绍一下grep");

        assertEquals(PaperAnswerService.Intent.PAPER_QA, answer.intent());
        assertFalse(answer.fallbackUsed());
        assertTrue(answer.markdown().contains("没有生成可验证的答案"));
        assertFalse(answer.markdown().contains("**可确认的信息**"));
        assertFalse(answer.markdown().contains("[1]"));
        assertEquals(0, answer.referenceMappings().size());
    }

    @Test
    void qaDoesNotGenerateAnswerWhenOnlyUnusableEvidenceIsRetrieved() {
        when(retrievalService.retrieve(anyString(), eq("u1"), any(RetrievalBudget.class), eq(List.of()))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "5", 0.9)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperQaRoute());

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "这篇论文和 agent 有什么关系");

        assertEquals(PaperAnswerService.Intent.PAPER_QA, answer.intent());
        assertEquals(0, answer.referenceMappings().size());
        assertTrue(answer.markdown().contains("足够可靠"));
    }

    @Test
    void qaGenerationFailureForNakedCitationDoesNotRenderFallbackSummary() {
        when(retrievalService.retrieve(anyString(), eq("u1"), any(RetrievalBudget.class), eq(List.of()))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Real evidence shows that agent harnesses alter retrieval decisions.", 0.9)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperQaRoute(), turn("这是模型自己编的引用 [1]"));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "这篇论文和 agent 有什么关系");

        assertFalse(answer.fallbackUsed());
        assertTrue(answer.markdown().contains("没有生成可验证的答案"));
        assertEquals(0, answer.referenceMappings().size());
    }

    @Test
    void qaGenerationFailureForLegacySourceCitationDoesNotRenderFallbackSummary() {
        when(retrievalService.retrieve(anyString(), eq("u1"), any(RetrievalBudget.class), eq(List.of()))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Real evidence shows that agent harnesses alter retrieval decisions.", 0.9)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperQaRoute(), turn("这是旧格式引用 来源#1"));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "这篇论文和 agent 有什么关系");

        assertFalse(answer.fallbackUsed());
        assertTrue(answer.markdown().contains("没有生成可验证的答案"));
        assertEquals(0, answer.referenceMappings().size());
    }

    @Test
    void qaRendersValidEvidenceTokensIntoCitationMappings() {
        when(retrievalService.retrieve(anyString(), eq("u1"), any(RetrievalBudget.class), eq(List.of()))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Agent harness changes retrieval behavior.", 0.9)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperQaRoute(), turn("**结论**\nAgent Harness 会改变检索行为。{{E1}}"));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "这篇论文和 agent 有什么关系");

        assertEquals(PaperAnswerService.Intent.PAPER_QA, answer.intent());
        assertTrue(answer.markdown().contains("[1]"));
        assertTrue(!answer.markdown().contains("{{E1}}"));
        assertEquals("paper-a", answer.referenceMappings().get(1).paperId());
    }

    @Test
    void loraContentQuestionUsesPaperQaInsteadOfRecommendationTemplate() {
        String question = "According to the LoRA paper, what limitation of full fine-tuning does LoRA address, and how does the low-rank update work?";
        String classifierJson = """
                {
                  "route": "PAPER_QA",
                  "retrievalIntent": "METHOD",
                  "query": "LoRA full fine-tuning limitation low-rank update",
                  "confidence": 0.94,
                  "reason": "The user asks how the method in a paper works."
                }
                """;
        when(retrievalService.retrieve(eq("LoRA full fine-tuning limitation low-rank update"), eq("u1"), any(RetrievalBudget.class), eq(List.of()))).thenReturn(retrievalResult(List.of(
                result("lora-paper", 1, "LoRA addresses the inefficiency of full fine-tuning by freezing pretrained weights and learning low-rank decomposition matrices for the update.", 0.95)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(
                        turn(classifierJson),
                        turn("**结论**\nLoRA 避免对所有预训练参数做全量更新，而是在冻结权重旁学习低秩更新矩阵。{{E1}}")
                );

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", question);

        assertEquals(PaperAnswerService.Intent.PAPER_QA, answer.intent());
        assertEquals("PAPER_QA", answer.diagnostics().route());
        assertTrue(answer.markdown().contains("[1]"));
        assertTrue(!answer.markdown().contains("推荐"));
        assertTrue(!answer.markdown().contains("只找到"));
        assertEquals("lora-paper", answer.referenceMappings().get(1).paperId());
        verify(retrievalService).retrieve(eq("LoRA full fine-tuning limitation low-rank update"), eq("u1"), any(RetrievalBudget.class), eq(List.of()));
        verify(retrievalService, never()).discoverPapers(anyString(), eq("u1"), any(RetrievalBudget.class));
    }

    @Test
    void qaReferenceMappingPreservesPdfReadinessOnly() {
        SearchResult evalResult = result("paper-pdf", 1, "PDF evidence about agent benchmarks.", 0.9);
        evalResult.setOriginalFilename("agent-benchmarks.pdf");
        evalResult.setSourceType("PDF");
        evalResult.setEvidenceAssetLevel("PDF_PENDING_ASSETS");
        evalResult.setPdfEvidenceAvailable(false);
        evalResult.setPageScreenshotAvailable(false);
        evalResult.setFigureScreenshotAvailable(false);
        evalResult.setAssetWarnings(List.of("page_screenshots_missing"));
        when(retrievalService.retrieve(anyString(), eq("u1"), any(RetrievalBudget.class), eq(List.of()))).thenReturn(retrievalResult(List.of(
                evalResult
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperQaRoute(), turn("**结论**\n结构化评测证据说明 agent benchmark 设置。{{E1}}"));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "agent benchmark 怎么设置");
        ChatHandler.ReferenceInfo reference = answer.referenceMappings().get(1);

        assertEquals("paper-pdf", reference.paperId());
        assertEquals("PDF", reference.sourceType());
        assertEquals("PDF_PENDING_ASSETS", reference.evidenceAssetLevel());
        assertEquals(false, reference.pdfEvidenceAvailable());
        assertEquals(false, reference.pageScreenshotAvailable());
        assertEquals(false, reference.figureScreenshotAvailable());
        assertEquals(List.of("page_screenshots_missing"), reference.assetWarnings());
    }

    @Test
    void qaReferenceMappingPreservesTableEvidenceAndCropAvailability() {
        SearchResult tableResult = result("paper-table", 12, "Table 1 reports overall accuracy for grep and vector search.", 0.9);
        tableResult.setSourceKind("TABLE");
        tableResult.setTableId("table-1");
        tableResult.setTableText("Metric: Overall accuracy\ngrep: 91.2\nvector: 89.4");
        tableResult.setTableMarkdown("| Metric | grep | vector |\n| --- | ---: | ---: |\n| Overall accuracy | 91.2 | 89.4 |");
        tableResult.setTableScreenshotAvailable(true);
        tableResult.setPdfEvidenceAvailable(true);
        tableResult.setEvidenceAssetLevel("PDF_VISUAL");
        when(retrievalService.retrieve(anyString(), eq("u1"), any(RetrievalBudget.class), eq(List.of()))).thenReturn(retrievalResult(List.of(
                tableResult
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperQaRoute(), turn("**结论**\n表 1 报告了 grep 和 vector 的整体准确率。{{E1}}"));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "实验1的表1报告了什么？");
        ChatHandler.ReferenceInfo reference = answer.referenceMappings().get(1);

        assertEquals("TABLE", reference.sourceKind());
        assertEquals("table-1", reference.tableId());
        assertEquals("Metric: Overall accuracy\ngrep: 91.2\nvector: 89.4", reference.tableText());
        assertEquals("| Metric | grep | vector |\n| --- | ---: | ---: |\n| Overall accuracy | 91.2 | 89.4 |", reference.tableMarkdown());
        assertEquals(true, reference.tableScreenshotAvailable());
        assertEquals("PDF_VISUAL", reference.evidenceAssetLevel());
    }

    @Test
    void qaGenerationFailureForFigureEvidenceDoesNotRenderFallbackVisualClaim() {
        SearchResult figureResult = result("paper-figure", 9, "Figure 2 caption: agent harness accuracy rises after scoped retrieval.", 0.9);
        figureResult.setSourceKind("FIGURE");
        figureResult.setFigureId("figure-2");
        figureResult.setEvidenceRole("FIGURE_CAPTION");
        figureResult.setPdfEvidenceAvailable(true);
        figureResult.setEvidenceAssetLevel("PDF_PENDING_ASSETS");
        figureResult.setPageScreenshotAvailable(true);
        figureResult.setFigureScreenshotAvailable(false);
        figureResult.setAssetWarnings(List.of("figure_screenshot_missing"));
        when(retrievalService.retrieve(anyString(), eq("u1"), any(RetrievalBudget.class), eq(List.of()))).thenReturn(retrievalResult(List.of(
                figureResult
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperQaRoute())
                .thenThrow(new RateLimitExceededException("LLM Token 余额不足", 60));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "这张图说明什么？");

        assertFalse(answer.fallbackUsed());
        assertEquals(0, answer.referenceMappings().size());
        assertTrue(answer.markdown().contains("没有生成可验证的答案"));
        assertTrue(!answer.markdown().contains("截图"));
        assertTrue(!answer.markdown().contains("视觉"));
        assertTrue(!answer.markdown().toLowerCase(java.util.Locale.ROOT).contains("visual"));
        assertTrue(!answer.markdown().toLowerCase(java.util.Locale.ROOT).contains("image"));
    }

    @Test
    void qaRendersImportantSectionTitleNextToCitationWhenModelOmittedIt() {
        SearchResult highNoiseTable = result(
                "paper-a",
                119,
                "Model Harness s5 s10 s20 s30 full Claude Haiku 4.5 Claude Code 91.4 94.0 95.7 90.5 94.0",
                0.9
        );
        highNoiseTable.setSourceKind("TABLE");
        highNoiseTable.setSectionTitle("4.2 Experiment 2: Context Scaling with Increasing Noise");
        when(retrievalService.retrieve(anyString(), eq("u1"), any(RetrievalBudget.class), eq(List.of()))).thenReturn(retrievalResult(List.of(
                highNoiseTable
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperQaRoute(), turn("**依据**\n- 高噪声条件下 Claude Haiku 4.5 仍达到 94.0%。{{E1}}"));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "讲一讲高噪声场景");

        assertTrue(answer.markdown().contains("Experiment 2: Context Scaling with Increasing Noise"));
        assertTrue(answer.markdown().contains("[1]"));
    }

    @Test
    void qaNumbersCitationsByEvidenceRankNotMentionOrder() {
        when(retrievalService.retrieve(anyString(), eq("u1"), any(RetrievalBudget.class), eq(List.of()))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Stronger evidence explains the central agent harness retrieval behavior.", 0.9),
                result("paper-b", 1, "Weaker evidence provides related but less direct tool usage context.", 0.7)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperQaRoute(), turn("**依据**\n- 次要证据。{{E2}}\n- 主要证据。{{E1}}"));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "这篇论文和 agent 有什么关系");

        assertTrue(answer.markdown().contains("次要证据。[2]"));
        assertTrue(answer.markdown().contains("主要证据。[1]"));
        assertEquals("paper-a", answer.referenceMappings().get(1).paperId());
        assertEquals("paper-b", answer.referenceMappings().get(2).paperId());
    }

    @Test
    void qaGenerationFailureForUnknownTitleDoesNotRenderFallbackSummary() {
        when(retrievalService.retrieve(anyString(), eq("u1"), any(RetrievalBudget.class), eq(List.of()))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Real evidence shows that agent harnesses alter retrieval decisions.", 0.9)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperQaRoute(), turn("《Fake Agent Paper》也很相关。{{E1}}"));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "这篇论文和 agent 有什么关系");

        assertFalse(answer.fallbackUsed());
        assertTrue(answer.markdown().contains("没有生成可验证的答案"));
        assertTrue(!answer.markdown().contains("Fake Agent Paper"));
        assertEquals(0, answer.referenceMappings().size());
    }

    @Test
    void verifierDoesNotRejectComparativeClaimWithValidEvidenceByPhraseList() {
        when(retrievalService.retrieve(anyString(), eq("u1"), any(RetrievalBudget.class), eq(List.of()))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Agent harnesses dynamically choose retrieval tools and inspect intermediate results before answering.", 0.9)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperQaRoute(), turn("**结论**\nAgent Harness 显著优于传统 Grep 搜索。{{E1}}"));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "agent harness 比 grep 更好吗");

        assertFalse(answer.fallbackUsed());
        assertTrue(answer.markdown().contains("显著优于"));
        assertEquals(1, answer.referenceMappings().size());
    }

    @Test
    void referenceScopeUsesProvidedEvidenceWithoutRetrievingLibrary() {
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "**结论**\n这个引用说明 Agent Harness 会动态调整检索行为。{{E1}}",
                        List.of(),
                        Map.of("role", "assistant", "content", "**结论**\n这个引用说明 Agent Harness 会动态调整检索行为。{{E1}}"),
                        "stop",
                        10,
                        5
                ));
        PaperAnswerService.AnswerScope scope = new PaperAnswerService.AnswerScope(
                List.of("paper-a"),
                List.of("Title paper-a"),
                1,
                35L,
                7,
                5,
                "paper-a",
                "Title paper-a",
                "paper-a.pdf",
                "Agent harnesses dynamically choose retrieval tools and inspect intermediate results before answering.",
                "{\"coordinateSystem\":\"top_left_1000\"}",
                "TEXT"
        );

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "解释这个引用", scope);

        assertEquals(PaperAnswerService.Intent.REFERENCE_QA, answer.intent());
        assertTrue(answer.markdown().contains("[1]"));
        assertEquals("paper-a", answer.referenceMappings().get(1).paperId());
        verifyNoInteractions(retrievalService);
    }

    @Test
    void referenceSeedWithReferenceNumberDoesNotReinspectPersistedReference() {
        when(conversationService.findLatestReferenceDetail(1L, "c1", 1))
                .thenReturn(java.util.Optional.of(Map.of(
                        "paperId", "paper-b",
                        "paperTitle", "Out of scope paper",
                        "matchedChunkText", "This persisted detail should not be re-resolved."
                )));
        when(llmProviderRouter.completeReActTurn(eq("1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "**结论**\nvalidated seed 已经足够回答。{{E1}}",
                        List.of(),
                        Map.of("role", "assistant", "content", "**结论**\nvalidated seed 已经足够回答。{{E1}}"),
                        "stop",
                        10,
                        5
                ));
        PaperAnswerService.AnswerScope scope = new PaperAnswerService.AnswerScope(
                List.of("paper-a"),
                List.of("Title paper-a"),
                1,
                null,
                7,
                5,
                "paper-a",
                "Title paper-a",
                "paper-a.pdf",
                "validated seed evidence from the locked scope",
                "{\"coordinateSystem\":\"top_left_1000\"}",
                "TEXT"
        );

        PaperAnswerService.AnswerResult answer = service.answer("1", "c1", "解释 [1]", scope);

        assertEquals(PaperAnswerService.Intent.REFERENCE_QA, answer.intent());
        assertEquals("paper-a", answer.referenceMappings().get(1).paperId());
        assertTrue(answer.markdown().contains("[1]"));
        verify(conversationService, never()).findLatestReferenceDetail(1L, "c1", 1);
        verify(conversationService, never()).findReferenceDetail(eq(1L), any(), eq(1));
        verifyNoInteractions(retrievalService);
    }

    @Test
    void referenceScopeWithoutUsableSeedDoesNotReturnOutOfScopeReinspectionEvidence() {
        when(conversationService.findLatestReferenceDetail(1L, "c1", 1))
                .thenReturn(java.util.Optional.of(Map.of(
                        "paperId", "paper-b",
                        "paperTitle", "Out of scope paper",
                        "originalFilename", "paper-b.pdf",
                        "matchedChunkText", "Out-of-scope persisted evidence must not be used.",
                        "chunkId", 7,
                        "pageNumber", 5
                )));
        PaperAnswerService.AnswerScope scope = new PaperAnswerService.AnswerScope(
                List.of("paper-a"),
                List.of("Title paper-a"),
                1,
                null,
                null,
                null,
                "paper-a",
                "Title paper-a",
                "paper-a.pdf",
                null,
                null,
                "TEXT"
        );

        PaperAnswerService.AnswerResult answer = service.answer("1", "c1", "解释 [1]", scope);

        assertEquals(PaperAnswerService.Intent.CLARIFY, answer.intent());
        assertEquals(0, answer.referenceMappings().size());
        verify(conversationService).findLatestReferenceDetail(1L, "c1", 1);
        verifyNoInteractions(retrievalService, llmProviderRouter);
    }

    @Test
    void answerScopeWithPaperIdsPreservesReferenceFocusAndBudget() {
        PaperAnswerService.AnswerScope incomingFocus = new PaperAnswerService.AnswerScope(
                List.of("mutable-paper"),
                List.of("Mutable paper"),
                3,
                42L,
                7,
                5,
                "paper-a",
                "Paper A",
                "paper-a.pdf",
                "Focused reference text",
                "{\"coordinateSystem\":\"top_left_1000\"}",
                "TEXT",
                RetrievalBudgetProfile.DEEP_AUDIT
        );

        PaperAnswerService.AnswerScope controlled = incomingFocus.withPaperIds(List.of("paper-a", "paper-c"));

        assertEquals(List.of("paper-a", "paper-c"), controlled.paperIds());
        assertEquals(3, controlled.referenceNumber());
        assertEquals(42L, controlled.conversationRecordId());
        assertEquals(7, controlled.chunkId());
        assertEquals(5, controlled.pageNumber());
        assertEquals("paper-a", controlled.paperId());
        assertEquals("Focused reference text", controlled.matchedText());
        assertEquals(RetrievalBudgetProfile.DEEP_AUDIT, controlled.retrievalBudgetProfile());
    }

    @Test
    void referenceScopeUsesProvidedEvidenceEvenWithoutReferenceNumber() {
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "**结论**\n这个引用说明 Agent Harness 会动态调整检索行为。{{E1}}",
                        List.of(),
                        Map.of("role", "assistant", "content", "**结论**\n这个引用说明 Agent Harness 会动态调整检索行为。{{E1}}"),
                        "stop",
                        10,
                        5
                ));
        PaperAnswerService.AnswerScope scope = new PaperAnswerService.AnswerScope(
                List.of("paper-a"),
                List.of("Title paper-a"),
                null,
                null,
                7,
                5,
                "paper-a",
                "Title paper-a",
                "paper-a.pdf",
                "Agent harnesses dynamically choose retrieval tools and inspect intermediate results before answering.",
                "{\"coordinateSystem\":\"top_left_1000\"}",
                "TEXT"
        );

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "解释这个引用", scope);

        assertEquals(PaperAnswerService.Intent.REFERENCE_QA, answer.intent());
        assertEquals("paper-a", answer.referenceMappings().get(1).paperId());
        verifyNoInteractions(retrievalService);
    }

    @Test
    void handwrittenMissingReferenceClarifiesWithoutRetrieval() {
        when(conversationService.findLatestReferenceDetail(1L, "c1", 99)).thenReturn(java.util.Optional.empty());

        PaperAnswerService.AnswerResult answer = service.answer("1", "c1", "解释 [99]");

        assertEquals(PaperAnswerService.Intent.CLARIFY, answer.intent());
        assertTrue(answer.markdown().contains("找不到这个引用"));
        verify(conversationService).findLatestReferenceDetail(1L, "c1", 99);
        verifyNoInteractions(retrievalService, llmProviderRouter);
    }

    @Test
    void manualSourceQaRunsThroughControlledPlannerAndToolExecutor() {
        PaperRetrievalService directRetrieval = mock(PaperRetrievalService.class);
        EvidencePlanner planner = mock(EvidencePlanner.class);
        EvidenceToolExecutor toolExecutor = mock(EvidenceToolExecutor.class);
        EvidenceAnswerGenerator answerGenerator = mock(EvidenceAnswerGenerator.class);
        EvidenceVerifier verifier = new EvidenceVerifier();
        EvidenceLedgerService ledgerService = new EvidenceLedgerService();
        PaperAnswerService harnessService = new PaperAnswerService(
                directRetrieval,
                paperService,
                conversationService,
                llmProviderRouter,
                redisTemplate,
                new ObjectMapper(),
                new PaperChatRouter(),
                planner,
                ledgerService,
                answerGenerator,
                verifier,
                toolExecutor
        );
        PlannerAction searchAction = new PlannerAction(
                PlannerActionType.SEARCH_EVIDENCE,
                "agent harness",
                "manual_source",
                List.of("paper-a"),
                null
        );
        PlannerAction answerAction = new PlannerAction(
                PlannerActionType.ANSWER_WITH_LEDGER,
                "agent harness",
                "ledger_ready",
                List.of("paper-a"),
                null
        );
        EvidenceLedger ledger = new EvidenceLedger(
                List.of(new PaperSource("paper-a", "Title paper-a", "paper-a.pdf")),
                List.of(new com.yizhaoqi.smartpai.service.EvidenceItem(
                        "E1",
                        "paper-a",
                        "Title paper-a",
                        "paper-a.pdf",
                        3,
                        8,
                        "TEXT",
                        "Method",
                        "Agent harness evidence explains controlled retrieval behavior.",
                        "{\"coordinateSystem\":\"top_left_1000\"}",
                        0.9d
                )),
                new LedgerDiagnostics(4, 1, 1, "EXHAUSTED")
        );
        when(planner.plan(any())).thenReturn(searchAction, answerAction);
        when(toolExecutor.execute(eq("u1"), eq("c1"), eq(searchAction), any(SourceScope.class)))
                .thenReturn(new EvidenceToolResult(PlannerActionType.SEARCH_EVIDENCE, ledger, ""));
        when(answerGenerator.generate(eq("u1"), eq("这篇论文讲什么"), any(EvidenceLedger.class)))
                .thenReturn(new EvidenceAnswerGenerator.GeneratedAnswer(
                        "**结论**\n这篇论文讨论受控检索。{{E1}}",
                        true,
                        "",
                        10,
                        5
                ));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperQaRoute());

        PaperAnswerService.AnswerResult answer = harnessService.answer(
                "u1",
                "c1",
                "这篇论文讲什么",
                new PaperAnswerService.AnswerScope(
                        List.of("paper-a"),
                        List.of("Title paper-a"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );

        assertEquals(PaperAnswerService.Intent.MANUAL_SOURCE_QA, answer.intent());
        assertEquals("MANUAL_SOURCE", answer.diagnostics().scopeMode());
        assertTrue(answer.markdown().contains("[1]"));
        assertEquals("paper-a", answer.referenceMappings().get(1).paperId());
        verify(planner, org.mockito.Mockito.times(2)).plan(any(PlannerContext.class));
        verify(toolExecutor).execute(eq("u1"), eq("c1"), eq(searchAction), any(SourceScope.class));
        verifyNoInteractions(directRetrieval);
    }

    @Test
    void naturalTopicDiscoveryFromAutoSourceRendersLibrarySearchRecommendation() {
        EvidencePlanner planner = mock(EvidencePlanner.class);
        EvidenceToolExecutor toolExecutor = mock(EvidenceToolExecutor.class);
        EvidenceAnswerGenerator answerGenerator = mock(EvidenceAnswerGenerator.class);
        PaperAnswerService harnessService = new PaperAnswerService(
                retrievalService,
                paperService,
                conversationService,
                llmProviderRouter,
                redisTemplate,
                new ObjectMapper(),
                new PaperChatRouter(),
                planner,
                new EvidenceLedgerService(),
                answerGenerator,
                new EvidenceVerifier(),
                toolExecutor
        );
        PlannerAction discoverAction = new PlannerAction(
                PlannerActionType.DISCOVER_PAPERS,
                "agent",
                "topic discovery",
                List.of(),
                null
        );
        EvidenceLedger ledger = new EvidenceLedger(
                List.of(new PaperSource("paper-agent", "Agent Paper", "agent.pdf")),
                List.of(new com.yizhaoqi.smartpai.service.EvidenceItem(
                        "E1",
                        "paper-agent",
                        "Agent Paper",
                        "agent.pdf",
                        null,
                        0,
                        "PAPER",
                        "title/abstract",
                        "Title: Agent Paper\nAbstract: This paper studies agent coordination and tool use.",
                        null,
                        3.2d
                )),
                new LedgerDiagnostics(1, 1, 1, "EXHAUSTED")
        );
        when(planner.plan(any())).thenReturn(discoverAction);
        when(toolExecutor.execute(eq("u1"), eq("c1"), eq(discoverAction), any(SourceScope.class)))
                .thenReturn(new EvidenceToolResult(PlannerActionType.DISCOVER_PAPERS, ledger, ""));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperDiscoveryRoute("agent"));

        PaperAnswerService.AnswerResult answer = harnessService.answer(
                "u1",
                "c1",
                "有什么agent相关的论文吗？"
        );

        assertEquals(PaperAnswerService.Intent.LIBRARY_SEARCH, answer.intent());
        assertEquals(1, answer.uniquePaperCount());
        assertTrue(answer.markdown().contains("《Agent Paper》"));
        assertTrue(answer.markdown().contains("题名：Agent Paper"));
        verifyNoInteractions(answerGenerator);
    }

    @Test
    void manualSourceQaPassesRetrievalBudgetProfileToToolExecutor() {
        EvidencePlanner planner = mock(EvidencePlanner.class);
        EvidenceToolExecutor toolExecutor = mock(EvidenceToolExecutor.class);
        EvidenceAnswerGenerator answerGenerator = mock(EvidenceAnswerGenerator.class);
        PaperAnswerService harnessService = new PaperAnswerService(
                retrievalService,
                paperService,
                conversationService,
                llmProviderRouter,
                redisTemplate,
                new ObjectMapper(),
                new PaperChatRouter(),
                planner,
                new EvidenceLedgerService(),
                answerGenerator,
                new EvidenceVerifier(),
                toolExecutor
        );
        PlannerAction searchAction = new PlannerAction(
                PlannerActionType.SEARCH_EVIDENCE,
                "method",
                "manual_source",
                List.of("paper-a"),
                null
        );
        PlannerAction answerAction = new PlannerAction(
                PlannerActionType.ANSWER_WITH_LEDGER,
                "method",
                "ledger_ready",
                List.of("paper-a"),
                null
        );
        EvidenceLedger ledger = new EvidenceLedger(
                List.of(new PaperSource("paper-a", "Title paper-a", "paper-a.pdf")),
                List.of(new com.yizhaoqi.smartpai.service.EvidenceItem(
                        "E1",
                        "paper-a",
                        "Title paper-a",
                        "paper-a.pdf",
                        3,
                        8,
                        "TEXT",
                        "Method",
                        "Agent harness evidence explains controlled retrieval behavior.",
                        "{\"coordinateSystem\":\"top_left_1000\"}",
                        0.9d
                )),
                new LedgerDiagnostics(4, 1, 1, "EXHAUSTED")
        );
        when(planner.plan(any())).thenReturn(searchAction, answerAction);
        when(toolExecutor.execute(eq("u1"), eq("c1"), eq(searchAction), any(SourceScope.class)))
                .thenReturn(new EvidenceToolResult(PlannerActionType.SEARCH_EVIDENCE, ledger, ""));
        when(answerGenerator.generate(eq("u1"), eq("这篇论文讲什么"), any(EvidenceLedger.class)))
                .thenReturn(new EvidenceAnswerGenerator.GeneratedAnswer(
                        "**结论**\n这篇论文讨论受控检索。{{E1}}",
                        true,
                        "",
                        10,
                        5
                ));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(paperQaRoute());

        harnessService.answer(
                "u1",
                "c1",
                "这篇论文讲什么",
                new PaperAnswerService.AnswerScope(
                        List.of("paper-a"),
                        List.of("Title paper-a"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        RetrievalBudgetProfile.DEEP_AUDIT
                )
        );

        ArgumentCaptor<SourceScope> scope = ArgumentCaptor.forClass(SourceScope.class);
        verify(toolExecutor).execute(eq("u1"), eq("c1"), eq(searchAction), scope.capture());
        assertEquals(RetrievalBudgetProfile.DEEP_AUDIT, scope.getValue().retrievalBudgetProfile());
    }

    private PaperRetrievalService.RetrievalResult retrievalResult(List<SearchResult> results) {
        PaperQueryPlanner.RetrievalPlan plan = new PaperQueryPlanner.RetrievalPlan(
                "query",
                "query",
                PaperQueryPlanner.RetrievalIntent.GENERAL,
                List.of("query"),
                List.of("TEXT"),
                List.of()
        );
        return new PaperRetrievalService.RetrievalResult(plan, results, Map.of("query", results.size()));
    }

    private void plannerReturns(PlannerActionType actionType, String query) {
        PaperAnswerService.Intent route = actionType == PlannerActionType.DISCOVER_PAPERS
                || actionType == PlannerActionType.LIST_LIBRARY
                ? PaperAnswerService.Intent.LIBRARY_SEARCH
                : PaperAnswerService.Intent.PAPER_QA;
        PaperQueryPlanner.RetrievalIntent retrievalIntent = route == PaperAnswerService.Intent.LIBRARY_SEARCH
                ? PaperQueryPlanner.RetrievalIntent.LITERATURE_SEARCH
                : PaperQueryPlanner.RetrievalIntent.GENERAL;
        String json = "{\"route\":\"" + route.name() + "\",\"retrievalIntent\":\"" + retrievalIntent.name() + "\",\"query\":\""
                + (query == null ? "" : query)
                + "\",\"confidence\":0.9,\"reason\":\"test\"}";
        when(llmProviderRouter.completeReActTurn(anyString(), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        json,
                        List.of(),
                        Map.of("role", "assistant", "content", json),
                        "stop",
                        10,
                        5
                ));
    }

    private LlmProviderRouter.ReActTurn turn(String content) {
        return new LlmProviderRouter.ReActTurn(
                content,
                List.of(),
                Map.of("role", "assistant", "content", content),
                "stop",
                10,
                5
        );
    }

    private LlmProviderRouter.ReActTurn paperQaRoute() {
        String content = """
                {
                  "taskType": "PAPER_QA",
                  "operation": "ANSWER_FROM_EVIDENCE",
                  "query": "",
                  "confidence": 0.9,
                  "reason": "test paper qa route"
                }
                """;
        return turn(content);
    }

    private LlmProviderRouter.ReActTurn followUpRoute(String query) {
        String content = """
                {
                  "taskType": "FOLLOW_UP",
                  "operation": "ANSWER_FROM_EVIDENCE",
                  "query": "%s",
                  "confidence": 0.9,
                  "reason": "test follow-up route"
                }
                """.formatted(query == null ? "" : query);
        return turn(content);
    }

    private LlmProviderRouter.ReActTurn clarifyRoute() {
        String content = """
                {
                  "taskType": "CLARIFY",
                  "operation": "ASK_CLARIFICATION",
                  "query": "",
                  "confidence": 0.9,
                  "reason": "test clarify route"
                }
                """;
        return turn(content);
    }

    private LlmProviderRouter.ReActTurn smalltalkRoute() {
        String content = """
                {
                  "taskType": "SMALLTALK",
                  "operation": "DIRECT_RESPONSE",
                  "query": "",
                  "confidence": 0.9,
                  "reason": "test smalltalk route"
                }
                """;
        return turn(content);
    }

    private LlmProviderRouter.ReActTurn paperDiscoveryRoute(String query) {
        String content = """
                {
                  "taskType": "PAPER_DISCOVERY",
                  "operation": "SEARCH_PAPER_METADATA",
                  "query": "%s",
                  "confidence": 0.9,
                  "reason": "test paper discovery route"
                }
                """.formatted(query == null ? "" : query);
        return turn(content);
    }

    private SearchResult result(String paperId, int chunkId, String text, double score) {
        return new SearchResult(
                paperId,
                chunkId,
                text,
                score,
                "u1",
                "TEAM",
                false,
                "Title " + paperId,
                paperId + ".pdf",
                chunkId,
                text,
                "HYBRID",
                text,
                "PARAGRAPH",
                "Agent Harnesses",
                2,
                "{\"coordinateSystem\":\"top_left_1000\"}",
                "MinerU",
                "self-hosted",
                "TEXT",
                null,
                null,
                null,
                false
        );
    }

    private Paper paper(String paperId, String title) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setPaperTitle(title);
        paper.setOriginalFilename(title + ".pdf");
        return paper;
    }
}
