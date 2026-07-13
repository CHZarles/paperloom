package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductPdfParserSmokeCliTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void springStartupFailureStillWritesFailedEvalRunForAllManifestCases() throws Exception {
        Path manifest = tempDir.resolve("manifest.jsonl");
        Files.writeString(manifest, """
                {"id":"paper_a","path":"paper-a.pdf","expectedMinChunks":2,"expectedMinPages":1,"requiresParserArtifact":true}
                {"id":"paper_b","path":"paper-b.pdf","expectedMinChunks":3,"requiresPageScreenshot":true}
                """);

        Path runDir = ProductPdfParserSmokeCli.runCommand(new String[]{
                "--manifest", manifest.toString(),
                "--runs-root", tempDir.resolve("runs").toString(),
                "--run-id", "spring-startup-failure"
        }, startupArgs -> {
            throw new IllegalStateException("database unavailable password=hunter2 token=abc123");
        });

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(2, scorecard.path("caseCount").asInt());
        assertEquals(0, scorecard.path("passed").asInt());
        JsonNode rows = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases");
        for (JsonNode row : rows) {
            assertFalse(row.path("passed").asBoolean());
            assertTrue(row.path("failureClass").toString().contains("RUNTIME_UNAVAILABLE"));
            assertTrue(row.path("failures").toString().contains("pdf_parser_smoke_startup_failed"));
            assertTrue(row.path("diagnostics").path("startupFailure").asText().contains("database unavailable"));
            assertFalse(row.path("diagnostics").path("startupFailure").asText().contains("hunter2"));
            assertFalse(row.path("diagnostics").path("startupFailure").asText().contains("abc123"));
        }
    }
}
