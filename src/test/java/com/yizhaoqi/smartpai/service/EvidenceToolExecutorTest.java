package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.Paper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceToolExecutorTest {

    @Test
    void discoverPapersBuildsLedgerFromRetrievalResults() {
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        PaperService paperService = mock(PaperService.class);
        ConversationService conversationService = mock(ConversationService.class);
        EvidenceToolExecutor executor = new EvidenceToolExecutor(
                retrievalService,
                paperService,
                conversationService,
                new EvidenceLedgerService()
        );
        when(retrievalService.retrieve(eq("agent grep"), eq("u1"), any(RetrievalBudget.class)))
                .thenReturn(retrievalResult(List.of(
                        result("paper-a", 1, "Agent harnesses can invoke grep as a native lexical search tool.", 0.9),
                        result("paper-b", 1, "Tool-using agents combine search actions with intermediate reasoning.", 0.8)
                )));

        EvidenceToolResult result = executor.execute(
                "u1",
                "c1",
                new PlannerAction(PlannerActionType.DISCOVER_PAPERS, "agent grep", "find papers", List.of(), null),
                SourceScope.auto()
        );

        assertEquals(2, result.ledger().sourceSet().size());
        assertEquals(2, result.ledger().evidence().size());
        verify(retrievalService).retrieve(eq("agent grep"), eq("u1"), any(RetrievalBudget.class));
    }

    @Test
    void searchEvidenceRespectsManualSourceScope() {
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        EvidenceToolExecutor executor = new EvidenceToolExecutor(
                retrievalService,
                mock(PaperService.class),
                mock(ConversationService.class),
                new EvidenceLedgerService()
        );
        SourceScope scope = SourceScope.manual(List.of("paper-a"));
        when(retrievalService.retrieve(eq("method"), eq("u1"), any(RetrievalBudget.class), eq(List.of("paper-a"))))
                .thenReturn(retrievalResult(List.of(
                        result("paper-a", 2, "The method describes how the harness controls retrieval.", 0.9)
                )));

        EvidenceToolResult result = executor.execute(
                "u1",
                "c1",
                new PlannerAction(PlannerActionType.SEARCH_EVIDENCE, "method", "search scoped paper", List.of("paper-a"), null),
                scope
        );

        assertEquals(List.of("paper-a"), result.ledger().sourceSet().stream().map(PaperSource::paperId).toList());
        verify(retrievalService).retrieve(eq("method"), eq("u1"), any(RetrievalBudget.class), eq(List.of("paper-a")));
    }

    @Test
    void searchEvidenceUsesHighRecallBudgetFromScope() {
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        EvidenceToolExecutor executor = new EvidenceToolExecutor(
                retrievalService,
                mock(PaperService.class),
                mock(ConversationService.class),
                new EvidenceLedgerService()
        );
        SourceScope scope = SourceScope.manual(List.of("paper-a"), RetrievalBudgetProfile.HIGH_RECALL);
        when(retrievalService.retrieve(eq("method"), eq("u1"), any(RetrievalBudget.class), eq(List.of("paper-a"))))
                .thenReturn(retrievalResult(List.of(
                        result("paper-a", 2, "The method describes how the harness controls retrieval.", 0.9)
                )));

        executor.execute(
                "u1",
                "c1",
                new PlannerAction(PlannerActionType.SEARCH_EVIDENCE, "method", "search scoped paper", List.of("paper-a"), null),
                scope
        );

        ArgumentCaptor<RetrievalBudget> budget = ArgumentCaptor.forClass(RetrievalBudget.class);
        verify(retrievalService).retrieve(eq("method"), eq("u1"), budget.capture(), eq(List.of("paper-a")));
        assertEquals(5, budget.getValue().pageWindowTopK());
        assertEquals("scientific-qa-diverse-windows", budget.getValue().pageWindowPlanner());
    }

    @Test
    void inspectReferenceBuildsLedgerFromLatestReferenceMapping() {
        ConversationService conversationService = mock(ConversationService.class);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("paperId", "paper-a");
        detail.put("paperTitle", "Title paper-a");
        detail.put("originalFilename", "paper-a.pdf");
        detail.put("matchedChunkText", "Agent harnesses dynamically choose retrieval tools and inspect intermediate results.");
        detail.put("chunkId", 7);
        detail.put("pageNumber", 5);
        when(conversationService.findLatestReferenceDetail(1L, "c1", 1)).thenReturn(Optional.of(detail));
        EvidenceToolExecutor executor = new EvidenceToolExecutor(
                mock(PaperRetrievalService.class),
                mock(PaperService.class),
                conversationService,
                new EvidenceLedgerService()
        );

        EvidenceToolResult result = executor.execute(
                "1",
                "c1",
                new PlannerAction(PlannerActionType.INSPECT_REFERENCE, "解释 [1]", "reference", List.of(), 1),
                SourceScope.reference(1, null)
        );

        assertEquals(1, result.ledger().evidence().size());
        assertEquals("paper-a", result.ledger().evidence().get(0).paperId());
    }

    @Test
    void inspectPageBuildsLedgerFromPageWindowChunks() {
        PaperPageWindowService pageWindowService = mock(PaperPageWindowService.class);
        SearchResult pageChunk = result("paper-a", 7, "Table 2 reports the benchmark result on page four.", 1.0);
        pageChunk.setPageNumber(4);
        when(pageWindowService.inspectPageWindow("paper-a", 4, 1)).thenReturn(List.of(pageChunk));
        EvidenceToolExecutor executor = new EvidenceToolExecutor(
                mock(PaperRetrievalService.class),
                mock(PaperService.class),
                mock(ConversationService.class),
                new EvidenceLedgerService(),
                pageWindowService
        );

        EvidenceToolResult result = executor.execute(
                "u1",
                "c1",
                new PlannerAction(PlannerActionType.INSPECT_PAGE, "", "inspect page", List.of("paper-a"), null, 4, 1),
                SourceScope.manual(List.of("paper-a"))
        );

        assertEquals(PlannerActionType.INSPECT_PAGE, result.actionType());
        assertEquals(1, result.ledger().evidence().size());
        assertEquals(4, result.ledger().evidence().get(0).pageNumber());
        verify(pageWindowService).inspectPageWindow("paper-a", 4, 1);
    }

    @Test
    void listLibraryReturnsSourceSetWithoutEvidence() {
        PaperService paperService = mock(PaperService.class);
        Paper paper = new Paper();
        paper.setPaperId("paper-a");
        paper.setPaperTitle("Agent Harness Paper");
        paper.setOriginalFilename("agent.pdf");
        when(paperService.getAccessiblePapers("u1", null)).thenReturn(List.of(paper));
        EvidenceToolExecutor executor = new EvidenceToolExecutor(
                mock(PaperRetrievalService.class),
                paperService,
                mock(ConversationService.class),
                new EvidenceLedgerService()
        );

        EvidenceToolResult result = executor.execute(
                "u1",
                "c1",
                new PlannerAction(PlannerActionType.LIST_LIBRARY, "", "list", List.of(), null),
                SourceScope.auto()
        );

        assertEquals(1, result.ledger().sourceSet().size());
        assertEquals(0, result.ledger().evidence().size());
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
        SearchResult result = new SearchResult(paperId, chunkId, text, score);
        result.setPaperTitle("Title " + paperId);
        result.setOriginalFilename(paperId + ".pdf");
        result.setMatchedChunkText(text);
        result.setSourceKind("TEXT");
        result.setPageNumber(chunkId);
        return result;
    }
}
