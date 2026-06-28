package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.EvidenceLedgerService;
import com.yizhaoqi.smartpai.service.PaperPageWindowService;
import com.yizhaoqi.smartpai.service.PaperQueryPlanner;
import com.yizhaoqi.smartpai.service.PaperRetrievalService;
import com.yizhaoqi.smartpai.service.RetrievalBudget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceBackedPageWindowHarnessTest {

    @Test
    void expandsProductionHitsIntoInspectedPageWindowLedger() {
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        PaperPageWindowService pageWindowService = mock(PaperPageWindowService.class);
        RetrievalBudget budget = RetrievalBudget.forQa();
        String query = "Which baseline improves F1?";
        SearchResult firstStageHit = result(
                "paper-a",
                12,
                5,
                "Evaluation",
                "TEXT",
                "The evaluation section discusses baselines and points to nearby result tables.",
                0.8
        );
        SearchResult inspectedEvidence = result(
                "paper-a",
                13,
                4,
                "Experiments",
                "TABLE",
                "Table 2 reports the retrieval baseline improves F1 by four points over the sparse baseline.",
                1.0
        );
        when(retrievalService.retrieve(query, "u1", budget, List.of("paper-a")))
                .thenReturn(retrievalResult(List.of(firstStageHit)));
        when(pageWindowService.inspectPageWindow("paper-a", 5, 1))
                .thenReturn(List.of(inspectedEvidence));

        ServiceBackedPageWindowHarness harness = new ServiceBackedPageWindowHarness(
                retrievalService,
                pageWindowService,
                new EvidenceLedgerService()
        );

        ServiceBackedPageWindowHarness.HarnessResult result = harness.run(
                query,
                "u1",
                budget,
                List.of("paper-a"),
                new ServiceBackedPageWindowHarness.Options(3, 1, "scientific-qa")
        );

        assertEquals(1, result.windows().size());
        assertEquals("paper-a:5", result.windows().get(0).centerPageKey());
        assertEquals(1, result.inspections().size());
        assertEquals(List.of(4), result.inspections().get(0).pageNumbers());
        assertEquals(1, result.ledger().evidence().size());
        assertTrue(result.ledger().evidence().get(0).matchedText().contains("improves F1"));
        assertEquals("paper-a", result.ledger().sourceSet().get(0).paperId());
        verify(retrievalService).retrieve(query, "u1", budget, List.of("paper-a"));
        verify(pageWindowService).inspectPageWindow("paper-a", 5, 1);
    }

    @Test
    void canLocatePagesFromFullScopedPaperWithoutFirstStageRetrieval() {
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        PaperPageWindowService pageWindowService = mock(PaperPageWindowService.class);
        RetrievalBudget budget = RetrievalBudget.forQa();
        String query = "Which baseline improves F1?";
        SearchResult intro = result(
                "paper-a",
                1,
                1,
                "Introduction",
                "TEXT",
                "The paper introduces the retrieval problem.",
                0.5
        );
        SearchResult evidencePage = result(
                "paper-a",
                9,
                5,
                "Experiments",
                "TABLE",
                "Table 2 reports the retrieval baseline improves F1 by four points.",
                1.0
        );
        when(pageWindowService.inspectPaper("paper-a")).thenReturn(List.of(intro, evidencePage));
        when(pageWindowService.inspectPageWindow("paper-a", 5, 1)).thenReturn(List.of(evidencePage));

        ServiceBackedPageWindowHarness harness = new ServiceBackedPageWindowHarness(
                retrievalService,
                pageWindowService,
                new EvidenceLedgerService()
        );

        ServiceBackedPageWindowHarness.HarnessResult result = harness.run(
                query,
                "u1",
                budget,
                List.of("paper-a"),
                new ServiceBackedPageWindowHarness.Options(1, 1, "scientific-qa", "scoped-paper")
        );

        assertEquals("SCOPED_PAPER", result.candidateSource());
        assertEquals(1, result.windows().size());
        assertEquals("paper-a:5", result.windows().get(0).centerPageKey());
        assertTrue(result.ledger().evidence().get(0).matchedText().contains("improves F1"));
        verify(retrievalService, never()).retrieve(query, "u1", budget, List.of("paper-a"));
        verify(pageWindowService).inspectPaper("paper-a");
        verify(pageWindowService).inspectPageWindow("paper-a", 5, 1);
    }

    @Test
    void evalScopedPaperUsesEvalCorpusPageWindowReader() {
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        PaperPageWindowService pageWindowService = mock(PaperPageWindowService.class);
        EvalCorpusPageWindowService evalPageWindowService = mock(EvalCorpusPageWindowService.class);
        RetrievalBudget budget = RetrievalBudget.forQa();
        String query = "Which baseline improves F1?";
        SearchResult intro = result(
                "qasper:paper-a",
                1,
                1,
                "Introduction",
                "TEXT",
                "The paper introduces the retrieval problem.",
                0.5
        );
        SearchResult evidencePage = result(
                "qasper:paper-a",
                9,
                5,
                "Experiments",
                "TABLE",
                "Table 2 reports the retrieval baseline improves F1 by four points.",
                1.0
        );
        when(evalPageWindowService.inspectPaper(RetrievalCorpus.EVAL_QASPER, "qasper:paper-a"))
                .thenReturn(List.of(intro, evidencePage));
        when(evalPageWindowService.inspectPageWindow(RetrievalCorpus.EVAL_QASPER, "qasper:paper-a", 5, 1))
                .thenReturn(List.of(evidencePage));

        ServiceBackedPageWindowHarness harness = new ServiceBackedPageWindowHarness(
                retrievalService,
                pageWindowService,
                evalPageWindowService,
                new EvidenceLedgerService()
        );

        ServiceBackedPageWindowHarness.HarnessResult result = harness.run(
                query,
                "eval-user",
                budget,
                List.of("qasper:paper-a"),
                new ServiceBackedPageWindowHarness.Options(
                        1,
                        1,
                        "scientific-qa",
                        "scoped-paper",
                        RetrievalCorpus.EVAL_QASPER
                )
        );

        assertEquals("SCOPED_PAPER", result.candidateSource());
        assertEquals("qasper:paper-a:5", result.windows().get(0).centerPageKey());
        assertTrue(result.ledger().evidence().get(0).matchedText().contains("improves F1"));
        verify(retrievalService, never()).retrieve(query, "eval-user", budget, List.of("qasper:paper-a"));
        verify(pageWindowService, never()).inspectPaper("qasper:paper-a");
        verify(pageWindowService, never()).inspectPageWindow("qasper:paper-a", 5, 1);
        verify(evalPageWindowService).inspectPaper(RetrievalCorpus.EVAL_QASPER, "qasper:paper-a");
        verify(evalPageWindowService).inspectPageWindow(RetrievalCorpus.EVAL_QASPER, "qasper:paper-a", 5, 1);
    }

    @Test
    void evalScopedPaperFailsFastWithoutEvalCorpusReader() {
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        PaperPageWindowService pageWindowService = mock(PaperPageWindowService.class);
        ServiceBackedPageWindowHarness harness = new ServiceBackedPageWindowHarness(
                retrievalService,
                pageWindowService,
                new EvidenceLedgerService()
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                harness.run(
                        "Which baseline improves F1?",
                        "eval-user",
                        RetrievalBudget.forQa(),
                        List.of("qasper:paper-a"),
                        new ServiceBackedPageWindowHarness.Options(
                                1,
                                1,
                                "scientific-qa",
                                "scoped-paper",
                                RetrievalCorpus.EVAL_QASPER
                        )
                )
        );

        assertTrue(error.getMessage().contains("EvalCorpusPageWindowService"));
        verify(retrievalService, never()).retrieve(
                "Which baseline improves F1?",
                "eval-user",
                RetrievalBudget.forQa(),
                List.of("qasper:paper-a")
        );
        verify(pageWindowService, never()).inspectPaper("qasper:paper-a");
    }

    @Test
    void targetAwarePlannerVariantUsesExplicitTargetTermBoost() {
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        PaperPageWindowService pageWindowService = mock(PaperPageWindowService.class);
        SearchResult genericEvaluation = result(
                "paper-a",
                41,
                4,
                "Evaluation Results",
                "TEXT",
                "We compare related approaches against evaluation datasets. "
                        + "Experiments report results and comparison tables.",
                1.0
        );
        SearchResult baselineEvidence = result(
                "paper-a",
                91,
                9,
                "Baselines",
                "TEXT",
                "The baseline was a majority classifier used for comparison.",
                1.0
        );
        when(pageWindowService.inspectPaper("paper-a"))
                .thenReturn(List.of(genericEvaluation, baselineEvidence));
        when(pageWindowService.inspectPageWindow("paper-a", 9, 0))
                .thenReturn(List.of(baselineEvidence));

        ServiceBackedPageWindowHarness harness = new ServiceBackedPageWindowHarness(
                retrievalService,
                pageWindowService,
                new EvidenceLedgerService()
        );

        ServiceBackedPageWindowHarness.HarnessResult result = harness.run(
                "what was the baseline?",
                "u1",
                RetrievalBudget.forQa(),
                List.of("paper-a"),
                new ServiceBackedPageWindowHarness.Options(1, 0, "scientific-qa-targets", "scoped-paper")
        );

        assertEquals("paper-a:9", result.windows().get(0).centerPageKey());
        assertTrue(result.windows().get(0).reasons().contains("target:baseline"));
        verify(retrievalService, never()).retrieve(
                "what was the baseline?",
                "u1",
                RetrievalBudget.forQa(),
                List.of("paper-a")
        );
        verify(pageWindowService).inspectPageWindow("paper-a", 9, 0);
    }

    @Test
    void chunkAwarePlannerVariantCarriesCandidateChunkSignalIntoPageReasons() {
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        PaperPageWindowService pageWindowService = mock(PaperPageWindowService.class);
        SearchResult genericMethodPage = result(
                "paper-a",
                41,
                4,
                "Methods",
                "TEXT",
                "The method section describes architectures, algorithms, approaches, and evaluation.",
                1.0
        );
        SearchResult answerBearingChunk = result(
                "paper-a",
                91,
                9,
                "Implementation Details",
                "TEXT",
                "The gated calibration method uses held-out evidence scores to choose the final policy.",
                1.0
        );
        when(pageWindowService.inspectPaper("paper-a"))
                .thenReturn(List.of(genericMethodPage, answerBearingChunk));
        when(pageWindowService.inspectPageWindow("paper-a", 9, 0))
                .thenReturn(List.of(answerBearingChunk));

        ServiceBackedPageWindowHarness harness = new ServiceBackedPageWindowHarness(
                retrievalService,
                pageWindowService,
                new EvidenceLedgerService()
        );

        ServiceBackedPageWindowHarness.HarnessResult result = harness.run(
                "Which method uses gated calibration?",
                "u1",
                RetrievalBudget.forQa(),
                List.of("paper-a"),
                new ServiceBackedPageWindowHarness.Options(1, 0, "scientific-qa-chunk-window", "scoped-paper")
        );

        assertEquals("paper-a:9", result.windows().get(0).centerPageKey());
        assertTrue(result.windows().get(0).reasons().stream().anyMatch(reason -> reason.startsWith("chunk:")));
        verify(retrievalService, never()).retrieve(
                "Which method uses gated calibration?",
                "u1",
                RetrievalBudget.forQa(),
                List.of("paper-a")
        );
        verify(pageWindowService).inspectPageWindow("paper-a", 9, 0);
    }

    @Test
    void diverseWindowPlannerAvoidsOverlappingInspectionWindows() {
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        PaperPageWindowService pageWindowService = mock(PaperPageWindowService.class);
        SearchResult strongestPage = result(
                "paper-a",
                50,
                5,
                "Evaluation",
                "TEXT",
                "The evaluation compares baselines and reports F1 results against related approaches.",
                1.0
        );
        SearchResult overlappingPage = result(
                "paper-a",
                60,
                6,
                "Evaluation Results",
                "TEXT",
                "More baseline comparison results and evaluation details appear here.",
                1.0
        );
        SearchResult lowerScoredEvidencePage = result(
                "paper-a",
                200,
                20,
                "Baselines",
                "TEXT",
                "The baseline was a majority classifier.",
                1.0
        );
        when(pageWindowService.inspectPaper("paper-a"))
                .thenReturn(List.of(strongestPage, overlappingPage, lowerScoredEvidencePage));
        when(pageWindowService.inspectPageWindow("paper-a", 5, 1))
                .thenReturn(List.of(strongestPage));
        when(pageWindowService.inspectPageWindow("paper-a", 20, 1))
                .thenReturn(List.of(lowerScoredEvidencePage));

        ServiceBackedPageWindowHarness harness = new ServiceBackedPageWindowHarness(
                retrievalService,
                pageWindowService,
                new EvidenceLedgerService()
        );

        ServiceBackedPageWindowHarness.HarnessResult result = harness.run(
                "What baseline was used?",
                "u1",
                RetrievalBudget.forQa(),
                List.of("paper-a"),
                new ServiceBackedPageWindowHarness.Options(2, 1, "scientific-qa-diverse-windows", "scoped-paper")
        );

        assertEquals(List.of("paper-a:5", "paper-a:20"), result.windows().stream()
                .map(PaperPageWindow::centerPageKey)
                .toList());
        assertTrue(result.ledger().evidence().stream()
                .anyMatch(evidence -> evidence.matchedText().contains("majority classifier")));
        verify(retrievalService, never()).retrieve(
                "What baseline was used?",
                "u1",
                RetrievalBudget.forQa(),
                List.of("paper-a")
        );
        verify(pageWindowService).inspectPageWindow("paper-a", 5, 1);
        verify(pageWindowService).inspectPageWindow("paper-a", 20, 1);
    }

    @Test
    void centerDiversePlannerAllowsEdgeOverlappingInspectionWindows() {
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        PaperPageWindowService pageWindowService = mock(PaperPageWindowService.class);
        SearchResult centerFive = result(
                "paper-a",
                50,
                5,
                "Evaluation",
                "TEXT",
                "The baseline evaluation compare comparison related approaches against experiments results.",
                1.0
        );
        SearchResult centerSix = result(
                "paper-a",
                60,
                6,
                "Evaluation",
                "TEXT",
                "The baseline evaluation includes additional notes.",
                1.0
        );
        SearchResult centerSeven = result(
                "paper-a",
                70,
                7,
                "Implementation Details",
                "TEXT",
                "The majority classifier served as the initial system.",
                1.0
        );
        when(pageWindowService.inspectPaper("paper-a"))
                .thenReturn(List.of(centerFive, centerSix, centerSeven));
        when(pageWindowService.inspectPageWindow("paper-a", 5, 1))
                .thenReturn(List.of(centerFive));
        when(pageWindowService.inspectPageWindow("paper-a", 7, 1))
                .thenReturn(List.of(centerSeven));

        ServiceBackedPageWindowHarness harness = new ServiceBackedPageWindowHarness(
                retrievalService,
                pageWindowService,
                new EvidenceLedgerService()
        );

        ServiceBackedPageWindowHarness.HarnessResult result = harness.run(
                "What baseline was used?",
                "u1",
                RetrievalBudget.forQa(),
                List.of("paper-a"),
                new ServiceBackedPageWindowHarness.Options(2, 1, "scientific-qa-center-diverse-windows", "scoped-paper")
        );

        assertEquals(List.of("paper-a:5", "paper-a:7"), result.windows().stream()
                .map(PaperPageWindow::centerPageKey)
                .toList());
        assertTrue(result.ledger().evidence().stream()
                .anyMatch(evidence -> evidence.matchedText().contains("majority classifier")));
        verify(pageWindowService).inspectPageWindow("paper-a", 5, 1);
        verify(pageWindowService).inspectPageWindow("paper-a", 7, 1);
    }

    private PaperRetrievalService.RetrievalResult retrievalResult(List<SearchResult> results) {
        PaperQueryPlanner.RetrievalPlan plan = new PaperQueryPlanner.RetrievalPlan(
                "Which baseline improves F1?",
                "Which baseline improves F1?",
                PaperQueryPlanner.RetrievalIntent.EXPERIMENT_RESULT,
                List.of("Which baseline improves F1?"),
                List.of("TEXT", "TABLE"),
                List.of("evaluation", "results")
        );
        return new PaperRetrievalService.RetrievalResult(plan, results, Map.of("hybrid", results.size()));
    }

    private SearchResult result(String paperId,
                                int chunkId,
                                int pageNumber,
                                String sectionTitle,
                                String sourceKind,
                                String text,
                                double score) {
        SearchResult result = new SearchResult(paperId, chunkId, text, score);
        result.setPaperTitle("Adaptive Retrieval");
        result.setOriginalFilename("adaptive.pdf");
        result.setPageNumber(pageNumber);
        result.setSectionTitle(sectionTitle);
        result.setSourceKind(sourceKind);
        result.setMatchedChunkText(text);
        return result;
    }
}
