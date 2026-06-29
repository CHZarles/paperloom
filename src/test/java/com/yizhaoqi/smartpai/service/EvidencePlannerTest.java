package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EvidencePlannerTest {

    @Test
    void librarySearchUsesStructuredQueryWithoutPhraseCleaning() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        EvidencePlanner planner = new EvidencePlanner(llm, new ObjectMapper());

        PlannerAction action = planner.plan(new PlannerContext(
                "u1",
                "推荐一些和 agent 相关的论文",
                PaperAnswerService.Intent.LIBRARY_SEARCH,
                SourceScope.auto(),
                EvidenceLedger.empty()
        ));

        assertEquals(PlannerActionType.DISCOVER_PAPERS, action.type());
        assertEquals("推荐一些和 agent 相关的论文", action.query());
    }

    @Test
    void autoSourceQaIsNotExecutableByPlanner() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        when(llm.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "{\"action\":\"SEARCH_EVIDENCE\",\"query\":\"这篇论文讲了什么\",\"reason\":\"paper qa\"}",
                        List.of(),
                        Map.of(),
                        "stop",
                        10,
                        5
                ));
        EvidencePlanner planner = new EvidencePlanner(llm, new ObjectMapper());

        PlannerAction action = planner.plan(new PlannerContext(
                "u1",
                "这篇论文讲了什么",
                PaperAnswerService.Intent.AUTO_SOURCE_QA,
                SourceScope.auto(),
                EvidenceLedger.empty()
        ));

        assertEquals(PlannerActionType.ASK_CLARIFICATION, action.type());
        assertEquals("auto_source_unresolved", action.reason());
    }

    @Test
    void paperQaPlansSearchEvidenceWithoutLlmPlanner() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        EvidencePlanner planner = new EvidencePlanner(llm, new ObjectMapper());

        PlannerAction action = planner.plan(new PlannerContext(
                "u1",
                "According to the LoRA paper, what limitation of full fine-tuning does LoRA address, and how does the low-rank update work?",
                PaperAnswerService.Intent.PAPER_QA,
                SourceScope.auto(),
                EvidenceLedger.empty()
        ));

        assertEquals(PlannerActionType.SEARCH_EVIDENCE, action.type());
        assertEquals(
                "According to the LoRA paper, what limitation of full fine-tuning does LoRA address, and how does the low-rank update work?",
                action.query()
        );
        verifyNoInteractions(llm);
    }

    @Test
    void autoSourceQaDoesNotLetPlannerChoosePaperDiscovery() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        when(llm.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "{\"action\":\"DISCOVER_PAPERS\",\"query\":\"agent\",\"reason\":\"topic discovery\"}",
                        List.of(),
                        Map.of(),
                        "stop",
                        10,
                        5
                ));
        EvidencePlanner planner = new EvidencePlanner(llm, new ObjectMapper());

        PlannerAction action = planner.plan(new PlannerContext(
                "u1",
                "有什么agent相关的论文吗？",
                PaperAnswerService.Intent.AUTO_SOURCE_QA,
                SourceScope.auto(),
                EvidenceLedger.empty()
        ));

        assertEquals(PlannerActionType.ASK_CLARIFICATION, action.type());
        assertEquals("auto_source_unresolved", action.reason());
    }

    @Test
    void obviousReferenceQueryPlansInspectReferenceWithoutLlm() {
        EvidencePlanner planner = new EvidencePlanner(mock(LlmProviderRouter.class), new ObjectMapper());

        PlannerAction action = planner.plan(new PlannerContext(
                "u1",
                "解释 [1]",
                PaperAnswerService.Intent.REFERENCE_QA,
                new SourceScope(ScopeMode.REFERENCE_SOURCE, List.of(), 1, null),
                EvidenceLedger.empty()
        ));

        assertEquals(PlannerActionType.INSPECT_REFERENCE, action.type());
        assertEquals(1, action.referenceNumber());
    }

    @Test
    void parsesInspectPageActionFromLlmOutput() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        when(llm.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "{\"action\":\"INSPECT_PAGE\",\"query\":\"inspect page\",\"reason\":\"nearby evidence\",\"pageNumber\":4,\"windowRadius\":2}",
                        List.of(),
                        Map.of(),
                        "stop",
                        10,
                        5
                ));
        EvidencePlanner planner = new EvidencePlanner(llm, new ObjectMapper());

        PlannerAction action = planner.plan(new PlannerContext(
                "u1",
                "inspect page 4",
                PaperAnswerService.Intent.SMALLTALK,
                SourceScope.manual(List.of("paper-a")),
                EvidenceLedger.empty()
        ));

        assertEquals(PlannerActionType.INSPECT_PAGE, action.type());
        assertEquals(4, action.pageNumber());
        assertEquals(2, action.windowRadius());
        assertEquals(List.of("paper-a"), action.paperIds());
    }
}
