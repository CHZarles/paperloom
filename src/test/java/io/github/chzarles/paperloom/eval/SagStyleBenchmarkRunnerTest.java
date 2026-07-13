package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SagStyleBenchmarkRunnerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void writesScorecardWithChunkEvidenceMetrics() throws Exception {
        Path casesPath = tempDir.resolve("cases.jsonl");
        Path chunksPath = tempDir.resolve("chunks.jsonl");
        Files.writeString(casesPath, """
                {"id":"dense_results","query":"What are the evaluation results for DenseRetriever?","scope":{"paperIds":["paper-a"],"paperTitles":[]},"requiredEvidenceRegex":["78 F1"],"expectedPaperIds":["paper-a"]}
                """);
        Files.writeString(chunksPath, """
                {"paperId":"paper-a","paperTitle":"DenseRetriever Evaluation","originalFilename":"dense.pdf","pageNumber":1,"chunkId":1,"sectionTitle":"Method","sourceKind":"TEXT","text":"We compare DenseRetriever against a Sparse Baseline in the evaluation setup."}
                {"paperId":"paper-a","paperTitle":"DenseRetriever Evaluation","originalFilename":"dense.pdf","pageNumber":2,"chunkId":2,"sectionTitle":"Results","sourceKind":"TABLE","text":"Table 2 reports the Sparse Baseline reaches 71 F1 while the neural run reaches 78 F1."}
                """);

        Path runDir = SagStyleBenchmarkRunner.run(new SagStyleBenchmarkRunner.Options(
                casesPath,
                chunksPath,
                tempDir.resolve("runs"),
                "2026-06-24T130000Z-sag-style-fast-mode-test",
                "2026-06-24T13:00:00Z",
                "sag-style-fast-mode",
                "sag-style-test",
                3
        ));

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals("sag-style-fast-mode", scorecard.path("harnessId").asText());
        assertEquals(1, scorecard.path("passed").asInt());
        assertEquals(1.0d, scorecard.path("metrics").path("chunkEvidenceHitAt1").asDouble());
        String runJson = Files.readString(runDir.resolve("run.json"));
        assertTrue(runJson.contains("two-hop-entity"));
    }

    @Test
    void mainParsesOptionsAndRunsBenchmark() throws Exception {
        Path casesPath = tempDir.resolve("main-cases.jsonl");
        Path chunksPath = tempDir.resolve("main-chunks.jsonl");
        Path runsRoot = tempDir.resolve("main-runs");
        Files.writeString(casesPath, """
                {"id":"dense_results","query":"What are the evaluation results for DenseRetriever?","scope":{"paperIds":["paper-a"],"paperTitles":[]},"requiredEvidenceRegex":["78 F1"],"expectedPaperIds":["paper-a"]}
                """);
        Files.writeString(chunksPath, """
                {"paperId":"paper-a","paperTitle":"DenseRetriever Evaluation","originalFilename":"dense.pdf","pageNumber":1,"chunkId":1,"sectionTitle":"Method","sourceKind":"TEXT","text":"We compare DenseRetriever against a Sparse Baseline in the evaluation setup."}
                {"paperId":"paper-a","paperTitle":"DenseRetriever Evaluation","originalFilename":"dense.pdf","pageNumber":2,"chunkId":2,"sectionTitle":"Results","sourceKind":"TABLE","text":"Table 2 reports the Sparse Baseline reaches 71 F1 while the neural run reaches 78 F1."}
                """);

        SagStyleBenchmarkRunner.main(new String[]{
                "--cases", casesPath.toString(),
                "--chunks", chunksPath.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "2026-06-24T131000Z-sag-style-fast-mode-main-test",
                "--started-at", "2026-06-24T13:10:00Z",
                "--harness-id", "sag-style-fast-mode",
                "--dataset-id", "sag-style-main-test",
                "--top-k", "3"
        });

        assertTrue(Files.exists(runsRoot.resolve("2026-06-24T131000Z-sag-style-fast-mode-main-test/scorecard.json")));
    }
}
