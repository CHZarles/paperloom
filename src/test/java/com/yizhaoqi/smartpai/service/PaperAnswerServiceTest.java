package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.Paper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private LlmProviderRouter llmProviderRouter;
    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOps;
    private PaperAnswerService service;

    @BeforeEach
    void setUp() {
        retrievalService = mock(PaperRetrievalService.class);
        paperService = mock(PaperService.class);
        llmProviderRouter = mock(LlmProviderRouter.class);
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new PaperAnswerService(retrievalService, paperService, llmProviderRouter, redisTemplate, new ObjectMapper());
    }

    @Test
    void smalltalkDoesNotRetrieveOrCallLlm() {
        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "hi");

        assertEquals(PaperAnswerService.Intent.SMALLTALK, answer.intent());
        assertEquals(0, answer.referenceMappings().size());
        assertTrue(answer.markdown().contains("我在"));
        verifyNoInteractions(retrievalService, llmProviderRouter);
    }

    @Test
    void recommendationGroupsChunksByPaperAndDoesNotCallLlm() {
        when(retrievalService.retrieve(anyString(), eq("u1"), eq(120))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Agent Harness evidence one", 0.9),
                result("paper-a", 2, "Agent Harness evidence two", 0.8),
                result("paper-a", 3, "Agent Harness evidence three", 0.7)
        )));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "推荐一下和 agent 相关的论文");

        assertEquals(PaperAnswerService.Intent.PAPER_RECOMMENDATION, answer.intent());
        assertEquals(1, answer.uniquePaperCount());
        assertEquals(1, answer.referenceMappings().size());
        assertTrue(answer.markdown().contains("只找到 1 篇"));
        assertTrue(answer.markdown().contains("《Title paper-a》"));
        assertTrue(answer.markdown().contains("[1]"));
        verifyNoInteractions(llmProviderRouter);
    }

    @Test
    void recommendationKeywordWithoutPaperStillUsesRecommendationRoute() {
        when(retrievalService.retrieve(eq("grep"), eq("u1"), eq(120))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "grep evidence", 0.9)
        )));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "推荐一些grep");

        assertEquals(PaperAnswerService.Intent.PAPER_RECOMMENDATION, answer.intent());
        assertTrue(answer.markdown().contains("《Title paper-a》"));
        verifyNoInteractions(llmProviderRouter);
    }

    @Test
    void paperInventoryQuestionUsesRecommendationRoute() {
        when(paperService.getAccessiblePapers("u1", null)).thenReturn(List.of(paper("paper-a", "Agent Harness Paper")));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "现在有什么论文");

        assertEquals(PaperAnswerService.Intent.PAPER_RECOMMENDATION, answer.intent());
        assertTrue(answer.markdown().contains("当前可访问论文库中有 1 篇论文"));
        assertTrue(answer.markdown().contains("《Agent Harness Paper》"));
        assertEquals(0, answer.referenceMappings().size());
        verifyNoInteractions(retrievalService, llmProviderRouter);
    }

    @Test
    void recommendationDeduplicatesMultiplePapers() {
        when(retrievalService.retrieve(anyString(), eq("u1"), eq(120))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Agent Harness evidence one", 0.9),
                result("paper-a", 2, "Agent Harness evidence two", 0.8),
                result("paper-b", 1, "Tool calling evidence", 0.7)
        )));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "推荐一下和 agent 相关的论文");

        assertEquals(2, answer.uniquePaperCount());
        assertEquals(2, answer.referenceMappings().size());
        assertTrue(answer.markdown().contains("《Title paper-a》"));
        assertTrue(answer.markdown().contains("《Title paper-b》"));
        verifyNoInteractions(llmProviderRouter);
    }

    @Test
    void recommendationFallsBackToOriginalFilenameWhenTitleIsMissing() {
        SearchResult result = result("paper-a", 1, "Agent Harness evidence one", 0.9);
        result.setPaperTitle(null);
        result.setOriginalFilename("agent-harness.pdf");
        when(retrievalService.retrieve(anyString(), eq("u1"), eq(120))).thenReturn(retrievalResult(List.of(result)));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "推荐一下和 agent 相关的论文");

        assertEquals(1, answer.uniquePaperCount());
        assertTrue(answer.markdown().contains("《agent-harness.pdf》"));
        assertEquals("agent-harness.pdf", answer.referenceMappings().get(1).paperTitle());
        verifyNoInteractions(llmProviderRouter);
    }

    @Test
    void recommendationStoresPaperFocusForFollowUp() {
        when(retrievalService.retrieve(anyString(), eq("u1"), eq(120))).thenReturn(retrievalResult(List.of(
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

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "详细讲解");

        assertEquals(PaperAnswerService.Intent.CLARIFY, answer.intent());
        assertTrue(answer.markdown().contains("你想讲哪一篇"));
        verify(retrievalService, never()).retrieve(anyString(), anyString(), anyInt());
        verifyNoInteractions(llmProviderRouter);
    }

    @Test
    void followUpWithMultipleFocusAsksForClarificationAndDoesNotRetrieve() {
        when(valueOps.get("paperloom:chat:focus:c1")).thenReturn("""
                {"lastIntent":"PAPER_RECOMMENDATION","lastPaperIds":["paper-a","paper-b"],"lastPaperTitles":["Title paper-a","Title paper-b"]}
                """);

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "详细讲解");

        assertEquals(PaperAnswerService.Intent.CLARIFY, answer.intent());
        assertTrue(answer.markdown().contains("你想讲第几篇"));
        verify(retrievalService, never()).retrieve(anyString(), anyString(), anyInt());
        verifyNoInteractions(llmProviderRouter);
    }

    @Test
    void followUpWithSingleFocusScopesQaToFocusedPaper() {
        when(valueOps.get("paperloom:chat:focus:c1")).thenReturn("""
                {"lastIntent":"PAPER_RECOMMENDATION","lastPaperIds":["paper-a"],"lastPaperTitles":["Title paper-a"]}
                """);
        when(retrievalService.retrieve(eq("Title paper-a 详细讲解"), eq("u1"), eq(40))).thenReturn(retrievalResult(List.of(
                result("paper-b", 1, "Distractor evidence", 0.95),
                result("paper-a", 1, "Focused paper evidence", 0.8)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "**结论**\n这篇论文讨论 Agent Harness。{{E1}}",
                        List.of(),
                        Map.of("role", "assistant", "content", "**结论**\n这篇论文讨论 Agent Harness。{{E1}}"),
                        "stop",
                        10,
                        5
                ));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "详细讲解");

        assertEquals(PaperAnswerService.Intent.PAPER_QA, answer.intent());
        assertEquals("paper-a", answer.referenceMappings().get(1).paperId());
        assertTrue(answer.markdown().contains("[1]"));
    }

    @Test
    void qaFallsBackWhenLlmUsesUnknownEvidenceToken() {
        when(retrievalService.retrieve(anyString(), eq("u1"), eq(40))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Real evidence", 0.9)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "这是一个无效引用 {{E99}}",
                        List.of(),
                        Map.of("role", "assistant", "content", "这是一个无效引用 {{E99}}"),
                        "stop",
                        10,
                        5
                ));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "这篇论文和 agent 有什么关系");

        assertEquals(PaperAnswerService.Intent.PAPER_QA, answer.intent());
        assertTrue(answer.fallbackUsed());
        assertTrue(answer.markdown().contains("[1]"));
        assertEquals(1, answer.referenceMappings().size());
    }

    @Test
    void qaFallsBackWhenLlmUsesNakedCitation() {
        when(retrievalService.retrieve(anyString(), eq("u1"), eq(40))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Real evidence", 0.9)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "这是模型自己编的引用 [1]",
                        List.of(),
                        Map.of("role", "assistant", "content", "这是模型自己编的引用 [1]"),
                        "stop",
                        10,
                        5
                ));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "这篇论文和 agent 有什么关系");

        assertTrue(answer.fallbackUsed());
        assertTrue(answer.markdown().contains("[1]"));
        assertEquals("paper-a", answer.referenceMappings().get(1).paperId());
    }

    @Test
    void qaFallsBackWhenLlmUsesLegacySourceCitation() {
        when(retrievalService.retrieve(anyString(), eq("u1"), eq(40))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Real evidence", 0.9)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "这是旧格式引用 来源#1",
                        List.of(),
                        Map.of("role", "assistant", "content", "这是旧格式引用 来源#1"),
                        "stop",
                        10,
                        5
                ));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "这篇论文和 agent 有什么关系");

        assertTrue(answer.fallbackUsed());
        assertTrue(answer.markdown().contains("[1]"));
        assertEquals("paper-a", answer.referenceMappings().get(1).paperId());
    }

    @Test
    void qaRendersValidEvidenceTokensIntoCitationMappings() {
        when(retrievalService.retrieve(anyString(), eq("u1"), eq(40))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Agent harness changes retrieval behavior.", 0.9)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "**结论**\nAgent Harness 会改变检索行为。{{E1}}",
                        List.of(),
                        Map.of("role", "assistant", "content", "**结论**\nAgent Harness 会改变检索行为。{{E1}}"),
                        "stop",
                        10,
                        5
                ));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "这篇论文和 agent 有什么关系");

        assertEquals(PaperAnswerService.Intent.PAPER_QA, answer.intent());
        assertTrue(answer.markdown().contains("[1]"));
        assertTrue(!answer.markdown().contains("{{E1}}"));
        assertEquals("paper-a", answer.referenceMappings().get(1).paperId());
    }

    @Test
    void qaNumbersCitationsByEvidenceRankNotMentionOrder() {
        when(retrievalService.retrieve(anyString(), eq("u1"), eq(40))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Stronger evidence.", 0.9),
                result("paper-b", 1, "Weaker evidence.", 0.7)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "**依据**\n- 次要证据。{{E2}}\n- 主要证据。{{E1}}",
                        List.of(),
                        Map.of("role", "assistant", "content", "**依据**\n- 次要证据。{{E2}}\n- 主要证据。{{E1}}"),
                        "stop",
                        10,
                        5
                ));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "这篇论文和 agent 有什么关系");

        assertTrue(answer.markdown().contains("次要证据。[2]"));
        assertTrue(answer.markdown().contains("主要证据。[1]"));
        assertEquals("paper-a", answer.referenceMappings().get(1).paperId());
        assertEquals("paper-b", answer.referenceMappings().get(2).paperId());
    }

    @Test
    void qaFallsBackWhenLlmMentionsCandidateOutsideLedgerTitleWhitelist() {
        when(retrievalService.retrieve(anyString(), eq("u1"), eq(40))).thenReturn(retrievalResult(List.of(
                result("paper-a", 1, "Real evidence", 0.9)
        )));
        when(llmProviderRouter.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "《Fake Agent Paper》也很相关。{{E1}}",
                        List.of(),
                        Map.of("role", "assistant", "content", "《Fake Agent Paper》也很相关。{{E1}}"),
                        "stop",
                        10,
                        5
                ));

        PaperAnswerService.AnswerResult answer = service.answer("u1", "c1", "这篇论文和 agent 有什么关系");

        assertTrue(answer.fallbackUsed());
        assertTrue(!answer.markdown().contains("Fake Agent Paper"));
        assertTrue(answer.markdown().contains("[1]"));
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
