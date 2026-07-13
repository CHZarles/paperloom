package io.github.chzarles.paperloom.eval;

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
    void rollsUpFailedChildCasesForBlockingGate() throws Exception {
        ProductLaunchReadinessRunner runner = new ProductLaunchReadinessRunner(List.of(
                gateWithCases("product-launch-runtime-preflight", List.of(
                        childCase("backend_login", false, List.of("BACKEND_UNAVAILABLE")),
                        childCase("mysql_tcp", false, List.of("MYSQL_UNAVAILABLE")),
                        childCase("redis_tcp", true, List.of())
                )),
                gate("product-pdf-launch-data-seed", true)
        ));

        Path runDir = runner.run(options("readiness-child-rollup"));

        JsonNode failedGate = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile())
                .path("cases")
                .get(0);
        JsonNode diagnostics = failedGate.path("diagnostics");
        assertTrue(diagnostics.path("childFailedCaseDetailsAvailable").asBoolean());
        JsonNode failedCases = diagnostics.path("childFailedCases");
        assertEquals(2, failedCases.size());
        assertEquals("backend_login", failedCases.get(0).path("caseId").asText());
        assertTrue(failedCases.get(0).path("failureClass").toString().contains("BACKEND_UNAVAILABLE"));
        assertEquals("mysql_tcp", failedCases.get(1).path("caseId").asText());
        assertTrue(failedCases.get(1).path("failureClass").toString().contains("MYSQL_UNAVAILABLE"));
        assertFalse(failedCases.toString().contains("redis_tcp"));

        String remediation = Files.readString(runDir.resolve("remediation.md"));
        assertTrue(remediation.contains("Failed child cases:"));
        assertTrue(remediation.contains("`backend_login`: BACKEND_UNAVAILABLE"));
        assertTrue(remediation.contains("`mysql_tcp`: MYSQL_UNAVAILABLE"));
        assertFalse(remediation.contains("diagnostic-secret"));
    }

    @Test
    void keepsWrapperRobustWhenFailedChildRunJsonIsMissing() throws Exception {
        ProductLaunchReadinessRunner runner = new ProductLaunchReadinessRunner(List.of(
                new ProductLaunchReadinessRunner.LaunchGate(
                        "product-launch-runtime-preflight",
                        context -> {
                            Path childRunDir = writeChildRun(context, false);
                            Files.delete(childRunDir.resolve("run.json"));
                            return childRunDir;
                        }
                ),
                gate("product-pdf-launch-data-seed", true)
        ));

        Path runDir = runner.run(options("readiness-child-rollup-unavailable"));

        JsonNode failedGate = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile())
                .path("cases")
                .get(0);
        JsonNode diagnostics = failedGate.path("diagnostics");
        assertFalse(diagnostics.path("childFailedCaseDetailsAvailable").asBoolean());
        assertEquals("CHILD_RUN_JSON_UNAVAILABLE",
                diagnostics.path("childFailedCaseDetailsUnavailableReason").asText());
        assertTrue(diagnostics.path("childFailedCases").isArray());
        assertEquals(0, diagnostics.path("childFailedCases").size());

        String remediation = Files.readString(runDir.resolve("remediation.md"));
        assertTrue(remediation.contains("Failed child case details unavailable."));
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

    private ProductLaunchReadinessRunner.LaunchGate gateWithCases(String gateId, List<ChildCase> childCases) {
        return new ProductLaunchReadinessRunner.LaunchGate(
                gateId,
                context -> writeChildRun(context, childCases)
        );
    }

    private ChildCase childCase(String caseId, boolean passes, List<String> failureClass) {
        return new ChildCase(caseId, passes, failureClass);
    }

    private Path writeChildRun(ProductLaunchReadinessRunner.GateContext context, boolean passes) throws Exception {
        return writeChildRun(context, List.of(childCase(
                context.gateId(),
                passes,
                passes ? List.of() : List.of("RUNTIME_UNAVAILABLE")
        )));
    }

    private Path writeChildRun(ProductLaunchReadinessRunner.GateContext context,
                               List<ChildCase> childCases) throws Exception {
        List<ChildCase> safeCases = childCases == null ? List.of() : childCases;
        List<RagBenchmarkCase> cases = safeCases.stream()
                .map(childCase -> new RagBenchmarkCase(
                        childCase.caseId(),
                        "Child gate case " + childCase.caseId(),
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
                ))
                .toList();
        List<RagBenchmarkActual> actuals = safeCases.stream()
                .map(childCase -> new RagBenchmarkActual(
                        "CHILD_GATE",
                        childCase.passes() ? "passed" : "failed with diagnostic-secret",
                        Map.of(),
                        childCase.passes() ? Map.of() : Map.of("rawDiagnostic", "diagnostic-secret")
                ))
                .toList();
        List<RagBenchmarkVerdict> verdicts = safeCases.stream()
                .map(childCase -> new RagBenchmarkVerdict(
                        childCase.caseId(),
                        childCase.passes(),
                        childCase.passes() ? List.of() : List.of("child_gate_failed"),
                        childCase.passes() ? List.of() : childCase.failureClass()
                ))
                .toList();
        boolean passes = verdicts.stream().allMatch(RagBenchmarkVerdict::passed);
        Path runDir = RagEvalRunWriter.write(
                context.runsRoot(),
                context.childRunId(),
                Instant.now().toString(),
                context.gateId(),
                context.gateId(),
                "child-gate",
                new RagBenchmarkRun(cases, actuals, verdicts),
                Map.of()
        );
        if (!passes) {
            Files.writeString(runDir.resolve("remediation.md"),
                    "# Child Remediation\n\nFix child gate `" + context.gateId() + "`.\n");
        }
        return runDir;
    }

    private record ChildCase(String caseId, boolean passes, List<String> failureClass) {
        private ChildCase {
            failureClass = failureClass == null ? List.of() : List.copyOf(failureClass);
        }
    }
}
