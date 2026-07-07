package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.ChatHandler;

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

public class ProductReadingLiveLaunchSmokeRunner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ROUTE = "PRODUCT_READING_LIVE_LAUNCH_SMOKE";
    private static final Set<String> PAPER_CHOICE_SOURCE_TOOLS = Set.of(
            "list_papers",
            "search_paper_candidates",
            "find_papers_by_identity"
    );

    private final LiveReadingChatClient client;

    public ProductReadingLiveLaunchSmokeRunner(LiveReadingChatClient client) {
        this.client = client;
    }

    public Path run(Options options) throws IOException {
        if (client == null) {
            throw new IllegalStateException("LiveReadingChatClient is required");
        }
        Options safeOptions = options == null ? Options.defaults() : options;
        List<ProductReadingLiveLaunchSmokeCase> cases = loadCases(safeOptions.casesPath());
        LaunchState state = new LaunchState();
        List<CaseResult> results = new ArrayList<>();
        for (ProductReadingLiveLaunchSmokeCase testCase : cases) {
            CaseResult result = runCase(testCase, safeOptions.conversationId(), state);
            results.add(result);
            state.remember(testCase.id(), result.response());
        }
        return RagEvalRunWriter.write(
                safeOptions.runsRoot(),
                safeOptions.runId(),
                safeOptions.startedAt(),
                safeOptions.harnessId(),
                safeOptions.datasetId(),
                safeOptions.casesPath().toString(),
                new RagBenchmarkRun(
                        results.stream().map(this::benchmarkCase).toList(),
                        results.stream().map(this::actual).toList(),
                        results.stream().map(this::verdict).toList()
                ),
                metrics(results)
        );
    }

    static List<ProductReadingLiveLaunchSmokeCase> loadCases(Path path) throws IOException {
        List<ProductReadingLiveLaunchSmokeCase> cases = new ArrayList<>();
        for (String rawLine : Files.readAllLines(path)) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            cases.add(OBJECT_MAPPER.readValue(line, ProductReadingLiveLaunchSmokeCase.class));
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("Live launch smoke cases file is empty: " + path);
        }
        return List.copyOf(cases);
    }

    private CaseResult runCase(ProductReadingLiveLaunchSmokeCase testCase,
                               String conversationId,
                               LaunchState state) {
        List<String> failures = new ArrayList<>();
        LinkedHashSet<String> failureClass = new LinkedHashSet<>();
        Map<String, Object> referenceFocus = referenceFocus(testCase, state, failures, failureClass);
        if (!failures.isEmpty()) {
            return new CaseResult(testCase, null, false, failures, List.copyOf(failureClass), diagnostics(
                    testCase,
                    referenceFocus,
                    null
            ));
        }

        LiveReadingChatResponse response;
        try {
            response = client.ask(new LiveReadingChatRequest(
                    testCase.id(),
                    testCase.message(),
                    conversationId,
                    referenceFocus
            ));
        } catch (Exception exception) {
            failures.add("live_chat_failed(" + exception.getClass().getSimpleName() + ": " + exception.getMessage() + ")");
            failureClass.add("LIVE_CHAT_FAILED");
            return new CaseResult(testCase, null, false, failures, List.copyOf(failureClass), diagnostics(
                    testCase,
                    referenceFocus,
                    null
            ));
        }

        evaluateResponse(testCase, response, failures, failureClass);
        return new CaseResult(
                testCase,
                response,
                failures.isEmpty(),
                List.copyOf(failures),
                List.copyOf(failureClass),
                diagnostics(testCase, referenceFocus, response)
        );
    }

    private Map<String, Object> referenceFocus(ProductReadingLiveLaunchSmokeCase testCase,
                                               LaunchState state,
                                               List<String> failures,
                                               LinkedHashSet<String> failureClass) {
        Map<String, Object> referenceFocus = new LinkedHashMap<>();
        if (testCase.focusPaperHandleFromCaseValue() != null) {
            String paperHandle = state.paperHandleByCase.get(testCase.focusPaperHandleFromCaseValue());
            if (paperHandle == null) {
                failures.add("paper_handle_anchor_missing(fromCase=" + testCase.focusPaperHandleFromCaseValue() + ")");
                failureClass.add("ANCHOR_MISSING");
            } else {
                referenceFocus.put("paperHandle", paperHandle);
            }
        }
        if (testCase.focusSourceQuoteRefFromCaseValue() != null) {
            String sourceQuoteRef = state.sourceQuoteRefByCase.get(testCase.focusSourceQuoteRefFromCaseValue());
            if (sourceQuoteRef == null) {
                failures.add("source_quote_ref_anchor_missing(fromCase=" + testCase.focusSourceQuoteRefFromCaseValue() + ")");
                failureClass.add("ANCHOR_MISSING");
            } else {
                referenceFocus.put("sourceQuoteRef", sourceQuoteRef);
            }
        }
        return Map.copyOf(referenceFocus);
    }

    private void evaluateResponse(ProductReadingLiveLaunchSmokeCase testCase,
                                  LiveReadingChatResponse response,
                                  List<String> failures,
                                  LinkedHashSet<String> failureClass) {
        LiveReadingChatResponse safeResponse = response == null
                ? LiveReadingChatResponse.empty()
                : response;
        Set<String> toolNames = new LinkedHashSet<>(safeResponse.toolNames());
        List<Map<String, Object>> validProductStateItems = validProductStateItems(safeResponse.productStateItems());
        for (String requiredToolName : testCase.requiredToolNames()) {
            if (!toolNames.contains(requiredToolName)) {
                failures.add("required_tool_missing(" + requiredToolName + ")");
                failureClass.add("TOOL_EVENT_MISSING");
            }
        }
        if (testCase.productStateItemRequired() && validProductStateItems.isEmpty()) {
            failures.add("product_state_item_missing");
            failureClass.add("PRODUCT_STATE_ITEM_MISSING");
        }
        Set<String> productStateSourceTools = productStateSourceTools(validProductStateItems);
        for (String requiredSourceTool : testCase.requiredProductStateSourceTools()) {
            if (!productStateSourceTools.contains(requiredSourceTool)) {
                failures.add("product_state_source_tool_missing(" + requiredSourceTool + ")");
                failureClass.add("PRODUCT_STATE_ITEM_MISSING");
            }
        }
        if (testCase.referenceRequired() && sourceQuoteRefs(safeResponse.referenceMappings()).isEmpty()) {
            failures.add("source_quote_reference_missing");
            failureClass.add("REFERENCE_MISSING");
        }
    }

    private Map<String, Object> diagnostics(ProductReadingLiveLaunchSmokeCase testCase,
                                            Map<String, Object> referenceFocus,
                                            LiveReadingChatResponse response) {
        LiveReadingChatResponse safeResponse = response == null
                ? LiveReadingChatResponse.empty()
                : response;
        List<Map<String, Object>> validProductStateItems = validProductStateItems(safeResponse.productStateItems());
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("caseId", testCase.id());
        diagnostics.put("referenceFocus", referenceFocus == null ? Map.of() : referenceFocus);
        diagnostics.put("requiredToolNames", testCase.requiredToolNames());
        diagnostics.put("observedToolNames", safeResponse.toolNames());
        diagnostics.put("requiredProductStateSourceTools", testCase.requiredProductStateSourceTools());
        diagnostics.put("observedProductStateSourceTools", List.copyOf(productStateSourceTools(validProductStateItems)));
        diagnostics.put("productStateItemCount", validProductStateItems.size());
        diagnostics.put("rawProductStateItemCount", safeResponse.productStateItems().size());
        diagnostics.put("sourceQuoteRefs", sourceQuoteRefs(safeResponse.referenceMappings()));
        diagnostics.put("completionDiagnostics", safeResponse.diagnostics());
        return diagnostics;
    }

    private RagBenchmarkCase benchmarkCase(CaseResult result) {
        ProductReadingLiveLaunchSmokeCase testCase = result.testCase();
        return new RagBenchmarkCase(
                testCase.id(),
                testCase.message(),
                "zh",
                "PRODUCT_READING_LIVE_LAUNCH",
                "LIVE_CHAT",
                new RagBenchmarkCase.Scope(List.of(), List.of()),
                ROUTE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                testCase.referenceRequired()
        );
    }

    private RagBenchmarkActual actual(CaseResult result) {
        LiveReadingChatResponse response = result.response() == null
                ? LiveReadingChatResponse.empty()
                : result.response();
        return new RagBenchmarkActual(
                ROUTE,
                response.markdown(),
                response.referenceMappings(),
                result.diagnostics()
        );
    }

    private RagBenchmarkVerdict verdict(CaseResult result) {
        return new RagBenchmarkVerdict(
                result.testCase().id(),
                result.passed(),
                result.failures(),
                result.failureClass()
        );
    }

    private Map<String, Double> metrics(List<CaseResult> results) {
        long passed = results.stream().filter(CaseResult::passed).count();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("liveLaunchSmokePassRate", fraction(passed, results.size()));
        metrics.put("liveLaunchSmokeCaseCount", (double) results.size());
        metrics.put("liveLaunchSmokePassedCount", (double) passed);
        return metrics;
    }

    private Set<String> productStateSourceTools(List<Map<String, Object>> productStateItems) {
        LinkedHashSet<String> sourceTools = new LinkedHashSet<>();
        for (Map<String, Object> item : productStateItems == null ? List.<Map<String, Object>>of() : productStateItems) {
            String sourceTool = stringValue(item.get("sourceTool"));
            if (sourceTool != null) {
                sourceTools.add(sourceTool);
            }
        }
        return sourceTools;
    }

    private static List<Map<String, Object>> validProductStateItems(List<Map<String, Object>> productStateItems) {
        if (productStateItems == null || productStateItems.isEmpty()) {
            return List.of();
        }
        return productStateItems.stream()
                .filter(ProductReadingLiveLaunchSmokeRunner::validPaperChoiceItem)
                .toList();
    }

    private static boolean validPaperChoiceItem(Map<String, Object> item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        String kind = stringValue(item.get("kind"));
        String sourceTool = stringValue(item.get("sourceTool"));
        String paperHandle = stringValue(item.get("paperHandle"));
        return "READING_PAPER_CHOICE".equals(kind)
                && PAPER_CHOICE_SOURCE_TOOLS.contains(sourceTool)
                && paperHandle != null
                && paperHandle.startsWith("paper_handle_");
    }

    private List<String> sourceQuoteRefs(Map<Integer, ChatHandler.ReferenceInfo> referenceMappings) {
        if (referenceMappings == null || referenceMappings.isEmpty()) {
            return List.of();
        }
        return referenceMappings.values().stream()
                .map(ChatHandler.ReferenceInfo::sourceQuoteRef)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private static String firstPaperHandle(List<Map<String, Object>> productStateItems) {
        if (productStateItems == null) {
            return null;
        }
        for (Map<String, Object> item : validProductStateItems(productStateItems)) {
            if (item == null) {
                continue;
            }
            String paperHandle = stringValue(item.get("paperHandle"));
            if (paperHandle != null && paperHandle.startsWith("paper_handle_")) {
                return paperHandle;
            }
        }
        return null;
    }

    private static String firstSourceQuoteRef(Map<Integer, ChatHandler.ReferenceInfo> referenceMappings) {
        if (referenceMappings == null) {
            return null;
        }
        for (ChatHandler.ReferenceInfo referenceInfo : referenceMappings.values()) {
            if (referenceInfo == null) {
                continue;
            }
            String sourceQuoteRef = referenceInfo.sourceQuoteRef();
            if (sourceQuoteRef != null && sourceQuoteRef.startsWith("source_quote_")) {
                return sourceQuoteRef;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private double fraction(double numerator, double denominator) {
        return denominator == 0.0d ? 0.0d : numerator / denominator;
    }

    private static String blankToDefault(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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

    private record CaseResult(
            ProductReadingLiveLaunchSmokeCase testCase,
            LiveReadingChatResponse response,
            boolean passed,
            List<String> failures,
            List<String> failureClass,
            Map<String, Object> diagnostics
    ) {
        private CaseResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
            failureClass = failureClass == null ? List.of() : List.copyOf(failureClass);
            diagnostics = diagnostics == null ? Map.of() : new LinkedHashMap<>(diagnostics);
        }
    }

    private static final class LaunchState {
        private final Map<String, String> paperHandleByCase = new LinkedHashMap<>();
        private final Map<String, String> sourceQuoteRefByCase = new LinkedHashMap<>();

        private void remember(String caseId, LiveReadingChatResponse response) {
            if (caseId == null || response == null) {
                return;
            }
            String paperHandle = firstPaperHandle(response.productStateItems());
            if (paperHandle != null) {
                paperHandleByCase.put(caseId, paperHandle);
            }
            String sourceQuoteRef = firstSourceQuoteRef(response.referenceMappings());
            if (sourceQuoteRef != null) {
                sourceQuoteRefByCase.put(caseId, sourceQuoteRef);
            }
        }
    }

    public interface LiveReadingChatClient {
        LiveReadingChatResponse ask(LiveReadingChatRequest request);
    }

    public record LiveReadingChatRequest(
            String caseId,
            String message,
            String conversationId,
            Map<String, Object> referenceFocus
    ) {
        public LiveReadingChatRequest {
            caseId = blankToDefault(caseId, "live_reading_case");
            message = blankToDefault(message, "");
            conversationId = blankToDefault(conversationId, "");
            referenceFocus = referenceFocus == null ? Map.of() : Map.copyOf(referenceFocus);
        }
    }

    public record LiveReadingChatResponse(
            String markdown,
            Map<Integer, ChatHandler.ReferenceInfo> referenceMappings,
            Map<String, Object> diagnostics,
            List<Map<String, Object>> productStateItems,
            List<String> toolNames
    ) {
        public LiveReadingChatResponse {
            markdown = markdown == null ? "" : markdown;
            referenceMappings = referenceMappings == null ? Map.of() : Map.copyOf(referenceMappings);
            diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
            productStateItems = copyProductStateItems(productStateItems);
            toolNames = safeList(toolNames);
        }

        static LiveReadingChatResponse empty() {
            return new LiveReadingChatResponse("", Map.of(), Map.of(), List.of(), List.of());
        }

        private static List<Map<String, Object>> copyProductStateItems(List<Map<String, Object>> items) {
            if (items == null || items.isEmpty()) {
                return List.of();
            }
            return items.stream()
                    .filter(item -> item != null && !item.isEmpty())
                    .map(LinkedHashMap::new)
                    .map(Map::<String, Object>copyOf)
                    .toList();
        }
    }

    public record ProductReadingLiveLaunchSmokeCase(
            String id,
            String message,
            List<String> requiredToolNames,
            Boolean requiresProductStateItem,
            List<String> requiredProductStateSourceTools,
            Boolean requiresReference,
            String focusPaperHandleFromCase,
            String focusSourceQuoteRefFromCase
    ) {
        public ProductReadingLiveLaunchSmokeCase {
            id = blankToDefault(id, "live_reading_case");
            message = blankToDefault(message, "");
            requiredToolNames = safeList(requiredToolNames);
            requiredProductStateSourceTools = safeList(requiredProductStateSourceTools);
            focusPaperHandleFromCase = blankToNull(focusPaperHandleFromCase);
            focusSourceQuoteRefFromCase = blankToNull(focusSourceQuoteRefFromCase);
        }

        boolean productStateItemRequired() {
            return Boolean.TRUE.equals(requiresProductStateItem);
        }

        boolean referenceRequired() {
            return Boolean.TRUE.equals(requiresReference);
        }

        String focusPaperHandleFromCaseValue() {
            return focusPaperHandleFromCase;
        }

        String focusSourceQuoteRefFromCaseValue() {
            return focusSourceQuoteRefFromCase;
        }
    }

    public record Options(
            Path casesPath,
            Path runsRoot,
            String runId,
            String startedAt,
            String harnessId,
            String datasetId,
            String conversationId
    ) {
        public Options {
            casesPath = casesPath == null
                    ? Path.of("eval/rag/product-reading-live-launch-smoke-cases.jsonl")
                    : casesPath;
            runsRoot = runsRoot == null ? Path.of("eval/rag/runs") : runsRoot;
            startedAt = blankToDefault(startedAt, Instant.now().toString());
            harnessId = blankToDefault(harnessId, "product-reading-live-launch-smoke");
            datasetId = blankToDefault(datasetId, "product-reading-live-launch-smoke");
            runId = blankToDefault(runId, defaultRunId(startedAt, harnessId, datasetId));
            conversationId = blankToDefault(conversationId, "");
        }

        static Options defaults() {
            return new Options(
                    Path.of("eval/rag/product-reading-live-launch-smoke-cases.jsonl"),
                    Path.of("eval/rag/runs"),
                    null,
                    Instant.now().toString(),
                    "product-reading-live-launch-smoke",
                    "product-reading-live-launch-smoke",
                    ""
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
}
