package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.ConversationService;
import com.yizhaoqi.smartpai.service.EvidenceAnswerGenerator;
import com.yizhaoqi.smartpai.service.EvidenceLedgerService;
import com.yizhaoqi.smartpai.service.EvidencePlanner;
import com.yizhaoqi.smartpai.service.EvidenceToolExecutor;
import com.yizhaoqi.smartpai.service.EvidenceVerifier;
import com.yizhaoqi.smartpai.service.LlmProviderRouter;
import com.yizhaoqi.smartpai.service.PaperAnswerService;
import com.yizhaoqi.smartpai.service.PaperChatRouter;
import com.yizhaoqi.smartpai.service.PaperQueryPlanner;
import com.yizhaoqi.smartpai.service.PaperRetrievalService;
import com.yizhaoqi.smartpai.service.PaperService;
import com.yizhaoqi.smartpai.service.RetrievalBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagBenchmarkRunnerTest {

    private static final String GREP_PAPER_ID = "6da506ce952a2c4d85928b3e0052f4f6";
    private PaperRetrievalService retrievalService;
    private PaperService paperService;
    private ConversationService conversationService;
    private LlmProviderRouter llmProviderRouter;
    private PaperAnswerService answerService;

    @BeforeEach
    void setUp() {
        retrievalService = mock(PaperRetrievalService.class);
        paperService = mock(PaperService.class);
        conversationService = mock(ConversationService.class);
        llmProviderRouter = mock(LlmProviderRouter.class);
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        ObjectMapper objectMapper = new ObjectMapper();
        EvidenceLedgerService ledgerService = new EvidenceLedgerService();
        EvidenceVerifier verifier = new EvidenceVerifier();
        answerService = new PaperAnswerService(
                retrievalService,
                paperService,
                conversationService,
                llmProviderRouter,
                redisTemplate,
                objectMapper,
                new PaperChatRouter(),
                new EvidencePlanner(llmProviderRouter, objectMapper),
                ledgerService,
                new EvidenceAnswerGenerator(llmProviderRouter, verifier),
                verifier,
                new EvidenceToolExecutor(retrievalService, paperService, conversationService, ledgerService)
        );
        when(paperService.getAccessiblePapers("1", null)).thenReturn(List.of(paper()));
        when(retrievalService.retrieve(anyString(), eq("1"), any(RetrievalBudget.class))).thenAnswer(invocation -> {
            String query = invocation.getArgument(0, String.class);
            return retrievalResult(resultsFor(query));
        });
        when(retrievalService.retrieve(anyString(), eq("1"), any(RetrievalBudget.class), any())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0, String.class);
            return retrievalResult(resultsFor(query));
        });
        when(conversationService.findLatestReferenceDetail(1L, "bench", 1))
                .thenReturn(Optional.of(referenceDetail()));
        when(llmProviderRouter.completeReActTurn(eq("1"), any(), eq(List.of()), anyInt()))
                .thenAnswer(invocation -> llmTurnForMessages(invocation.getArgument(1)));
    }

    @Test
    void runsProductRescueSmokeCasesThroughPaperAnswerService() throws Exception {
        List<RagBenchmarkCase> cases = RagBenchmarkDataset.load(Path.of("eval/rag/product-rescue-smoke.jsonl"));

        RagBenchmarkRun run = new RagBenchmarkRunner(answerService, new RagBenchmarkEvaluator())
                .run("1", "bench", cases);

        assertEquals(cases.size(), run.verdicts().size());
        assertTrue(run.verdicts().stream().allMatch(RagBenchmarkVerdict::passed),
                () -> "benchmark failures: " + run.verdicts().stream()
                        .filter(verdict -> !verdict.passed())
                        .map(verdict -> verdict.caseId() + "=" + verdict.failures())
                        .toList());
    }

    private List<SearchResult> resultsFor(String query) {
        String normalized = query == null ? "" : query.toLowerCase();
        if (normalized.contains("高噪声") || normalized.contains("noise")) {
            return List.of(result(7,
                    "4.2 Experiment 2: Context Scaling with Increasing Noise",
                    "Experiment 2 studies context scaling with increasing noise and reports how agent harnesses behave as noise grows.",
                    0.95));
        }
        if (normalized.contains("概念") || normalized.contains("concept")) {
            return List.of(result(3,
                    "Core Concepts",
                    "Agent Harness, Agentic Search, Grep, Semantic Search, and Lexical Search are central concepts in the paper.",
                    0.9));
        }
        if (normalized.contains("讲了什么") || normalized.contains("summary")) {
            return List.of(
                    result(2,
                            "Abstract",
                            "The paper studies how agent harnesses reshape retrieval and search by combining Grep with contextual tool use.",
                            0.92),
                    result(1,
                            "Keywords",
                            "Agentic Search, Semantic Search, Lexical Search, Context Engineering, Agent Harnesses, LLM Evaluation, Grep",
                            0.91)
            );
        }
        return List.of(result(4,
                "Agent Harnesses",
                "The agent harness uses Grep as a native search primitive for retrieval and inspects intermediate search evidence.",
                0.9));
    }

    private LlmProviderRouter.ReActTurn llmTurnForMessages(List<Map<String, Object>> messages) {
        String user = messages.stream()
                .filter(message -> "user".equals(message.get("role")))
                .map(message -> String.valueOf(message.get("content")))
                .findFirst()
                .orElse("");
        String content;
        if (user.contains("高噪声")) {
            content = "**结论**\n论文在 Experiment 2 中讨论高噪声场景。{{E1}}\n\n"
                    + "**依据**\n- increasing noise 实验用于观察上下文噪声增长时的检索表现。{{E1}}\n\n"
                    + "**限制**\n这里只基于当前引用证据。";
        } else if (user.contains("相关概念")) {
            content = "**结论**\n论文中的相关概念包括 Agent Harness、Agentic Search、Grep、Semantic Search 和 Lexical Search。{{E1}}";
        } else if (user.contains("解释")) {
            content = "**结论**\n这个引用的意思是：Agent Harness 把 Grep 作为检索工具来使用，并检查中间证据。{{E1}}";
        } else {
            content = "**结论**\n这篇论文主要讨论 Agent Harness 如何改变检索和搜索过程。{{E1}}\n\n"
                    + "**依据**\n- 论文摘要说明它研究了 agent harness、retrieval 和 search 的关系。{{E1}}\n\n"
                    + "**限制**\n这里只概括当前可用证据。";
        }
        return new LlmProviderRouter.ReActTurn(
                content,
                List.of(),
                Map.of("role", "assistant", "content", content),
                "stop",
                10,
                5
        );
    }

    private Map<String, Object> referenceDetail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("paperId", GREP_PAPER_ID);
        detail.put("paperTitle", "Is Grep All You Need? How Agent Harnesses Reshape Agentic Search");
        detail.put("originalFilename", "grep-agent-harness.pdf");
        detail.put("matchedChunkText", "The agent harness uses Grep as a native search primitive for retrieval.");
        detail.put("chunkId", 4);
        detail.put("pageNumber", 5);
        detail.put("sectionTitle", "Agent Harnesses");
        detail.put("sourceKind", "TEXT");
        return detail;
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

    private SearchResult result(int chunkId, String sectionTitle, String text, double score) {
        return new SearchResult(
                GREP_PAPER_ID,
                chunkId,
                text,
                score,
                "1",
                "TEAM",
                false,
                "Is Grep All You Need? How Agent Harnesses Reshape Agentic Search",
                "grep-agent-harness.pdf",
                chunkId,
                text,
                "HYBRID",
                text,
                "PARAGRAPH",
                sectionTitle,
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

    private Paper paper() {
        Paper paper = new Paper();
        paper.setPaperId(GREP_PAPER_ID);
        paper.setPaperTitle("Is Grep All You Need? How Agent Harnesses Reshape Agentic Search");
        paper.setOriginalFilename("grep-agent-harness.pdf");
        return paper;
    }
}
