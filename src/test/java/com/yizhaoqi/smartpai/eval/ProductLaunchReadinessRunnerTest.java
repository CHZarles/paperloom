package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductLaunchReadinessRunnerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void stopsAfterFirstFailedGateAndMarksDownstreamGatesSkipped() throws Exception {
        ProductLaunchReadinessRunner runner = new ProductLaunchReadinessRunner(List.of(
                gate("product-launch-runtime-preflight", false),
                gate("product-pdf-launch-data-seed", true),
                gate("product-reading-live-launch-smoke", true),
                gate("product-reading-launch-trace-eval", true),
                gate("product-pdf-parser-smoke", true)
        ));

        Path runDir = runner.run(options("readiness-fail-fast"));

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(5, scorecard.path("caseCount").asInt());
        assertEquals(0, scorecard.path("passed").asInt());
        JsonNode rows = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases");
        assertEquals("product-launch-runtime-preflight", rows.get(0).path("caseId").asText());
        assertFalse(rows.get(0).path("passed").asBoolean());
        assertTrue(rows.get(0).path("failureClass").toString().contains("GATE_FAILED"));
        assertTrue(rows.get(0).path("diagnostics").path("childRunDir").asText().contains("product-launch-runtime-preflight"));
        for (int i = 1; i < rows.size(); i++) {
            assertFalse(rows.get(i).path("passed").asBoolean());
            assertTrue(rows.get(i).path("failureClass").toString().contains("SKIPPED_DUE_TO_PREVIOUS_GATE"));
            assertEquals("product-launch-runtime-preflight",
                    rows.get(i).path("diagnostics").path("blockingGateId").asText());
        }
        String remediation = Files.readString(runDir.resolve("remediation.md"));
        assertTrue(remediation.contains("Status: not launch-ready"));
        assertTrue(remediation.contains("Blocking gate: `product-launch-runtime-preflight`"));
        assertTrue(remediation.contains("product-pdf-launch-data-seed"));
        assertTrue(remediation.contains("SKIPPED_DUE_TO_PREVIOUS_GATE"));
        assertTrue(remediation.contains("product-launch-runtime-preflight/remediation.md"));
    }

    @Test
    void passesOnlyWhenEveryGatePasses() throws Exception {
        ProductLaunchReadinessRunner runner = new ProductLaunchReadinessRunner(List.of(
                gate("product-launch-runtime-preflight", true),
                gate("product-pdf-launch-data-seed", true),
                gate("product-reading-live-launch-smoke", true),
                gate("product-reading-launch-trace-eval", true),
                gate("product-pdf-parser-smoke", true)
        ));

        Path runDir = runner.run(options("readiness-pass"));

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(5, scorecard.path("caseCount").asInt());
        assertEquals(5, scorecard.path("passed").asInt());
        assertEquals(0, scorecard.path("failed").asInt());
        JsonNode rows = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases");
        for (JsonNode row : rows) {
            assertTrue(row.path("passed").asBoolean());
            assertTrue(row.path("failureClass").isEmpty());
            assertTrue(row.path("diagnostics").path("childRunDir").asText().contains(row.path("caseId").asText()));
        }
        String remediation = Files.readString(runDir.resolve("remediation.md"));
        assertTrue(remediation.contains("Status: launch-ready"));
        assertTrue(remediation.contains("All launch gates passed"));
        assertTrue(remediation.contains("product-pdf-parser-smoke"));
    }

    private ProductLaunchReadinessRunner.Options options(String runId) {
        return new ProductLaunchReadinessRunner.Options(
                tempDir.resolve("runs"),
                runId,
                "2026-07-07T14:00:00Z",
                "product-launch-readiness",
                "product-launch-readiness"
        );
    }

    private ProductLaunchReadinessRunner.LaunchGate gate(String gateId, boolean passes) {
        return new ProductLaunchReadinessRunner.LaunchGate(
                gateId,
                context -> writeChildRun(context, passes)
        );
    }

    private Path writeChildRun(ProductLaunchReadinessRunner.GateContext context, boolean passes) throws Exception {
        RagBenchmarkVerdict verdict = new RagBenchmarkVerdict(
                context.gateId(),
                passes,
                passes ? List.of() : List.of("child_gate_failed"),
                passes ? List.of() : List.of("RUNTIME_UNAVAILABLE")
        );
        Path runDir = RagEvalRunWriter.write(
                context.runsRoot(),
                context.childRunId(),
                Instant.now().toString(),
                context.gateId(),
                context.gateId(),
                "child-gate",
                new RagBenchmarkRun(
                        List.of(new RagBenchmarkCase(
                                context.gateId(),
                                "Child gate " + context.gateId(),
                                "zh",
                                "LAUNCH_GATE",
                                "PRODUCT_LAUNCH",
                                new RagBenchmarkCase.Scope(List.of(), List.of()),
                                "CHILD_GATE",
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                false
                        )),
                        List.of(new RagBenchmarkActual("CHILD_GATE", passes ? "passed" : "failed", Map.of(), Map.of())),
                        List.of(verdict)
                ),
                Map.of()
        );
        if (!passes) {
            Files.writeString(runDir.resolve("remediation.md"),
                    "# Child Remediation\n\nFix child gate `" + context.gateId() + "`.\n");
        }
        return runDir;
    }
}
