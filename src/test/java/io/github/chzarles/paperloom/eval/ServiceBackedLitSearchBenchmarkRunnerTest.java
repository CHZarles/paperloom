package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.entity.SearchResult;
import io.github.chzarles.paperloom.service.PaperQueryPlanner;
import io.github.chzarles.paperloom.service.PaperRetrievalService;
import io.github.chzarles.paperloom.service.RetrievalBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceBackedLitSearchBenchmarkRunnerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void writesRetrievedCorpusIdsAndScorecardFromProductionRetrievalService() throws Exception {
        Path gold = tempDir.resolve("litsearch-mini.jsonl");
        Files.writeString(gold, """
                {"id":"q1","query":"post-hoc hallucination detection","goldCorpusIds":["gold-1"]}
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
        SearchResult goldHit = new SearchResult("litsearch:gold-1", 7, "Abstract: detects hallucinations.", 0.9d);
        SearchResult missHit = new SearchResult("litsearch:miss-1", 3, "Abstract: unrelated.", 0.7d);
        when(retrievalService.retrieve("post-hoc hallucination detection", "u1", budget))
                .thenReturn(retrievalResult("post-hoc hallucination detection", List.of(goldHit, missHit)));

        ServiceBackedLitSearchBenchmarkRunner runner = new ServiceBackedLitSearchBenchmarkRunner(retrievalService);

        Path runDir = runner.run(new ServiceBackedLitSearchBenchmarkRunner.Options(
                gold,
                tempDir.resolve("retrieved.jsonl"),
                tempDir.resolve("runs"),
                registry,
                tempDir.resolve("CHEATSHEET.md"),
                "current-evidence-ledger",
                "litsearch-mini",
                "service-litsearch-mini",
                "2026-06-24T16:00:00Z",
                "u1",
                budget,
                20
        ));

        JsonNode retrieved = OBJECT_MAPPER.readTree(Files.readString(tempDir.resolve("retrieved.jsonl")).trim());
        assertEquals("q1", retrieved.path("caseId").asText());
        assertEquals("gold-1", retrieved.path("retrievedCorpusIds").get(0).asText());
        assertEquals("miss-1", retrieved.path("retrievedCorpusIds").get(1).asText());

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals("current-evidence-ledger", scorecard.path("harnessId").asText());
        assertEquals("litsearch-mini", scorecard.path("datasetId").asText());
        assertEquals(1.0d, scorecard.path("metrics").path("recallAt20").asDouble());
        assertEquals(1.0d, scorecard.path("metrics").path("mrr").asDouble());
        assertTrue(Files.readString(tempDir.resolve("CHEATSHEET.md")).contains("LitSearch Mini"));

        verify(retrievalService).retrieve("post-hoc hallucination detection", "u1", budget);
    }

    @Test
    void scopesProductionRetrievalToImportedBenchmarkPaperIdsWhenProvided() throws Exception {
        Path gold = tempDir.resolve("litsearch-mini.jsonl");
        Files.writeString(gold, """
                {"id":"q1","query":"post-hoc hallucination detection","goldCorpusIds":["gold-1"]}
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
        List<String> scopePaperIds = List.of("litsearch:gold-1", "litsearch:miss-1");
        SearchResult goldHit = new SearchResult("litsearch:gold-1", 7, "Abstract: detects hallucinations.", 0.9d);
        when(retrievalService.retrieve("post-hoc hallucination detection", "u1", budget, scopePaperIds))
                .thenReturn(retrievalResult("post-hoc hallucination detection", List.of(goldHit)));

        ServiceBackedLitSearchBenchmarkRunner runner = new ServiceBackedLitSearchBenchmarkRunner(retrievalService);

        runner.run(new ServiceBackedLitSearchBenchmarkRunner.Options(
                gold,
                tempDir.resolve("retrieved-scoped.jsonl"),
                tempDir.resolve("runs"),
                registry,
                tempDir.resolve("CHEATSHEET.md"),
                "current-evidence-ledger",
                "litsearch-mini",
                "service-litsearch-mini-scoped",
                "2026-06-24T16:05:00Z",
                "u1",
                budget,
                20,
                scopePaperIds
        ));

        verify(retrievalService).retrieve("post-hoc hallucination detection", "u1", budget, scopePaperIds);
        verify(retrievalService, never()).retrieve("post-hoc hallucination detection", "u1", budget);
    }

    @Test
    void resumesExistingNonEmptyRetrievedRowsAndFillsEmptyRows() throws Exception {
        Path gold = tempDir.resolve("litsearch-resume.jsonl");
        Files.writeString(gold, """
                {"id":"q1","query":"post-hoc hallucination detection","goldCorpusIds":["gold-1"]}
                {"id":"q2","query":"evidence-aware literature search","goldCorpusIds":["gold-2"]}
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
                  - id: litsearch-resume
                    name: LitSearch Resume
                    tier: professional
                    task: literature search retrieval
                    status: runnable
                    path: litsearch-resume.jsonl
                    source: local
                    primaryMetric: recallAt20
                    cases: "2"
                """);
        Path retrievedPath = tempDir.resolve("retrieved-resume.jsonl");
        Files.writeString(retrievedPath, """
                {"caseId":"q1","retrievedCorpusIds":["gold-1"]}
                {"caseId":"q2","retrievedCorpusIds":[]}
                """);
        PaperRetrievalService retrievalService = mock(PaperRetrievalService.class);
        RetrievalBudget budget = RetrievalBudget.forLibrarySearch();
        SearchResult goldHit = new SearchResult("litsearch:gold-2", 1, "Abstract: evidence-aware retrieval.", 0.9d);
        when(retrievalService.retrieve("evidence-aware literature search", "u1", budget))
                .thenReturn(retrievalResult("evidence-aware literature search", List.of(goldHit)));

        ServiceBackedLitSearchBenchmarkRunner runner = new ServiceBackedLitSearchBenchmarkRunner(retrievalService);

        runner.run(new ServiceBackedLitSearchBenchmarkRunner.Options(
                gold,
                retrievedPath,
                tempDir.resolve("runs"),
                registry,
                tempDir.resolve("CHEATSHEET.md"),
                "current-evidence-ledger",
                "litsearch-resume",
                "service-litsearch-resume",
                "2026-06-24T16:10:00Z",
                "u1",
                budget,
                20
        ));

        List<String> lines = Files.readAllLines(retrievedPath);
        assertEquals(2, lines.size());
        assertEquals("gold-1", OBJECT_MAPPER.readTree(lines.get(0)).path("retrievedCorpusIds").path(0).asText(""));
        assertEquals("gold-2", OBJECT_MAPPER.readTree(lines.get(1)).path("retrievedCorpusIds").path(0).asText(""));
        verify(retrievalService, never()).retrieve("post-hoc hallucination detection", "u1", budget);
        verify(retrievalService).retrieve("evidence-aware literature search", "u1", budget);
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
