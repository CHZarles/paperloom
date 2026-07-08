package com.yizhaoqi.smartpai.eval.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.eval.RagBenchmarkDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenDatasetCliTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void validatesCommittedManifest() throws Exception {
        int exitCode = GoldenDatasetCli.run(new String[]{
                "validate",
                "--manifest", "research/golden-data/manifest.yaml"
        });

        assertEquals(0, exitCode);
    }

    @Test
    void exportsCompatibilityJsonl() throws Exception {
        Path output = tempDir.resolve("golden.jsonl");

        int exitCode = GoldenDatasetCli.run(new String[]{
                "export-rag",
                "--manifest", "research/golden-data/manifest.yaml",
                "--output", output.toString()
        });

        assertEquals(0, exitCode);
        assertEquals(7, RagBenchmarkDataset.load(output).size());
    }

    @Test
    void scoresTraceToJson() throws Exception {
        Path output = tempDir.resolve("score.json");

        int exitCode = GoldenDatasetCli.run(new String[]{
                "score-trace",
                "--manifest", "research/golden-data/manifest.yaml",
                "--trace", "research/golden-data/run-traces/transformer-adam-pass.yaml",
                "--output", output.toString()
        });

        assertEquals(0, exitCode);
        JsonNode json = OBJECT_MAPPER.readTree(Files.readString(output));
        assertTrue(json.path("passed").asBoolean());
        assertEquals("transformer_adam_params_001", json.path("case_id").asText());
    }
}
