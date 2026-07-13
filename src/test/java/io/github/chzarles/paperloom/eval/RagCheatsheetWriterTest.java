package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagCheatsheetWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void rendersConciseRowsForScoredAndPendingProfessionalBenchmarks() throws Exception {
        Path registry = registryYaml();
        Path runsRoot = tempDir.resolve("runs");
        Files.createDirectories(runsRoot.resolve("run-1"));
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.writeValue(
                runsRoot.resolve("run-1").resolve("scorecard.json").toFile(),
                RagScorecard.from(
                        "run-1",
                        "2026-06-23T12:00:00Z",
                        "current-evidence-ledger",
                        "qasper-dev-200",
                        new RagBenchmarkRun(
                                List.of(),
                                List.of(),
                                List.of(
                                        new RagBenchmarkVerdict("a", true, List.of(), List.of()),
                                        new RagBenchmarkVerdict("b", false, List.of("missing evidence"), List.of("FALSE_NEGATIVE"))
                                )
                        ),
                        Map.of()
                )
        );
        Files.createDirectories(runsRoot.resolve("run-page"));
        objectMapper.writeValue(
                runsRoot.resolve("run-page").resolve("scorecard.json").toFile(),
                RagScorecard.from(
                        "run-page",
                        "2026-06-23T12:10:00Z",
                        "current-evidence-ledger",
                        "page-location-mini",
                        new RagBenchmarkRun(
                                List.of(),
                                List.of(),
                                List.of(new RagBenchmarkVerdict("page-a", true, List.of(), List.of()))
                        ),
                        Map.of("positivePageRecallAt3", 0.25d)
                )
        );
        Files.createDirectories(runsRoot.resolve("run-window-evidence"));
        objectMapper.writeValue(
                runsRoot.resolve("run-window-evidence").resolve("scorecard.json").toFile(),
                RagScorecard.from(
                        "run-window-evidence",
                        "2026-06-23T12:20:00Z",
                        "current-evidence-ledger",
                        "page-window-evidence-mini",
                        new RagBenchmarkRun(
                                List.of(),
                                List.of(),
                                List.of(new RagBenchmarkVerdict("window-a", true, List.of(), List.of()))
                        ),
                        Map.of("windowEvidenceHitAt3", 1.0d)
                )
        );
        Files.createDirectories(runsRoot.resolve("bad-run"));
        Files.writeString(runsRoot.resolve("bad-run").resolve("scorecard.json"), "{not-json");
        Path output = tempDir.resolve("CHEATSHEET.md");

        RagCheatsheetWriter.write(output, registry, runsRoot, "2026-06-23T12:30:00Z");

        String markdown = Files.readString(output);
        assertTrue(markdown.contains("# PaperLoom RAG Eval Cheatsheet"));
        assertTrue(markdown.contains("| current-evidence-ledger | QASPER Dev 200 | professional | 2 | Pass 50.0% |"));
        assertTrue(markdown.contains("| current-evidence-ledger | LitSearch Full | professional | 597 queries / 64,183 papers | pending Recall@20 |"));
        assertTrue(markdown.contains("| litsearch-only | LitSearch Full | professional | 597 queries / 64,183 papers | pending Recall@20 |"));
        assertTrue(!markdown.contains("| litsearch-only | QASPER Dev 200 |"));
        assertTrue(markdown.contains("| current-evidence-ledger | Page Location Mini | prototype | 1 | PosPage@3 25.0% |"));
        assertTrue(markdown.contains("| current-evidence-ledger | Page Window Evidence Mini | prototype | 1 | WindowEv@3 100.0% |"));
        assertTrue(markdown.contains("bad-run/scorecard.json"));
    }

    private Path registryYaml() throws Exception {
        Path registry = tempDir.resolve("harnesses.yaml");
        Files.writeString(registry, """
                harnesses:
                  - id: current-evidence-ledger
                    name: Current Evidence Ledger
                    description: Router plus adaptive retrieval.
                    retrieval: adaptive-hybrid
                    planner: deterministic-first
                    verifier: enabled
                    status: runnable
                  - id: litsearch-only
                    name: LitSearch Only
                    description: LitSearch-only baseline.
                    retrieval: keyword
                    planner: none
                    verifier: disabled
                    status: runnable-litsearch
                    benchmarkIds: litsearch-full
                benchmarks:
                  - id: qasper-dev-200
                    name: QASPER Dev 200
                    tier: professional
                    task: paper evidence QA
                    status: runnable
                    path: eval/rag/qasper/generated/qasper-dev-200.jsonl
                    source: https://allenai.org/data/qasper
                    primaryMetric: passRate
                    cases: "200"
                  - id: litsearch-full
                    name: LitSearch Full
                    tier: professional
                    task: literature search retrieval
                    status: planned
                    path: eval/rag/litsearch/generated/litsearch-full-query.jsonl
                    source: https://huggingface.co/datasets/princeton-nlp/LitSearch
                    primaryMetric: recallAt20
                    cases: "597 queries / 64,183 papers"
                  - id: page-location-mini
                    name: Page Location Mini
                    tier: prototype
                    task: page-window retrieval
                    status: runnable
                    path: eval/rag/page-location/generated/page-location-mini.jsonl
                    source: local
                    primaryMetric: positivePageRecallAt3
                    cases: "1"
                  - id: page-window-evidence-mini
                    name: Page Window Evidence Mini
                    tier: prototype
                    task: inspected page-window evidence hit
                    status: runnable
                    path: eval/rag/page-location/generated/page-window-evidence-mini.jsonl
                    source: local
                    primaryMetric: windowEvidenceHitAt3
                    cases: "1"
                """);
        return registry;
    }
}
