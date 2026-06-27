package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvalRunWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesRunScorecardAndReportArtifacts() throws Exception {
        RagBenchmarkCase testCase = RagBenchmarkCase.productRescueCase(
                "grep_high_noise_zh",
                "讲一讲高噪声场景",
                "MANUAL_SOURCE_QA",
                List.of("paper-a"),
                List.of("increasing noise"),
                List.of("没有找到足够可靠")
        );
        RagBenchmarkRun run = new RagBenchmarkRun(
                List.of(testCase),
                List.of(new RagBenchmarkActual("MANUAL_SOURCE_QA", "ok [1]", Map.of(), Map.of("scannedCount", 6))),
                List.of(new RagBenchmarkVerdict("grep_high_noise_zh", true, List.of(), List.of()))
        );

        Path runDir = RagEvalRunWriter.write(
                tempDir,
                "2026-06-23T120000Z-current-evidence-ledger-product-rescue-smoke",
                "2026-06-23T12:00:00Z",
                "current-evidence-ledger",
                "product-rescue-smoke",
                "eval/rag/product-rescue-smoke.jsonl",
                run,
                Map.of()
        );

        Path runJson = runDir.resolve("run.json");
        Path scorecardJson = runDir.resolve("scorecard.json");
        Path report = runDir.resolve("report.md");
        assertTrue(Files.exists(runJson));
        assertTrue(Files.exists(scorecardJson));
        assertTrue(Files.exists(report));
        assertTrue(Files.readString(runJson).contains("\"harnessId\":\"current-evidence-ledger\""));
        assertTrue(Files.readString(runJson).contains("\"datasetPath\":\"eval/rag/product-rescue-smoke.jsonl\""));
        assertTrue(Files.readString(runJson).contains("\"markdown\":\"ok [1]\""));
        assertTrue(Files.readString(scorecardJson).contains("\"passRate\":1.0"));
        assertTrue(Files.readString(report).contains("Pass rate: 1/1"));
    }
}
