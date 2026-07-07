package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class ProductReadingLaunchTraceEvalRunner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ROUTE = "PRODUCT_READING_TRACE_EVAL";
    private static final String READING_TRACE_ARTIFACT = "PRODUCT_READING_REACT_TURN";

    public Path run(Options options) throws IOException {
        Options safeOptions = options == null ? Options.defaults() : options;
        List<ProductReadingLaunchTraceCase> cases = loadCases(safeOptions.casesPath());
        List<TraceDocument> traces = loadTraces(safeOptions.traceRoot());
        List<RagBenchmarkCase> benchmarkCases = new ArrayList<>();
        List<RagBenchmarkActual> actuals = new ArrayList<>();
        List<RagBenchmarkVerdict> verdicts = new ArrayList<>();

        for (ProductReadingLaunchTraceCase testCase : cases) {
            CaseEvaluation evaluation = evaluate(testCase, traces, safeOptions.traceRoot());
            benchmarkCases.add(benchmarkCase(testCase));
            actuals.add(actual(testCase, evaluation, traces.size()));
            verdicts.add(verdict(testCase, evaluation));
        }

        return RagEvalRunWriter.write(
                safeOptions.runsRoot(),
                safeOptions.runId(),
                safeOptions.startedAt(),
                safeOptions.harnessId(),
                safeOptions.datasetId(),
                safeOptions.casesPath().toString(),
                new RagBenchmarkRun(benchmarkCases, actuals, verdicts),
                metrics(verdicts, traces.size())
        );
    }

    static List<ProductReadingLaunchTraceCase> loadCases(Path path) throws IOException {
        List<ProductReadingLaunchTraceCase> cases = new ArrayList<>();
        for (String rawLine : Files.readAllLines(path)) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            cases.add(OBJECT_MAPPER.readValue(line, ProductReadingLaunchTraceCase.class));
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("Trace eval cases file is empty: " + path);
        }
        return List.copyOf(cases);
    }

    private List<TraceDocument> loadTraces(Path traceRoot) throws IOException {
        if (traceRoot == null || !Files.exists(traceRoot)) {
            return List.of();
        }
        List<TraceDocument> traces = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(traceRoot)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList()) {
                TraceDocument trace = readTrace(path);
                if (trace != null) {
                    traces.add(trace);
                }
            }
        }
        return List.copyOf(traces);
    }

    private TraceDocument readTrace(Path path) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(path.toFile());
            if (!READING_TRACE_ARTIFACT.equals(root.path("artifactType").asText())) {
                return null;
            }
            return new TraceDocument(
                    path,
                    root.path("resultStatus").asText(""),
                    root.path("answerEnvelope").path("answerType").asText(""),
                    values(root.path("toolCalls"), "toolName"),
                    values(root.path("productStateItems"), "kind"),
                    values(root.path("productStateItems"), "sourceTool"),
                    root.path("references").isArray() ? root.path("references").size() : 0
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private CaseEvaluation evaluate(ProductReadingLaunchTraceCase testCase,
                                    List<TraceDocument> traces,
                                    Path traceRoot) {
        List<String> validationFailures = validateCase(testCase);
        if (!validationFailures.isEmpty()) {
            return new CaseEvaluation(null, validationFailures, List.of("TRACE_CASE_INVALID"));
        }
        for (TraceDocument trace : traces) {
            if (matches(testCase, trace)) {
                return new CaseEvaluation(trace, List.of(), List.of());
            }
        }
        return new CaseEvaluation(
                null,
                List.of("matching_reading_trace_missing(" + requirementSummary(testCase) + ")"),
                List.of("TRACE_MISSING")
        );
    }

    private boolean matches(ProductReadingLaunchTraceCase testCase, TraceDocument trace) {
        if (testCase.expectedResultStatusValue() != null
                && !testCase.expectedResultStatusValue().equals(trace.resultStatus())) {
            return false;
        }
        if (testCase.requiredAnswerTypeValue() != null
                && !testCase.requiredAnswerTypeValue().equals(trace.answerType())) {
            return false;
        }
        if (!trace.toolNames().containsAll(testCase.requiredToolNames())) {
            return false;
        }
        if (!trace.productStateKinds().containsAll(testCase.requiredProductStateKinds())) {
            return false;
        }
        if (!trace.productStateSourceTools().containsAll(testCase.requiredProductStateSourceTools())) {
            return false;
        }
        return !testCase.referenceRequired() || trace.referenceCount() > 0;
    }

    private List<String> validateCase(ProductReadingLaunchTraceCase testCase) {
        List<String> failures = new ArrayList<>();
        if (testCase == null || testCase.id().isBlank()) {
            failures.add("trace_case_id_missing");
            return failures;
        }
        boolean hasRequirement = !testCase.requiredToolNames().isEmpty()
                || testCase.requiredAnswerTypeValue() != null
                || !testCase.requiredProductStateKinds().isEmpty()
                || !testCase.requiredProductStateSourceTools().isEmpty()
                || testCase.referenceRequired()
                || testCase.expectedResultStatusValue() != null;
        if (!hasRequirement) {
            failures.add("trace_case_has_no_requirements");
        }
        return failures;
    }

    private RagBenchmarkCase benchmarkCase(ProductReadingLaunchTraceCase testCase) {
        return new RagBenchmarkCase(
                testCase.id(),
                "Product Reading trace eval: " + testCase.id(),
                "zh",
                "PRODUCT_READING_TRACE_EVAL",
                "TRACE_ARTIFACT",
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

    private RagBenchmarkActual actual(ProductReadingLaunchTraceCase testCase,
                                      CaseEvaluation evaluation,
                                      int traceCount) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("traceCount", traceCount);
        diagnostics.put("requiredToolNames", testCase.requiredToolNames());
        diagnostics.put("requiredAnswerType", testCase.requiredAnswerTypeValue());
        diagnostics.put("requiredProductStateKinds", testCase.requiredProductStateKinds());
        diagnostics.put("requiredProductStateSourceTools", testCase.requiredProductStateSourceTools());
        diagnostics.put("requiresReference", testCase.referenceRequired());
        if (evaluation.trace() != null) {
            diagnostics.put("matchedTracePath", evaluation.trace().path().toString());
            diagnostics.put("matchedToolNames", List.copyOf(evaluation.trace().toolNames()));
            diagnostics.put("matchedProductStateSourceTools", List.copyOf(evaluation.trace().productStateSourceTools()));
            diagnostics.put("matchedReferenceCount", evaluation.trace().referenceCount());
        }
        String markdown = evaluation.passed()
                ? "Matched Product Reading trace for " + testCase.id()
                : String.join("; ", evaluation.failures());
        return new RagBenchmarkActual(ROUTE, markdown, Map.of(), diagnostics);
    }

    private RagBenchmarkVerdict verdict(ProductReadingLaunchTraceCase testCase,
                                        CaseEvaluation evaluation) {
        return new RagBenchmarkVerdict(
                testCase.id(),
                evaluation.passed(),
                evaluation.failures(),
                evaluation.failureClass()
        );
    }

    private Map<String, Double> metrics(List<RagBenchmarkVerdict> verdicts, int traceCount) {
        long passed = verdicts.stream().filter(RagBenchmarkVerdict::passed).count();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("matchedTraceCaseRate", verdicts.isEmpty() ? 0.0d : (double) passed / verdicts.size());
        metrics.put("traceCount", (double) traceCount);
        return metrics;
    }

    private String requirementSummary(ProductReadingLaunchTraceCase testCase) {
        return "tools=" + testCase.requiredToolNames()
                + ", answerType=" + testCase.requiredAnswerTypeValue()
                + ", productStateKinds=" + testCase.requiredProductStateKinds()
                + ", productStateSourceTools=" + testCase.requiredProductStateSourceTools()
                + ", requiresReference=" + testCase.referenceRequired()
                + ", resultStatus=" + testCase.expectedResultStatusValue();
    }

    private Set<String> values(JsonNode array, String fieldName) {
        Set<String> values = new LinkedHashSet<>();
        if (array == null || !array.isArray()) {
            return values;
        }
        for (JsonNode item : array) {
            String value = item.path(fieldName).asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private record TraceDocument(
            Path path,
            String resultStatus,
            String answerType,
            Set<String> toolNames,
            Set<String> productStateKinds,
            Set<String> productStateSourceTools,
            int referenceCount
    ) {
    }

    private record CaseEvaluation(
            TraceDocument trace,
            List<String> failures,
            List<String> failureClass
    ) {
        private CaseEvaluation {
            failures = failures == null ? List.of() : List.copyOf(failures);
            failureClass = failureClass == null ? List.of() : List.copyOf(failureClass);
        }

        boolean passed() {
            return failures.isEmpty();
        }
    }

    public record ProductReadingLaunchTraceCase(
            String id,
            List<String> requiredToolNames,
            String requiredAnswerType,
            List<String> requiredProductStateKinds,
            List<String> requiredProductStateSourceTools,
            Boolean requiresReference,
            String expectedResultStatus
    ) {
        public ProductReadingLaunchTraceCase {
            id = id == null ? "" : id.trim();
            requiredToolNames = safeList(requiredToolNames);
            requiredAnswerType = blankToNull(requiredAnswerType);
            requiredProductStateKinds = safeList(requiredProductStateKinds);
            requiredProductStateSourceTools = safeList(requiredProductStateSourceTools);
            expectedResultStatus = blankToNull(expectedResultStatus);
        }

        boolean referenceRequired() {
            return Boolean.TRUE.equals(requiresReference);
        }

        String requiredAnswerTypeValue() {
            return requiredAnswerType;
        }

        String expectedResultStatusValue() {
            return expectedResultStatus;
        }
    }

    public record Options(
            Path traceRoot,
            Path casesPath,
            Path runsRoot,
            String runId,
            String startedAt,
            String harnessId,
            String datasetId
    ) {
        public Options {
            traceRoot = traceRoot == null ? Path.of("data", "traces", "product-react") : traceRoot;
            casesPath = casesPath == null ? Path.of("eval/rag/product-reading-launch-trace-cases.jsonl") : casesPath;
            runsRoot = runsRoot == null ? Path.of("eval/rag/runs") : runsRoot;
            startedAt = blankToDefault(startedAt, Instant.now().toString());
            harnessId = blankToDefault(harnessId, "product-reading-launch-trace-eval");
            datasetId = blankToDefault(datasetId, "product-reading-launch-trace");
            runId = blankToDefault(runId, defaultRunId(startedAt, harnessId, datasetId));
        }

        static Options defaults() {
            return new Options(
                    Path.of("data/traces/product-react"),
                    Path.of("eval/rag/product-reading-launch-trace-cases.jsonl"),
                    Path.of("eval/rag/runs"),
                    null,
                    Instant.now().toString(),
                    "product-reading-launch-trace-eval",
                    "product-reading-launch-trace"
            );
        }

        private static String defaultRunId(String startedAt, String harnessId, String datasetId) {
            return startedAt
                    .replace(":", "")
                    .replace(".", "")
                    .replace("Z", "Z")
                    + "-" + harnessId + "-" + datasetId;
        }
    }

    private static List<String> safeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToDefault(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }
}
