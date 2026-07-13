package io.github.chzarles.paperloom.eval;

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
    private static final List<String> VISIBLE_INTERNAL_TOKENS = List.of(
            "paper_handle_",
            "page_ref_",
            "section_ref_",
            "location_ref_",
            "source_quote_",
            "paperHandle",
            "locationRef",
            "sourceQuoteRef",
            "parserQuality",
            "parserName",
            "parserVersion",
            "AUTO_SOURCE",
            "AUTO_LIBRARY",
            "SOURCE_SET_SNAPSHOT",
            "immutable=true",
            "Source Quote",
            "get_session_state",
            "list_papers",
            "search_paper_candidates",
            "find_papers_by_identity",
            "get_paper_outline",
            "list_paper_locations",
            "find_reading_locations",
            "read_locations",
            "trace_source_quotes"
    );

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
            JsonNode readingArtifacts = root.path("readingArtifacts");
            String finalAnswerMarkdown = root.path("finalAnswerMarkdown")
                    .asText(root.path("answerEnvelope").path("answer").asText(""));
            return new TraceDocument(
                    path,
                    root.path("input").path("userMessage").asText(""),
                    finalAnswerMarkdown,
                    root.path("resultStatus").asText(""),
                    root.path("answerEnvelope").path("answerType").asText(""),
                    values(root.path("toolCalls"), "toolName"),
                    values(root.path("productStateItems"), "kind"),
                    values(root.path("productStateItems"), "sourceTool"),
                    originalFilenames(root),
                    missingEvidence(readingArtifacts),
                    readingArtifactPanels(readingArtifacts),
                    paperShortlistItemCount(readingArtifacts),
                    root.path("references").isArray() ? root.path("references").size() : 0,
                    hasCanonicalResearchTrace(root.path("researchTrace")),
                    root.path("researchTrace").path("verificationPass").path("valid").asBoolean(false),
                    noviceReadable(finalAnswerMarkdown),
                    evidenceArtifactsComplete(root),
                    beginnerShortlistComplete(readingArtifacts)
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
        if (!containsAll(trace.inputUserMessage(), testCase.requiredInputContains())) {
            return false;
        }
        if (!containsAll(trace.finalAnswerMarkdown(), testCase.requiredAnswerContains())) {
            return false;
        }
        if (containsAny(trace.finalAnswerMarkdown(), testCase.forbiddenAnswerContains())) {
            return false;
        }
        if (!trace.originalFilenames().containsAll(testCase.requiredOriginalFilenames())) {
            return false;
        }
        if (!trace.missingEvidence().containsAll(testCase.requiredMissingEvidence())) {
            return false;
        }
        if (!trace.readingArtifactPanels().containsAll(testCase.requiredReadingArtifactPanels())) {
            return false;
        }
        if (testCase.referenceRequired() && trace.referenceCount() <= 0) {
            return false;
        }
        if (testCase.researchTraceRequired() && !trace.hasResearchTrace()) {
            return false;
        }
        if (testCase.verifiedResearchTraceRequired() && !trace.researchTraceVerified()) {
            return false;
        }
        if (testCase.noviceReadableAnswerRequired() && !trace.noviceReadable()) {
            return false;
        }
        if (testCase.artifactCompletenessRequired() && !trace.evidenceArtifactsComplete()) {
            return false;
        }
        return !testCase.beginnerShortlistRequired() || trace.beginnerShortlistComplete();
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
                || testCase.expectedResultStatusValue() != null
                || testCase.researchTraceRequired()
                || testCase.verifiedResearchTraceRequired()
                || !testCase.requiredInputContains().isEmpty()
                || !testCase.requiredAnswerContains().isEmpty()
                || !testCase.forbiddenAnswerContains().isEmpty()
                || !testCase.requiredOriginalFilenames().isEmpty()
                || !testCase.requiredMissingEvidence().isEmpty()
                || !testCase.requiredReadingArtifactPanels().isEmpty()
                || testCase.noviceReadableAnswerRequired()
                || testCase.artifactCompletenessRequired()
                || testCase.beginnerShortlistRequired();
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
        diagnostics.put("requiredInputContains", testCase.requiredInputContains());
        diagnostics.put("requiredAnswerContains", testCase.requiredAnswerContains());
        diagnostics.put("forbiddenAnswerContains", testCase.forbiddenAnswerContains());
        diagnostics.put("requiredOriginalFilenames", testCase.requiredOriginalFilenames());
        diagnostics.put("requiredMissingEvidence", testCase.requiredMissingEvidence());
        diagnostics.put("requiredReadingArtifactPanels", testCase.requiredReadingArtifactPanels());
        diagnostics.put("requiresReference", testCase.referenceRequired());
        diagnostics.put("requiresResearchTrace", testCase.researchTraceRequired());
        diagnostics.put("requiresVerifiedResearchTrace", testCase.verifiedResearchTraceRequired());
        diagnostics.put("requiresNoviceReadableAnswer", testCase.noviceReadableAnswerRequired());
        diagnostics.put("requiresArtifactCompleteness", testCase.artifactCompletenessRequired());
        diagnostics.put("requiresBeginnerShortlist", testCase.beginnerShortlistRequired());
        if (evaluation.trace() != null) {
            diagnostics.put("matchedTracePath", evaluation.trace().path().toString());
            diagnostics.put("matchedInputUserMessage", evaluation.trace().inputUserMessage());
            diagnostics.put("matchedToolNames", List.copyOf(evaluation.trace().toolNames()));
            diagnostics.put("matchedProductStateSourceTools", List.copyOf(evaluation.trace().productStateSourceTools()));
            diagnostics.put("matchedOriginalFilenames", List.copyOf(evaluation.trace().originalFilenames()));
            diagnostics.put("matchedMissingEvidence", List.copyOf(evaluation.trace().missingEvidence()));
            diagnostics.put("matchedReadingArtifactPanels", List.copyOf(evaluation.trace().readingArtifactPanels()));
            diagnostics.put("matchedPaperShortlistItemCount", evaluation.trace().paperShortlistItemCount());
            diagnostics.put("matchedReferenceCount", evaluation.trace().referenceCount());
            diagnostics.put("matchedResearchTrace", evaluation.trace().hasResearchTrace());
            diagnostics.put("matchedResearchTraceVerified", evaluation.trace().researchTraceVerified());
            diagnostics.put("matchedNoviceReadableAnswer", evaluation.trace().noviceReadable());
            diagnostics.put("matchedEvidenceArtifactsComplete", evaluation.trace().evidenceArtifactsComplete());
            diagnostics.put("matchedBeginnerShortlistComplete", evaluation.trace().beginnerShortlistComplete());
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
                + ", inputContains=" + testCase.requiredInputContains()
                + ", answerContains=" + testCase.requiredAnswerContains()
                + ", forbiddenAnswerContains=" + testCase.forbiddenAnswerContains()
                + ", originalFilenames=" + testCase.requiredOriginalFilenames()
                + ", missingEvidence=" + testCase.requiredMissingEvidence()
                + ", readingArtifactPanels=" + testCase.requiredReadingArtifactPanels()
                + ", requiresReference=" + testCase.referenceRequired()
                + ", resultStatus=" + testCase.expectedResultStatusValue()
                + ", requiresResearchTrace=" + testCase.researchTraceRequired()
                + ", requiresVerifiedResearchTrace=" + testCase.verifiedResearchTraceRequired()
                + ", requiresNoviceReadableAnswer=" + testCase.noviceReadableAnswerRequired()
                + ", requiresArtifactCompleteness=" + testCase.artifactCompletenessRequired()
                + ", requiresBeginnerShortlist=" + testCase.beginnerShortlistRequired();
    }

    private boolean hasCanonicalResearchTrace(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        return "research-harness-artifacts/v1".equals(node.path("schemaVersion").asText(""))
                && node.path("intentFrame").isObject()
                && node.path("retrievalPlan").isObject()
                && node.path("evidenceLedger").isObject()
                && node.path("claimGraph").isObject()
                && node.path("reasoningArtifacts").isArray()
                && node.path("verificationPass").isObject()
                && node.path("researchAnswer").isObject();
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

    private Set<String> originalFilenames(JsonNode root) {
        LinkedHashSet<String> filenames = new LinkedHashSet<>();
        filenames.addAll(values(root.path("productStateItems"), "originalFilename"));
        filenames.addAll(values(root.path("readingArtifacts").path("paperShortlist").path("items"), "originalFilename"));
        String selectedFilename = root.path("readingStatePatch").path("selectedPaper").path("originalFilename").asText("");
        if (!selectedFilename.isBlank()) {
            filenames.add(selectedFilename);
        }
        return Set.copyOf(filenames);
    }

    private Set<String> missingEvidence(JsonNode readingArtifacts) {
        return stringValues(readingArtifacts.path("missingEvidence").path("missing"));
    }

    private Set<String> stringValues(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        if (array == null || !array.isArray()) {
            return values;
        }
        for (JsonNode item : array) {
            String value = item.asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private Set<String> readingArtifactPanels(JsonNode readingArtifacts) {
        LinkedHashSet<String> panels = new LinkedHashSet<>();
        if (!readingArtifacts.path("goalCard").path("interpretedGoal").asText("").isBlank()) {
            panels.add("goalCard");
        }
        if (readingArtifacts.path("paperShortlist").path("items").isArray()
                && readingArtifacts.path("paperShortlist").path("items").size() > 0) {
            panels.add("paperShortlist");
        }
        if (readingArtifacts.path("readingPlan").path("steps").isArray()
                && readingArtifacts.path("readingPlan").path("steps").size() > 0) {
            panels.add("readingPlan");
        }
        if (readingArtifacts.path("claimEvidencePanel").path("rows").isArray()
                && readingArtifacts.path("claimEvidencePanel").path("rows").size() > 0) {
            panels.add("claimEvidencePanel");
        }
        if (readingArtifacts.path("missingEvidence").path("missing").isArray()
                && readingArtifacts.path("missingEvidence").path("missing").size() > 0) {
            panels.add("missingEvidence");
        }
        return Set.copyOf(panels);
    }

    private int paperShortlistItemCount(JsonNode readingArtifacts) {
        JsonNode items = readingArtifacts.path("paperShortlist").path("items");
        return items.isArray() ? items.size() : 0;
    }

    private boolean noviceReadable(String answer) {
        String safeAnswer = answer == null ? "" : answer;
        if (!safeAnswer.contains("I understand your goal as:")
                || !safeAnswer.contains("Short answer:")
                || !safeAnswer.contains("Start here:")
                || !safeAnswer.contains("How to verify:")
                || !safeAnswer.contains("Not verified yet:")
                || !safeAnswer.contains("Next step:")) {
            return false;
        }
        return !containsAny(safeAnswer, VISIBLE_INTERNAL_TOKENS);
    }

    private boolean evidenceArtifactsComplete(JsonNode root) {
        JsonNode references = root.path("references");
        JsonNode rows = root.path("readingArtifacts").path("claimEvidencePanel").path("rows");
        if (!references.isArray() || references.size() == 0 || !rows.isArray() || rows.size() == 0) {
            return false;
        }
        for (JsonNode reference : references) {
            boolean hasIdentity = !reference.path("sourceQuoteRef").asText("").isBlank()
                    && !reference.path("paperId").asText("").isBlank()
                    && !reference.path("paperVersion").asText("").isBlank()
                    && !reference.path("locationRef").asText("").isBlank()
                    && !reference.path("content").asText("").isBlank();
            boolean hasPageOrSection = reference.hasNonNull("pageNumber")
                    || !reference.path("sectionTitle").asText("").isBlank();
            if (!hasIdentity || !hasPageOrSection) {
                return false;
            }
        }
        for (JsonNode row : rows) {
            String sourceQuoteRef = row.path("sourceQuoteRef").asText("");
            boolean hasRowIdentity = !row.path("citationMarker").asText("").isBlank()
                    && !sourceQuoteRef.isBlank()
                    && !row.path("paperId").asText("").isBlank()
                    && !row.path("locationRef").asText("").isBlank()
                    && !row.path("quote").asText("").isBlank();
            if (!hasRowIdentity || !hasOpenSourceQuoteAction(row, sourceQuoteRef)) {
                return false;
            }
        }
        return missingEvidence(root.path("readingArtifacts")).contains("visual_pdf_page_evidence");
    }

    private boolean hasOpenSourceQuoteAction(JsonNode row, String sourceQuoteRef) {
        JsonNode actions = row.path("actions");
        if (!actions.isArray()) {
            return false;
        }
        for (JsonNode action : actions) {
            if ("OPEN_SOURCE_QUOTE".equals(action.path("action").asText(""))
                    && sourceQuoteRef.equals(action.path("payload").path("sourceQuoteRef").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private boolean beginnerShortlistComplete(JsonNode readingArtifacts) {
        JsonNode items = readingArtifacts.path("paperShortlist").path("items");
        if (!items.isArray() || items.size() < 3 || items.size() > 5) {
            return false;
        }
        for (JsonNode item : items) {
            String role = item.path("role").asText("").trim();
            String roleEvidenceSource = item.path("roleEvidenceSource").asText("").trim();
            String evidenceStatus = item.path("evidenceStatus").asText("").trim();
            String roleEvidenceStatus = item.path("roleEvidenceStatus").asText("").trim();
            if (role.isBlank()
                    || roleEvidenceSource.isBlank()
                    || "missing_role_metadata".equals(roleEvidenceSource)
                    || "unmapped_role_metadata".equals(roleEvidenceSource)
                    || (evidenceStatus.isBlank() && roleEvidenceStatus.isBlank())) {
                return false;
            }
        }
        return true;
    }

    private boolean containsAll(String value, List<String> requiredFragments) {
        String text = value == null ? "" : value;
        for (String fragment : requiredFragments == null ? List.<String>of() : requiredFragments) {
            if (!text.contains(fragment)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsAny(String value, List<String> forbiddenFragments) {
        String text = value == null ? "" : value;
        for (String fragment : forbiddenFragments == null ? List.<String>of() : forbiddenFragments) {
            if (!fragment.isBlank() && text.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private record TraceDocument(
            Path path,
            String inputUserMessage,
            String finalAnswerMarkdown,
            String resultStatus,
            String answerType,
            Set<String> toolNames,
            Set<String> productStateKinds,
            Set<String> productStateSourceTools,
            Set<String> originalFilenames,
            Set<String> missingEvidence,
            Set<String> readingArtifactPanels,
            int paperShortlistItemCount,
            int referenceCount,
            boolean hasResearchTrace,
            boolean researchTraceVerified,
            boolean noviceReadable,
            boolean evidenceArtifactsComplete,
            boolean beginnerShortlistComplete
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
            List<String> requiredInputContains,
            List<String> requiredAnswerContains,
            List<String> forbiddenAnswerContains,
            List<String> requiredOriginalFilenames,
            List<String> requiredMissingEvidence,
            List<String> requiredReadingArtifactPanels,
            Boolean requiresReference,
            String expectedResultStatus,
            Boolean requiresResearchTrace,
            Boolean requiresVerifiedResearchTrace,
            Boolean requiresNoviceReadableAnswer,
            Boolean requiresArtifactCompleteness,
            Boolean requiresBeginnerShortlist
    ) {
        public ProductReadingLaunchTraceCase {
            id = id == null ? "" : id.trim();
            requiredToolNames = safeList(requiredToolNames);
            requiredAnswerType = blankToNull(requiredAnswerType);
            requiredProductStateKinds = safeList(requiredProductStateKinds);
            requiredProductStateSourceTools = safeList(requiredProductStateSourceTools);
            requiredInputContains = safeList(requiredInputContains);
            requiredAnswerContains = safeList(requiredAnswerContains);
            forbiddenAnswerContains = safeList(forbiddenAnswerContains);
            requiredOriginalFilenames = safeList(requiredOriginalFilenames);
            requiredMissingEvidence = safeList(requiredMissingEvidence);
            requiredReadingArtifactPanels = safeList(requiredReadingArtifactPanels);
            expectedResultStatus = blankToNull(expectedResultStatus);
        }

        boolean referenceRequired() {
            return Boolean.TRUE.equals(requiresReference);
        }

        boolean researchTraceRequired() {
            return Boolean.TRUE.equals(requiresResearchTrace);
        }

        boolean verifiedResearchTraceRequired() {
            return Boolean.TRUE.equals(requiresVerifiedResearchTrace);
        }

        boolean noviceReadableAnswerRequired() {
            return Boolean.TRUE.equals(requiresNoviceReadableAnswer);
        }

        boolean artifactCompletenessRequired() {
            return Boolean.TRUE.equals(requiresArtifactCompleteness);
        }

        boolean beginnerShortlistRequired() {
            return Boolean.TRUE.equals(requiresBeginnerShortlist);
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
