package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProductLaunchReadinessRunner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ROUTE = "PRODUCT_LAUNCH_READINESS";
    private static final List<String> DEFAULT_GATE_IDS = List.of(
            "product-launch-runtime-preflight",
            "product-pdf-launch-data-seed",
            "product-reading-live-launch-smoke",
            "product-reading-launch-trace-eval",
            "product-pdf-parser-smoke"
    );

    private final List<LaunchGate> gates;

    public ProductLaunchReadinessRunner(List<LaunchGate> gates) {
        this.gates = gates == null ? List.of() : List.copyOf(gates);
    }

    public Path run(Options options) throws IOException {
        Options safeOptions = options == null ? Options.defaults() : options;
        List<LaunchGate> safeGates = gates.isEmpty() ? defaultFailingGates() : gates;
        List<GateResult> results = new ArrayList<>();
        String blockingGateId = null;
        RagEvalGateStatus.GateResult blockingGateResult = null;
        for (int i = 0; i < safeGates.size(); i++) {
            LaunchGate gate = safeGates.get(i);
            if (blockingGateId != null) {
                results.add(skippedResult(gate.gateId(), blockingGateId, blockingGateResult));
                continue;
            }
            GateContext context = new GateContext(
                    safeOptions.runsRoot(),
                    safeOptions.runId(),
                    safeOptions.startedAt(),
                    i + 1,
                    gate.gateId()
            );
            GateResult result = runGate(gate, context);
            results.add(result);
            if (!result.passed()) {
                blockingGateId = gate.gateId();
                blockingGateResult = result.childGateResult();
            }
        }
        Path runDir = RagEvalRunWriter.write(
                safeOptions.runsRoot(),
                safeOptions.runId(),
                safeOptions.startedAt(),
                safeOptions.harnessId(),
                safeOptions.datasetId(),
                "product-launch-readiness",
                new RagBenchmarkRun(
                        results.stream().map(ProductLaunchReadinessRunner::benchmarkCase).toList(),
                        results.stream().map(ProductLaunchReadinessRunner::actual).toList(),
                        results.stream().map(ProductLaunchReadinessRunner::verdict).toList()
                ),
                metrics(results)
        );
        Files.writeString(runDir.resolve("remediation.md"), remediationMarkdown(safeOptions, results));
        return runDir;
    }

    private GateResult runGate(LaunchGate gate, GateContext context) {
        try {
            Path childRunDir = gate.command().run(context);
            RagEvalGateStatus.GateResult child = RagEvalGateStatus.read(childRunDir);
            Map<String, Object> diagnostics = diagnostics(child);
            if (child.passing()) {
                return new GateResult(gate.gateId(), true, List.of(), List.of(), diagnostics, child);
            }
            return new GateResult(
                    gate.gateId(),
                    false,
                    List.of("launch_gate_failed(" + gate.gateId() + ")"),
                    List.of("GATE_FAILED"),
                    diagnostics,
                    child
            );
        } catch (Exception exception) {
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("gateId", gate.gateId());
            diagnostics.put("childRunId", context.childRunId());
            diagnostics.put("failure", sanitize(exception.getClass().getSimpleName() + ": " + exception.getMessage()));
            return new GateResult(
                    gate.gateId(),
                    false,
                    List.of("launch_gate_command_failed(" + diagnostics.get("failure") + ")"),
                    List.of("GATE_COMMAND_FAILED"),
                    diagnostics,
                    null
            );
        }
    }

    private static GateResult skippedResult(String gateId,
                                            String blockingGateId,
                                            RagEvalGateStatus.GateResult blockingGateResult) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("gateId", gateId);
        diagnostics.put("blockingGateId", blockingGateId);
        if (blockingGateResult != null) {
            diagnostics.put("blockingRunDir", blockingGateResult.runDir().toString());
            diagnostics.put("blockingRunId", blockingGateResult.runId());
            diagnostics.put("blockingPassed", blockingGateResult.passed());
            diagnostics.put("blockingCaseCount", blockingGateResult.caseCount());
            diagnostics.put("blockingPassRate", blockingGateResult.passRate());
        }
        return new GateResult(
                gateId,
                false,
                List.of("launch_gate_skipped(blockedBy=" + blockingGateId + ")"),
                List.of("SKIPPED_DUE_TO_PREVIOUS_GATE"),
                diagnostics,
                null
        );
    }

    private static Map<String, Object> diagnostics(RagEvalGateStatus.GateResult child) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("childRunDir", child.runDir().toString());
        diagnostics.put("childRunId", child.runId());
        diagnostics.put("childHarnessId", child.harnessId());
        diagnostics.put("childCaseCount", child.caseCount());
        diagnostics.put("childPassed", child.passed());
        diagnostics.put("childFailed", child.failed());
        diagnostics.put("childPassRate", child.passRate());
        ChildFailedCaseRollup childFailedCases = childFailedCaseRollup(child.runDir());
        diagnostics.put("childFailedCaseDetailsAvailable", childFailedCases.available());
        diagnostics.put("childFailedCases", childFailedCases.failedCases().stream()
                .map(ChildFailedCase::toDiagnostic)
                .toList());
        if (!childFailedCases.available()) {
            diagnostics.put("childFailedCaseDetailsUnavailableReason", "CHILD_RUN_JSON_UNAVAILABLE");
        }
        return diagnostics;
    }

    private static ChildFailedCaseRollup childFailedCaseRollup(Path childRunDir) {
        try {
            JsonNode cases = OBJECT_MAPPER.readTree(childRunDir.resolve("run.json").toFile()).path("cases");
            if (!cases.isArray()) {
                return ChildFailedCaseRollup.unavailable();
            }
            List<ChildFailedCase> failedCases = new ArrayList<>();
            for (JsonNode childCase : cases) {
                if (childCase.path("passed").asBoolean(false)) {
                    continue;
                }
                failedCases.add(new ChildFailedCase(
                        childCase.path("caseId").asText("unknown_child_case"),
                        failureClass(childCase.path("failureClass"))
                ));
            }
            return new ChildFailedCaseRollup(true, failedCases);
        } catch (Exception exception) {
            return ChildFailedCaseRollup.unavailable();
        }
    }

    private static List<String> failureClass(JsonNode failureClassNode) {
        if (failureClassNode == null || failureClassNode.isMissingNode() || failureClassNode.isNull()) {
            return List.of();
        }
        if (failureClassNode.isArray()) {
            List<String> classes = new ArrayList<>();
            for (JsonNode item : failureClassNode) {
                String value = sanitize(item.asText(""));
                if (!value.isBlank()) {
                    classes.add(value);
                }
            }
            return classes;
        }
        String value = sanitize(failureClassNode.asText(""));
        return value.isBlank() ? List.of() : List.of(value);
    }

    private static RagBenchmarkCase benchmarkCase(GateResult result) {
        return new RagBenchmarkCase(
                result.gateId(),
                "Product launch readiness gate: " + result.gateId(),
                "zh",
                "PRODUCT_LAUNCH_READINESS",
                "PRODUCT_LAUNCH",
                new RagBenchmarkCase.Scope(List.of(), List.of()),
                ROUTE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false
        );
    }

    private static RagBenchmarkActual actual(GateResult result) {
        return new RagBenchmarkActual(
                ROUTE,
                result.passed() ? "launch gate passed" : String.join("; ", result.failures()),
                Map.of(),
                result.diagnostics()
        );
    }

    private static RagBenchmarkVerdict verdict(GateResult result) {
        return new RagBenchmarkVerdict(
                result.gateId(),
                result.passed(),
                result.failures(),
                result.failureClass()
        );
    }

    private static Map<String, Double> metrics(List<GateResult> results) {
        long passed = results.stream().filter(GateResult::passed).count();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("launchReadinessPassRate", fraction(passed, results.size()));
        metrics.put("launchReadinessGateCount", (double) results.size());
        metrics.put("launchReadinessPassedGateCount", (double) passed);
        metrics.put("launchReadinessSkippedGateCount", (double) results.stream()
                .filter(result -> result.failureClass().contains("SKIPPED_DUE_TO_PREVIOUS_GATE"))
                .count());
        return metrics;
    }

    private static String remediationMarkdown(Options options, List<GateResult> results) {
        Options safeOptions = options == null ? Options.defaults() : options;
        List<GateResult> safeResults = results == null ? List.of() : results;
        boolean launchReady = !safeResults.isEmpty() && safeResults.stream().allMatch(GateResult::passed);
        StringBuilder builder = new StringBuilder()
                .append("# Product Launch Readiness Remediation\n\n")
                .append("Run: `").append(safeOptions.runId()).append("`\n\n")
                .append("Status: ").append(launchReady ? "launch-ready" : "not launch-ready").append("\n\n");

        if (launchReady) {
            builder.append("All launch gates passed. Child run evidence:\n\n");
            for (GateResult result : safeResults) {
                builder.append("- `").append(result.gateId()).append("`: ")
                        .append(childRunDir(result))
                        .append("\n");
            }
            builder.append("\n");
        } else {
            GateResult blocker = safeResults.stream()
                    .filter(result -> !result.passed())
                    .findFirst()
                    .orElse(null);
            if (blocker != null) {
                builder.append("Blocking gate: `").append(blocker.gateId()).append("`\n\n");
                String childRunDir = childRunDir(blocker);
                if (!childRunDir.isBlank()) {
                    builder.append("Blocking child run: `").append(childRunDir).append("`\n\n");
                    Path childRemediation = Path.of(childRunDir).resolve("remediation.md");
                    if (Files.exists(childRemediation)) {
                        builder.append("Child remediation: `").append(childRemediation).append("`\n\n");
                    }
                }
                appendChildFailedCases(builder, blocker);
                List<GateResult> skipped = safeResults.stream()
                        .filter(result -> result.failureClass().contains("SKIPPED_DUE_TO_PREVIOUS_GATE"))
                        .toList();
                if (!skipped.isEmpty()) {
                    builder.append("Skipped downstream gates:\n\n");
                    for (GateResult result : skipped) {
                        builder.append("- `").append(result.gateId())
                                .append("`: SKIPPED_DUE_TO_PREVIOUS_GATE\n");
                    }
                    builder.append("\n");
                }
            }
        }

        builder.append("## Gate Order\n\n");
        for (int i = 0; i < DEFAULT_GATE_IDS.size(); i++) {
            builder.append(i + 1).append(". `").append(DEFAULT_GATE_IDS.get(i)).append("`\n");
        }
        return builder.toString();
    }

    private static void appendChildFailedCases(StringBuilder builder, GateResult blocker) {
        Map<String, Object> diagnostics = blocker == null ? Map.of() : blocker.diagnostics();
        if (Boolean.FALSE.equals(diagnostics.get("childFailedCaseDetailsAvailable"))) {
            builder.append("Failed child case details unavailable.\n\n");
            return;
        }
        Object failedCasesValue = diagnostics.get("childFailedCases");
        if (!(failedCasesValue instanceof List<?> failedCases) || failedCases.isEmpty()) {
            return;
        }
        builder.append("Failed child cases:\n\n");
        for (Object failedCaseValue : failedCases) {
            if (!(failedCaseValue instanceof Map<?, ?> failedCase)) {
                continue;
            }
            Object caseIdValue = failedCase.get("caseId");
            String caseId = caseIdValue == null ? "unknown_child_case" : String.valueOf(caseIdValue);
            Object failureClassValue = failedCase.get("failureClass");
            String failureClass = failureClassValue instanceof List<?> classes
                    ? String.join(", ", classes.stream().map(String::valueOf).toList())
                    : String.valueOf(failureClassValue);
            builder.append("- `").append(caseId).append("`");
            if (!failureClass.isBlank() && !"null".equals(failureClass)) {
                builder.append(": ").append(failureClass);
            }
            builder.append("\n");
        }
        builder.append("\n");
    }

    private static String childRunDir(GateResult result) {
        if (result == null || result.childGateResult() == null || result.childGateResult().runDir() == null) {
            return "";
        }
        return result.childGateResult().runDir().toString();
    }

    private static List<LaunchGate> defaultFailingGates() {
        return DEFAULT_GATE_IDS.stream()
                .map(gateId -> new LaunchGate(gateId, context -> {
                    throw new IllegalStateException("no launch gate command configured");
                }))
                .toList();
    }

    private static double fraction(double numerator, double denominator) {
        return denominator == 0.0d ? 0.0d : numerator / denominator;
    }

    private static String sanitize(String message) {
        String safeMessage = message == null || message.isBlank() ? "launch gate failed" : message;
        return safeMessage
                .replaceAll("(?i)(password|passwd|pwd|token|secret|api[_-]?key)=([^\\s,;]+)", "$1=<redacted>")
                .replaceAll("(?i)(password|passwd|pwd|token|secret|api[_-]?key)\\s*:\\s*([^\\s,;]+)", "$1=<redacted>")
                .replaceAll("://([^:/@\\s]+):([^@/\\s]+)@", "://$1:<redacted>@");
    }

    public record LaunchGate(String gateId, GateCommand command) {
        public LaunchGate {
            gateId = blankToDefault(gateId, "launch-gate");
        }
    }

    @FunctionalInterface
    public interface GateCommand {
        Path run(GateContext context) throws Exception;
    }

    public record GateContext(
            Path runsRoot,
            String parentRunId,
            String startedAt,
            int sequence,
            String gateId
    ) {
        public GateContext {
            runsRoot = runsRoot == null ? Path.of("eval/rag/runs") : runsRoot;
            parentRunId = blankToDefault(parentRunId, "product-launch-readiness");
            startedAt = blankToDefault(startedAt, Instant.now().toString());
            sequence = Math.max(1, sequence);
            gateId = blankToDefault(gateId, "launch-gate");
        }

        String childRunId() {
            return parentRunId + "-" + String.format("%02d", sequence) + "-" + gateId;
        }
    }

    public record Options(
            Path runsRoot,
            String runId,
            String startedAt,
            String harnessId,
            String datasetId
    ) {
        public Options {
            runsRoot = runsRoot == null ? Path.of("eval/rag/runs") : runsRoot;
            startedAt = blankToDefault(startedAt, Instant.now().toString());
            harnessId = blankToDefault(harnessId, "product-launch-readiness");
            datasetId = blankToDefault(datasetId, "product-launch-readiness");
            runId = blankToDefault(runId, defaultRunId(startedAt, harnessId, datasetId));
        }

        static Options defaults() {
            return new Options(
                    Path.of("eval/rag/runs"),
                    null,
                    Instant.now().toString(),
                    "product-launch-readiness",
                    "product-launch-readiness"
            );
        }
    }

    private record GateResult(
            String gateId,
            boolean passed,
            List<String> failures,
            List<String> failureClass,
            Map<String, Object> diagnostics,
            RagEvalGateStatus.GateResult childGateResult
    ) {
        private GateResult {
            gateId = blankToDefault(gateId, "launch-gate");
            failures = failures == null ? List.of() : List.copyOf(failures);
            failureClass = failureClass == null ? List.of() : List.copyOf(failureClass);
            diagnostics = diagnostics == null ? Map.of() : new LinkedHashMap<>(diagnostics);
        }
    }

    private record ChildFailedCaseRollup(boolean available, List<ChildFailedCase> failedCases) {
        private ChildFailedCaseRollup {
            failedCases = failedCases == null ? List.of() : List.copyOf(failedCases);
        }

        static ChildFailedCaseRollup unavailable() {
            return new ChildFailedCaseRollup(false, List.of());
        }
    }

    private record ChildFailedCase(String caseId, List<String> failureClass) {
        private ChildFailedCase {
            caseId = blankToDefault(caseId, "unknown_child_case");
            failureClass = failureClass == null ? List.of() : List.copyOf(failureClass);
        }

        Map<String, Object> toDiagnostic() {
            Map<String, Object> diagnostic = new LinkedHashMap<>();
            diagnostic.put("caseId", caseId);
            diagnostic.put("failureClass", failureClass);
            return diagnostic;
        }
    }

    static String defaultRunId(String startedAt, String harnessId, String datasetId) {
        return startedAt
                .replace(":", "")
                .replace(".", "")
                .replace("Z", "Z")
                + "-" + harnessId + "-" + datasetId;
    }

    private static String blankToDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
