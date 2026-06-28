package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.EvidenceLedgerService;
import com.yizhaoqi.smartpai.service.PaperPageWindowService;
import com.yizhaoqi.smartpai.service.PaperQueryPlanner;
import com.yizhaoqi.smartpai.service.PaperRetrievalService;
import com.yizhaoqi.smartpai.service.RetrievalBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceBackedPageWindowBenchmarkCliTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void usesSpringStartupArgsThatAvoidEvalCliSideEffects() {
        List<String> args = Arrays.asList(ServiceBackedPageWindowBenchmarkCli.springStartupArgs());

        assertTrue(args.contains("--elasticsearch.init.enabled=false"));
        assertTrue(args.contains("--spring.kafka.listener.auto-startup=false"));
        assertTrue(args.contains("--admin.bootstrap.enabled=false"));
        assertTrue(args.contains("--paper.bootstrap.enabled=false"));
    }

    @Test
    void parsesServiceBackedPageWindowOptions() {
        ServiceBackedPageWindowBenchmarkCli.Options options =
                ServiceBackedPageWindowBenchmarkCli.Options.parse(new String[]{
                        "--cases", "eval/rag/product-rescue-smoke.jsonl",
                        "--runs-root", "eval/rag/runs",
                        "--registry", "eval/rag/harnesses.yaml",
                        "--cheatsheet", "eval/rag/CHEATSHEET.md",
                        "--harness-id", "service-backed-page-window",
                        "--dataset-id", "product-rescue-smoke",
                        "--run-id", "service-page-window-product",
                        "--started-at", "2026-06-24T20:30:00Z",
                        "--user-id", "eval-user",
                        "--retrieval-corpus", "PRODUCT_LIBRARY",
                        "--top-k", "5",
                        "--window-radius", "2",
                        "--query-planner", "scientific-qa",
                        "--candidate-source", "scoped-paper"
                });

        assertEquals(Path.of("eval/rag/product-rescue-smoke.jsonl"), options.casesPath());
        assertEquals(Path.of("eval/rag/runs"), options.runsRoot());
        assertEquals(Path.of("eval/rag/harnesses.yaml"), options.registryPath());
        assertEquals(Path.of("eval/rag/CHEATSHEET.md"), options.cheatsheetPath());
        assertEquals("service-backed-page-window", options.harnessId());
        assertEquals("product-rescue-smoke", options.datasetId());
        assertEquals("service-page-window-product", options.runId());
        assertEquals("2026-06-24T20:30:00Z", options.startedAt());
        assertEquals("eval-user", options.userId());
        assertEquals(RetrievalCorpus.PRODUCT_LIBRARY, options.retrievalCorpus());
        assertEquals(RetrievalBudget.forQa(), options.budget());
        assertEquals(5, options.topK());
        assertEquals(2, options.windowRadius());
        assertEquals("scientific-qa", options.queryPlanner());
        assertEquals("scoped-paper", options.candidateSource());
    }

    @Test
    void defaultsToProductPageWindowRunShape() {
        ServiceBackedPageWindowBenchmarkCli.Options options =
                ServiceBackedPageWindowBenchmarkCli.Options.parse(new String[]{
                        "--cases", "cases.jsonl",
                        "--retrieval-corpus", "PRODUCT_LIBRARY",
                        "--started-at", "2026-06-24T20:35:00Z"
                });

        assertEquals(Path.of("eval/rag/runs"), options.runsRoot());
        assertEquals(Path.of("eval/rag/harnesses.yaml"), options.registryPath());
        assertEquals(Path.of("eval/rag/CHEATSHEET.md"), options.cheatsheetPath());
        assertEquals("service-backed-page-window", options.harnessId());
        assertEquals("product-rescue-smoke", options.datasetId());
        assertEquals("2026-06-24T203500Z-service-backed-page-window-product-rescue-smoke", options.runId());
        assertEquals("eval-page-window-user", options.userId());
        assertEquals(RetrievalCorpus.PRODUCT_LIBRARY, options.retrievalCorpus());
        assertEquals(3, options.topK());
        assertEquals(1, options.windowRadius());
        assertEquals("scientific-qa", options.queryPlanner());
        assertEquals("first-stage", options.candidateSource());
    }

    @Test
    void parsesQasperPageWindowRunOnlyWithEvalQasperCorpus() {
        ServiceBackedPageWindowBenchmarkCli.Options options =
                ServiceBackedPageWindowBenchmarkCli.Options.parse(new String[]{
                        "--cases", "eval/rag/qasper/generated/qasper-dev-200-service-cases.jsonl",
                        "--dataset-id", "qasper-dev-200",
                        "--retrieval-corpus", "EVAL_QASPER",
                        "--started-at", "2026-06-24T20:35:00Z"
                });

        assertEquals("qasper-dev-200", options.datasetId());
        assertEquals(RetrievalCorpus.EVAL_QASPER, options.retrievalCorpus());
    }

    @Test
    void refusesMissingRetrievalCorpus() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ServiceBackedPageWindowBenchmarkCli.Options.parse(new String[]{
                        "--cases", "cases.jsonl"
                })
        );

        assertTrue(error.getMessage().contains("Missing --retrieval-corpus"));
    }

    @Test
    void refusesProductLibraryForQasperBenchmark() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ServiceBackedPageWindowBenchmarkCli.Options.parse(new String[]{
                        "--cases", "cases.jsonl",
                        "--dataset-id", "qasper-dev-200",
                        "--retrieval-corpus", "PRODUCT_LIBRARY"
                })
        );

        assertTrue(error.getMessage().contains("EVAL_QASPER"));
    }

    @Test
    void runsServiceBackedPageWindowAndRefreshesCheatsheet() throws Exception {
        Path cases = tempDir.resolve("cases.jsonl");
        Files.writeString(cases, """
                {"id":"hit","query":"Which table reports F1?","scope":{"paperIds":["paper-a"],"paperTitles":[]},"expectedRoute":"PAGE_WINDOW_LEDGER","requiredEvidenceRegex":["reports F1"],"expectedPaperIds":["paper-a"],"requiresCitation":true}
                """);
        Path registry = tempDir.resolve("harnesses.yaml");
        Files.writeString(registry, """
                harnesses:
                  - id: service-backed-page-window
                    name: Service Backed Page Window
                    description: Production retrieval plus deterministic page-window inspection.
                    retrieval: hybrid-plus-page-window
                    planner: scientific-qa
                    verifier: enabled
                    status: runnable
                benchmarks:
                  - id: product-rescue-smoke
                    name: Product Rescue Smoke
                    tier: product
                    task: source-grounded paper chat regression
                    status: runnable
                    path: product-rescue-smoke.jsonl
                    source: local
                    primaryMetric: passRate
                    cases: "1"
                """);
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        PaperPageWindowService pageWindowService = mock(PaperPageWindowService.class);
        RetrievalBudget budget = RetrievalBudget.forQa();
        SearchResult firstStage = result("paper-a", 4, 4, "Evaluation", "TEXT",
                "The evaluation section points to result tables.", 0.8d);
        SearchResult inspected = result("paper-a", 5, 4, "Evaluation", "TABLE",
                "Table 2 reports F1 improvements for the retrieval baseline.", 1.0d);
        when(retrievalService.retrieve("Which table reports F1?", "eval-user", budget, List.of("paper-a")))
                .thenReturn(retrievalResult("Which table reports F1?", List.of(firstStage)));
        when(pageWindowService.inspectPageWindow("paper-a", 4, 1))
                .thenReturn(List.of(inspected));

        Path runDir = ServiceBackedPageWindowBenchmarkCli.run(
                retrievalService,
                pageWindowService,
                new EvidenceLedgerService(),
                new ServiceBackedPageWindowBenchmarkCli.Options(
                        cases,
                        tempDir.resolve("runs"),
                        registry,
                        tempDir.resolve("CHEATSHEET.md"),
                        "service-backed-page-window",
                        "product-rescue-smoke",
                        "service-page-window-product",
                        "2026-06-24T20:40:00Z",
                        "eval-user",
                        RetrievalCorpus.PRODUCT_LIBRARY,
                        budget,
                        3,
                        1,
                        "scientific-qa"
                )
        );

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals("service-backed-page-window", scorecard.path("harnessId").asText());
        assertEquals("product-rescue-smoke", scorecard.path("datasetId").asText());
        assertEquals(1.0d, scorecard.path("passRate").asDouble());
        assertEquals(1.0d, scorecard.path("metrics").path("windowEvidenceHitAt1").asDouble());
        assertTrue(Files.readString(tempDir.resolve("CHEATSHEET.md"))
                .contains("| service-backed-page-window | Product Rescue Smoke | product | 1 | Pass 100.0% |"));
        verify(retrievalService).retrieve("Which table reports F1?", "eval-user", budget, List.of("paper-a"));
        verify(pageWindowService).inspectPageWindow("paper-a", 4, 1);
    }

    @Test
    void runsQasperPageWindowThroughEvalCorpusReader() throws Exception {
        Path cases = tempDir.resolve("qasper-cases.jsonl");
        Files.writeString(cases, """
                {"id":"hit","query":"Which table reports F1?","scope":{"paperIds":["qasper:paper-a"],"paperTitles":[]},"expectedRoute":"PAGE_WINDOW_LEDGER","requiredEvidenceRegex":["reports F1"],"expectedPaperIds":["qasper:paper-a"],"requiresCitation":true}
                """);
        Path registry = tempDir.resolve("harnesses-qasper.yaml");
        Files.writeString(registry, """
                harnesses:
                  - id: service-backed-scoped-diverse-window
                    name: Service Backed Scoped Diverse Window
                    description: Eval corpus scoped-paper page windows.
                    retrieval: scoped-paper-diverse-window
                    planner: scientific-qa-diverse-windows
                    verifier: enabled
                    status: runnable
                benchmarks:
                  - id: qasper-dev-200
                    name: QASPER Dev 200
                    tier: professional
                    task: scoped paper QA
                    status: runnable
                    path: qasper-cases.jsonl
                    source: local
                    primaryMetric: passRate
                    cases: "1"
                """);
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        PaperPageWindowService pageWindowService = mock(PaperPageWindowService.class);
        EvalCorpusPageWindowService evalPageWindowService = mock(EvalCorpusPageWindowService.class);
        RetrievalBudget budget = RetrievalBudget.forQa();
        SearchResult intro = result("qasper:paper-a", 1, 1, "Intro", "TEXT",
                "This paper studies retrieval.", 0.5d);
        SearchResult inspected = result("qasper:paper-a", 5, 4, "Evaluation", "TABLE",
                "Table 2 reports F1 improvements for the retrieval baseline.", 1.0d);
        when(evalPageWindowService.inspectPaper(RetrievalCorpus.EVAL_QASPER, "qasper:paper-a"))
                .thenReturn(List.of(intro, inspected));
        when(evalPageWindowService.inspectPageWindow(RetrievalCorpus.EVAL_QASPER, "qasper:paper-a", 4, 1))
                .thenReturn(List.of(inspected));

        Path runDir = ServiceBackedPageWindowBenchmarkCli.run(
                retrievalService,
                pageWindowService,
                evalPageWindowService,
                new EvidenceLedgerService(),
                new ServiceBackedPageWindowBenchmarkCli.Options(
                        cases,
                        tempDir.resolve("qasper-runs"),
                        registry,
                        tempDir.resolve("QASPER_CHEATSHEET.md"),
                        "service-backed-scoped-diverse-window",
                        "qasper-dev-200",
                        "service-page-window-qasper",
                        "2026-06-24T20:45:00Z",
                        "eval-user",
                        RetrievalCorpus.EVAL_QASPER,
                        budget,
                        1,
                        1,
                        "scientific-qa-diverse-windows",
                        "scoped-paper"
                )
        );

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals("qasper-dev-200", scorecard.path("datasetId").asText());
        assertEquals(1.0d, scorecard.path("passRate").asDouble());
        verify(retrievalService, never()).retrieve("Which table reports F1?", "eval-user", budget, List.of("qasper:paper-a"));
        verify(pageWindowService, never()).inspectPaper("qasper:paper-a");
        verify(pageWindowService, never()).inspectPageWindow("qasper:paper-a", 4, 1);
        verify(evalPageWindowService).inspectPaper(RetrievalCorpus.EVAL_QASPER, "qasper:paper-a");
        verify(evalPageWindowService).inspectPageWindow(RetrievalCorpus.EVAL_QASPER, "qasper:paper-a", 4, 1);
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

    private PaperRetrievalService.RetrievalResult retrievalResult(String query, List<SearchResult> results) {
        PaperQueryPlanner.RetrievalPlan plan = new PaperQueryPlanner.RetrievalPlan(
                query,
                query,
                PaperQueryPlanner.RetrievalIntent.EXPERIMENT_RESULT,
                List.of(query),
                List.of("TEXT", "TABLE"),
                List.of("evaluation", "results")
        );
        return new PaperRetrievalService.RetrievalResult(plan, results, Map.of(query, results.size()));
    }
}
