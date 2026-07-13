package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.entity.SearchResult;
import io.github.chzarles.paperloom.service.EvidenceLedger;
import io.github.chzarles.paperloom.service.EvidenceLedgerService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceBackedPageWindowBenchmarkRunnerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void writesRunArtifactsAndEvidenceMetricsForBenchmarkCases() throws Exception {
        Path casesPath = tempDir.resolve("cases.jsonl");
        Files.writeString(casesPath, """
                {"id":"hit","query":"Which baseline improves F1?","scope":{"paperIds":["paper-a"],"paperTitles":[]},"expectedRoute":"PAGE_WINDOW_LEDGER","requiredEvidenceRegex":["improves F1"],"expectedPaperIds":["paper-a"],"requiresCitation":true}
                {"id":"miss","query":"Which ablation is missing?","scope":{"paperIds":["paper-a"],"paperTitles":[]},"expectedRoute":"PAGE_WINDOW_LEDGER","requiredEvidenceRegex":["missing ablation"],"expectedPaperIds":["paper-a"],"requiresCitation":true}
                """);
        ServiceBackedPageWindowHarness harness = mock(ServiceBackedPageWindowHarness.class);
        when(harness.run(eq("Which baseline improves F1?"), eq("u1"), any(), eq(List.of("paper-a")), any()))
                .thenReturn(harnessResult(
                        "Which baseline improves F1?",
                        "Table 2 reports the baseline improves F1.",
                        "Table 2 reports the baseline improves F1."
                ));
        when(harness.run(eq("Which ablation is missing?"), eq("u1"), any(), eq(List.of("paper-a")), any()))
                .thenReturn(harnessResult(
                        "Which ablation is missing?",
                        "Table 2 reports the baseline improves F1.",
                        "Appendix C describes the missing ablation."
                ));

        ServiceBackedPageWindowBenchmarkRunner runner = new ServiceBackedPageWindowBenchmarkRunner(harness);

        Path runDir = runner.run(new ServiceBackedPageWindowBenchmarkRunner.Options(
                casesPath,
                tempDir.resolve("runs"),
                "2026-06-24T120000Z-service-backed-page-window-test",
                "2026-06-24T12:00:00Z",
                "service-backed-page-window",
                "service-backed-test",
                "u1",
                RetrievalBudget.forQa(),
                new ServiceBackedPageWindowHarness.Options(3, 1, "scientific-qa")
        ));

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals("service-backed-page-window", scorecard.path("harnessId").asText());
        assertEquals(2, scorecard.path("caseCount").asInt());
        assertEquals(1, scorecard.path("passed").asInt());
        assertEquals(0.5d, scorecard.path("passRate").asDouble());
        assertEquals(0.5d, scorecard.path("metrics").path("windowEvidenceHitAt1").asDouble());
        assertEquals(2.0d, scorecard.path("metrics").path("windowEvidenceCaseCount").asDouble());
        assertEquals(1.0d, scorecard.path("metrics").path("candidateEvidenceHitRate").asDouble());
        assertEquals(2.0d, scorecard.path("metrics").path("candidateEvidenceCaseCount").asDouble());
        JsonNode run = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile());
        assertEquals(1, run.path("cases").get(0).path("diagnostics").path("candidateChunkCount").asInt());
        assertTrue(run.path("cases").get(1).path("diagnostics").path("candidateEvidenceHit").asBoolean());
        String report = Files.readString(runDir.resolve("report.md"));
        assertTrue(report.contains("PASS hit"));
        assertTrue(report.contains("FAIL miss"));
    }

    private ServiceBackedPageWindowHarness.HarnessResult harnessResult(String query,
                                                                       String windowEvidenceText,
                                                                       String candidateEvidenceText) {
        SearchResult windowChunk = chunk(windowEvidenceText);
        SearchResult candidateChunk = chunk(candidateEvidenceText);
        PaperPageDocument page = new PaperPageDocument(
                "paper-a",
                "Adaptive Retrieval",
                "adaptive.pdf",
                4,
                windowEvidenceText,
                List.of(7),
                List.of("Experiments"),
                List.of("TABLE"),
                List.of("table-2"),
                List.of()
        );
        PaperPageWindow window = new PaperPageWindow(page, List.of(page), 1.0d, List.of("text"));
        PaperPageInspection inspection = PaperPageInspection.from(window, List.of(windowChunk));
        EvidenceLedger ledger = new EvidenceLedgerService().fromSearchResults(List.of(windowChunk), RetrievalBudget.forQa());
        return new ServiceBackedPageWindowHarness.HarnessResult(
                retrievalResult(query, List.of(candidateChunk)),
                "FIRST_STAGE",
                List.of(candidateChunk),
                query,
                List.of(),
                List.of(window),
                List.of(inspection),
                ledger
        );
    }

    private SearchResult chunk(String evidenceText) {
        SearchResult chunk = new SearchResult("paper-a", 7, evidenceText, 1.0d);
        chunk.setPaperTitle("Adaptive Retrieval");
        chunk.setOriginalFilename("adaptive.pdf");
        chunk.setPageNumber(4);
        chunk.setSectionTitle("Experiments");
        chunk.setSourceKind("TABLE");
        chunk.setMatchedChunkText(evidenceText);
        return chunk;
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
