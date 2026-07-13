package io.github.chzarles.paperloom.eval;

import io.github.chzarles.paperloom.service.ChatHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagLiveBenchmarkCliTest {

    @TempDir
    Path tempDir;

    @Test
    void writesRunArtifactsAndRefreshesCheatsheetFromLiveClient() throws Exception {
        Path dataset = tempDir.resolve("dataset.jsonl");
        Files.writeString(dataset, """
                {"id":"pass_case","query":"讲一讲高噪声场景","scopeMode":"MANUAL_SOURCE","scope":{"paperIds":["paper-a"],"paperTitles":["Paper A"]},"expectedRoute":"MANUAL_SOURCE_QA","requiredEvidenceRegex":["increasing noise"],"forbiddenAnswerRegex":["没有找到足够可靠"],"forbiddenEvidenceRegex":["^\\\\d+$"],"expectedPaperIds":["paper-a"],"requiresCitation":true}
                """);
        Path registry = tempDir.resolve("harnesses.yaml");
        Files.writeString(registry, """
                harnesses:
                  - id: current-evidence-ledger
                    name: Current Evidence Ledger
                    description: live harness
                    retrieval: adaptive-hybrid
                    planner: deterministic-first
                    verifier: enabled
                    status: runnable
                benchmarks:
                  - id: product-rescue-smoke
                    name: Product Rescue Smoke
                    tier: product
                    task: source-grounded paper chat regression
                    status: runnable
                    path: dataset.jsonl
                    source: local
                    primaryMetric: passRate
                    cases: "1"
                """);
        RagLiveBenchmarkRunner.LiveChatClient client = ignored -> new RagLiveBenchmarkRunner.LiveChatResponse(
                "论文讨论了 high noise 场景。[1]",
                Map.of(1, reference("paper-a", "The experiment studies increasing noise.")),
                Map.of("route", "MANUAL_SOURCE_QA", "acceptedEvidenceCount", 1, "scannedCount", 3)
        );

        RagLiveBenchmarkCli.run(new RagLiveBenchmarkCli.Options(
                dataset,
                tempDir.resolve("runs"),
                registry,
                tempDir.resolve("CHEATSHEET.md"),
                "current-evidence-ledger",
                "product-rescue-smoke",
                "run-1",
                "2026-06-23T12:00:00Z"
        ), client);

        assertTrue(Files.exists(tempDir.resolve("runs/run-1/run.json")));
        assertTrue(Files.exists(tempDir.resolve("runs/run-1/scorecard.json")));
        assertTrue(Files.readString(tempDir.resolve("runs/run-1/scorecard.json")).contains("\"passRate\":1.0"));
        assertTrue(Files.readString(tempDir.resolve("CHEATSHEET.md")).contains("| current-evidence-ledger | Product Rescue Smoke | product | 1 | Pass 100.0% |"));
    }

    private ChatHandler.ReferenceInfo reference(String paperId, String matchedText) {
        return new ChatHandler.ReferenceInfo(
                paperId,
                "Paper",
                "paper.pdf",
                2,
                matchedText,
                "HYBRID",
                "Hybrid",
                "query",
                matchedText,
                matchedText,
                0.9d,
                1,
                "PARAGRAPH",
                "Method",
                1,
                null,
                "MinerU",
                "self-hosted",
                "TEXT",
                null,
                null,
                null,
                "NORMAL_TEXT",
                "HYBRID",
                "GENERAL",
                "general",
                null,
                null,
                false
        );
    }
}
