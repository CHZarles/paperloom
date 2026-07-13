package io.github.chzarles.paperloom.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagBenchmarkReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesMarkdownAndJsonSummaryForProductRescueRun() throws Exception {
        RagBenchmarkCase passingCase = RagBenchmarkCase.productRescueCase(
                "pass_case",
                "讲一讲高噪声场景",
                "MANUAL_SOURCE_QA",
                List.of("paper-a"),
                List.of("increasing noise"),
                List.of("没有找到足够可靠")
        );
        RagBenchmarkCase failingCase = RagBenchmarkCase.productRescueCase(
                "bad_evidence_case",
                "这个文章讲了什么",
                "MANUAL_SOURCE_QA",
                List.of("paper-a"),
                List.of("agent harness"),
                List.of("没有找到足够可靠")
        );
        RagBenchmarkVerdict pass = new RagBenchmarkVerdict("pass_case", true, List.of(), List.of());
        RagBenchmarkVerdict fail = new RagBenchmarkVerdict(
                "bad_evidence_case",
                false,
                List.of("EVIDENCE_UNUSABLE:1"),
                List.of("BAD_EVIDENCE")
        );

        RagBenchmarkReportWriter.write(tempDir, List.of(passingCase, failingCase), List.of(pass, fail));

        Path markdown = tempDir.resolve("latest.md");
        Path json = tempDir.resolve("latest.json");
        assertTrue(Files.exists(markdown));
        assertTrue(Files.exists(json));
        String markdownText = Files.readString(markdown);
        assertTrue(markdownText.contains("Pass rate: 1/2"));
        assertTrue(markdownText.contains("BAD_EVIDENCE"));
        assertTrue(markdownText.contains("bad_evidence_case"));
        String jsonText = Files.readString(json);
        assertTrue(jsonText.contains("\"passed\":false"));
        assertTrue(jsonText.contains("\"caseId\":\"bad_evidence_case\""));
    }
}
