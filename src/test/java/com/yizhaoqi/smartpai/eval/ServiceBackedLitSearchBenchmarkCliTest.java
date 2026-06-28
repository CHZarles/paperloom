package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.eval.model.EvalPaper;
import com.yizhaoqi.smartpai.eval.repository.EvalPaperRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceBackedLitSearchBenchmarkCliTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void usesSpringStartupArgsThatAvoidEvalCliSideEffects() {
        List<String> args = Arrays.asList(ServiceBackedLitSearchBenchmarkCli.springStartupArgs());

        assertTrue(args.contains("--elasticsearch.init.enabled=false"));
        assertTrue(args.contains("--spring.kafka.listener.auto-startup=false"));
        assertTrue(args.contains("--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"));
        assertTrue(args.contains("--admin.bootstrap.enabled=false"));
        assertTrue(args.contains("--paper.bootstrap.enabled=false"));
    }

    @Test
    void parsesServiceBackedLitSearchOptions() {
        ServiceBackedLitSearchBenchmarkCli.Options options = ServiceBackedLitSearchBenchmarkCli.Options.parse(new String[]{
                "--gold", "eval/rag/litsearch/generated/litsearch-full-query.jsonl",
                "--retrieved", "eval/rag/litsearch/generated/service-backed-retrieved.jsonl",
                "--runs-root", "eval/rag/runs",
                "--registry", "eval/rag/harnesses.yaml",
                "--cheatsheet", "eval/rag/CHEATSHEET.md",
                "--harness-id", "current-evidence-ledger",
                "--dataset-id", "litsearch-sample-20",
                "--run-id", "service-litsearch-sample-20",
                "--started-at", "2026-06-24T17:00:00Z",
                "--user-id", "eval-user",
                "--top-k", "7",
                "--scope-imported-only", "true",
                "--retrieval-corpus", "EVAL_LITSEARCH",
                "--eval-split", "sample-20"
        });

        assertEquals(Path.of("eval/rag/litsearch/generated/litsearch-full-query.jsonl"), options.goldPath());
        assertEquals(Path.of("eval/rag/litsearch/generated/service-backed-retrieved.jsonl"), options.retrievedPath());
        assertEquals(Path.of("eval/rag/runs"), options.runsRoot());
        assertEquals(Path.of("eval/rag/harnesses.yaml"), options.registryPath());
        assertEquals(Path.of("eval/rag/CHEATSHEET.md"), options.cheatsheetPath());
        assertEquals("current-evidence-ledger", options.harnessId());
        assertEquals("litsearch-sample-20", options.datasetId());
        assertEquals("service-litsearch-sample-20", options.runId());
        assertEquals("2026-06-24T17:00:00Z", options.startedAt());
        assertEquals("eval-user", options.userId());
        assertEquals(7, options.topK());
        assertEquals(RetrievalBudget.forLibrarySearch(), options.budget());
        assertTrue(options.scopeImportedOnly());
        assertEquals(RetrievalCorpus.EVAL_LITSEARCH, options.retrievalCorpus());
        assertEquals("sample-20", options.evalSplit());
    }

    @Test
    void refusesMissingRetrievalCorpus() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ServiceBackedLitSearchBenchmarkCli.Options.parse(new String[]{
                        "--gold", "gold.jsonl",
                        "--retrieved", "retrieved.jsonl"
                })
        );

        assertTrue(error.getMessage().contains("Missing --retrieval-corpus"));
    }

    @Test
    void refusesProductLibraryForLitSearchBenchmark() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ServiceBackedLitSearchBenchmarkCli.Options.parse(new String[]{
                        "--gold", "gold.jsonl",
                        "--retrieved", "retrieved.jsonl",
                        "--retrieval-corpus", "PRODUCT_LIBRARY"
                })
        );

        assertTrue(error.getMessage().contains("EVAL_LITSEARCH"));
    }

    @Test
    void refusesQasperCorpusForLitSearchBenchmark() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ServiceBackedLitSearchBenchmarkCli.Options.parse(new String[]{
                        "--gold", "gold.jsonl",
                        "--retrieved", "retrieved.jsonl",
                        "--retrieval-corpus", "EVAL_QASPER"
                })
        );

        assertTrue(error.getMessage().contains("EVAL_LITSEARCH"));
    }

    @Test
    void defaultsToCurrentLedgerLitSearchFullRunShapeAfterCorpusIsExplicit() {
        ServiceBackedLitSearchBenchmarkCli.Options options = ServiceBackedLitSearchBenchmarkCli.Options.parse(new String[]{
                "--gold", "gold.jsonl",
                "--retrieved", "retrieved.jsonl",
                "--retrieval-corpus", "EVAL_LITSEARCH",
                "--started-at", "2026-06-24T17:05:00Z"
        });

        assertEquals(Path.of("eval/rag/runs"), options.runsRoot());
        assertEquals(Path.of("eval/rag/harnesses.yaml"), options.registryPath());
        assertEquals(Path.of("eval/rag/CHEATSHEET.md"), options.cheatsheetPath());
        assertEquals("current-evidence-ledger", options.harnessId());
        assertEquals("litsearch-full", options.datasetId());
        assertEquals("2026-06-24T170500Z-current-evidence-ledger-litsearch-full", options.runId());
        assertEquals("eval-litsearch-user", options.userId());
        assertEquals(20, options.topK());
        assertFalse(options.scopeImportedOnly());
        assertEquals(RetrievalCorpus.EVAL_LITSEARCH, options.retrievalCorpus());
        assertEquals("full", options.evalSplit());
    }

    @Test
    void runsServiceBackedRetrievalAndWritesStandardArtifacts() throws Exception {
        Path gold = tempDir.resolve("litsearch-mini.jsonl");
        Files.writeString(gold, """
                {"id":"q1","query":"papers about retrieval augmented generation benchmarks","goldCorpusIds":["paper-1"]}
                """);
        Path registry = tempDir.resolve("harnesses.yaml");
        Files.writeString(registry, """
                harnesses:
                  - id: current-evidence-ledger
                    name: Current Evidence Ledger
                    description: Router plus adaptive retrieval.
                    retrieval: routed-paper-search
                    planner: deterministic-first
                    verifier: enabled
                    status: runnable
                benchmarks:
                  - id: litsearch-mini
                    name: LitSearch Mini
                    tier: professional
                    task: literature search retrieval
                    status: runnable
                    path: litsearch-mini.jsonl
                    source: local
                    primaryMetric: recallAt20
                    cases: "1"
                """);
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        RetrievalBudget budget = RetrievalBudget.forLibrarySearch();
        SearchResult hit = new SearchResult("litsearch:paper-1", 1, "RAG benchmark abstract.", 0.91d);
        when(retrievalService.retrieve("papers about retrieval augmented generation benchmarks", "eval-user", budget))
                .thenReturn(retrievalResult("papers about retrieval augmented generation benchmarks", List.of(hit)));

        Path runDir = ServiceBackedLitSearchBenchmarkCli.run(retrievalService, new ServiceBackedLitSearchBenchmarkCli.Options(
                gold,
                tempDir.resolve("retrieved.jsonl"),
                tempDir.resolve("runs"),
                registry,
                tempDir.resolve("CHEATSHEET.md"),
                "current-evidence-ledger",
                "litsearch-mini",
                "service-litsearch-mini",
                "2026-06-24T17:10:00Z",
                "eval-user",
                budget,
                20,
                false,
                RetrievalCorpus.EVAL_LITSEARCH,
                "full"
        ));

        JsonNode retrieved = OBJECT_MAPPER.readTree(Files.readString(tempDir.resolve("retrieved.jsonl")).trim());
        assertEquals("q1", retrieved.path("caseId").asText());
        assertEquals("paper-1", retrieved.path("retrievedCorpusIds").get(0).asText());

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals("current-evidence-ledger", scorecard.path("harnessId").asText());
        assertEquals("litsearch-mini", scorecard.path("datasetId").asText());
        assertEquals(1.0d, scorecard.path("metrics").path("recallAt20").asDouble());
        assertTrue(Files.readString(tempDir.resolve("CHEATSHEET.md")).contains("LitSearch Mini"));
        verify(retrievalService).retrieve("papers about retrieval augmented generation benchmarks", "eval-user", budget);
    }

    @Test
    void scopesServiceBackedRunToImportedEvalPapersWhenRequested() throws Exception {
        Path gold = tempDir.resolve("litsearch-mini.jsonl");
        Files.writeString(gold, """
                {"id":"q1","query":"papers about retrieval augmented generation benchmarks","goldCorpusIds":["paper-1"]}
                """);
        Path registry = tempDir.resolve("harnesses.yaml");
        Files.writeString(registry, """
                harnesses:
                  - id: current-evidence-ledger
                    name: Current Evidence Ledger
                    description: Router plus adaptive retrieval.
                    retrieval: routed-paper-search
                    planner: deterministic-first
                    verifier: enabled
                    status: runnable
                benchmarks:
                  - id: litsearch-mini
                    name: LitSearch Mini
                    tier: professional
                    task: literature search retrieval
                    status: runnable
                    path: litsearch-mini.jsonl
                    source: local
                    primaryMetric: recallAt20
                    cases: "1"
                """);
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        EvalPaperRepository evalPaperRepository = mock(EvalPaperRepository.class);
        RetrievalBudget budget = RetrievalBudget.forLibrarySearch();
        EvalPaper scopedPaper = new EvalPaper();
        scopedPaper.setPaperId("litsearch:paper-1");
        when(evalPaperRepository.findByCorpusAndSplit("litsearch", "sample-20"))
                .thenReturn(List.of(scopedPaper));
        SearchResult hit = new SearchResult("litsearch:paper-1", 1, "RAG benchmark abstract.", 0.91d);
        when(retrievalService.retrieve(
                "papers about retrieval augmented generation benchmarks",
                "eval-user",
                budget,
                List.of("litsearch:paper-1")
        )).thenReturn(retrievalResult("papers about retrieval augmented generation benchmarks", List.of(hit)));

        ServiceBackedLitSearchBenchmarkCli.run(retrievalService, evalPaperRepository, new ServiceBackedLitSearchBenchmarkCli.Options(
                gold,
                tempDir.resolve("retrieved-scoped.jsonl"),
                tempDir.resolve("runs"),
                registry,
                tempDir.resolve("CHEATSHEET.md"),
                "current-evidence-ledger",
                "litsearch-mini",
                "service-litsearch-mini-scoped",
                "2026-06-24T17:15:00Z",
                "eval-user",
                budget,
                20,
                true,
                RetrievalCorpus.EVAL_LITSEARCH,
                "sample-20"
        ));

        verify(evalPaperRepository).findByCorpusAndSplit("litsearch", "sample-20");
        verify(retrievalService).retrieve(
                "papers about retrieval augmented generation benchmarks",
                "eval-user",
                budget,
                List.of("litsearch:paper-1")
        );
    }

    @Test
    void refusesScopedRunWhenNoImportedEvalPapersExist() throws Exception {
        Path gold = tempDir.resolve("litsearch-mini.jsonl");
        Files.writeString(gold, """
                {"id":"q1","query":"papers about retrieval augmented generation benchmarks","goldCorpusIds":["paper-1"]}
                """);
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        EvalPaperRepository evalPaperRepository = mock(EvalPaperRepository.class);
        when(evalPaperRepository.findByCorpusAndSplit("litsearch", "missing-sample"))
                .thenReturn(List.of());

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                ServiceBackedLitSearchBenchmarkCli.run(retrievalService, evalPaperRepository, new ServiceBackedLitSearchBenchmarkCli.Options(
                        gold,
                        tempDir.resolve("retrieved-empty-scope.jsonl"),
                        tempDir.resolve("runs"),
                        tempDir.resolve("harnesses.yaml"),
                        tempDir.resolve("CHEATSHEET.md"),
                        "current-evidence-ledger",
                        "litsearch-mini",
                        "service-litsearch-mini-empty-scope",
                        "2026-06-24T17:20:00Z",
                        "eval-user",
                        RetrievalBudget.forLibrarySearch(),
                        20,
                        true,
                        RetrievalCorpus.EVAL_LITSEARCH,
                        "missing-sample"
                )));

        assertTrue(error.getMessage().contains("No imported eval papers"));
        assertTrue(error.getMessage().contains("missing-sample"));
        verify(retrievalService, never()).retrieve(
                "papers about retrieval augmented generation benchmarks",
                "eval-user",
                RetrievalBudget.forLibrarySearch()
        );
    }

    @Test
    void refusesToReportPartialImportedScopeAsLitSearchFull() throws Exception {
        Path gold = tempDir.resolve("litsearch-full-query.jsonl");
        Files.writeString(gold, """
                {"id":"q1","query":"papers about retrieval augmented generation benchmarks","goldCorpusIds":["paper-1"]}
                """);
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        EvalPaperRepository evalPaperRepository = mock(EvalPaperRepository.class);
        EvalPaper scopedPaper = new EvalPaper();
        scopedPaper.setPaperId("litsearch:paper-1");
        when(evalPaperRepository.findByCorpusAndSplit("litsearch", "full"))
                .thenReturn(List.of(scopedPaper));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ServiceBackedLitSearchBenchmarkCli.run(retrievalService, evalPaperRepository, new ServiceBackedLitSearchBenchmarkCli.Options(
                        gold,
                        tempDir.resolve("retrieved-partial-full.jsonl"),
                        tempDir.resolve("runs"),
                        tempDir.resolve("harnesses.yaml"),
                        tempDir.resolve("CHEATSHEET.md"),
                        "current-evidence-ledger",
                        "litsearch-full",
                        "service-litsearch-partial-full",
                        "2026-06-24T17:25:00Z",
                        "eval-user",
                        RetrievalBudget.forLibrarySearch(),
                        20,
                        true,
                        RetrievalCorpus.EVAL_LITSEARCH,
                        "full"
                )));

        assertTrue(error.getMessage().contains("litsearch-full"));
        assertTrue(error.getMessage().contains("64,183"));
        verify(retrievalService, never()).retrieve(
                "papers about retrieval augmented generation benchmarks",
                "eval-user",
                RetrievalBudget.forLibrarySearch(),
                List.of("litsearch:paper-1")
        );
    }

    private PaperRetrievalService.RetrievalResult retrievalResult(String query, List<SearchResult> results) {
        PaperQueryPlanner.RetrievalPlan plan = new PaperQueryPlanner.RetrievalPlan(
                query,
                query,
                PaperQueryPlanner.RetrievalIntent.LITERATURE_SEARCH,
                List.of(query),
                List.of("TEXT"),
                List.of("title", "abstract")
        );
        return new PaperRetrievalService.RetrievalResult(plan, results, Map.of(query, results.size()));
    }
}
