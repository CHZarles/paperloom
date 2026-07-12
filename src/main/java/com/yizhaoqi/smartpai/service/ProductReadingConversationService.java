package com.yizhaoqi.smartpai.service;

import org.springframework.stereotype.Service;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Service
public class ProductReadingConversationService {

    private static final int MAX_CLICKED_SOURCE_QUOTE_REFS = 20;
    private static final int MAX_CLICKED_PAPER_HANDLES = 20;
    private static final int MAX_CLICKED_LOCATION_REFS = 20;
    private static final Set<String> READING_ACTIONS = Set.of(
            "SEARCH_PAPERS",
            "LIST_LOCATIONS",
            "FIND_LOCATIONS",
            "READ_LOCATION",
            "TRACE_SOURCE_QUOTE"
    );
    private static final Pattern SOURCE_QUOTE_REF_PATTERN =
            Pattern.compile("^source_quote_[A-Za-z0-9_-]+$");
    private static final Pattern PAPER_HANDLE_PATTERN =
            Pattern.compile("^paper_handle_[A-Za-z0-9_-]+$");
    private static final Pattern LOCATION_REF_PATTERN =
            Pattern.compile("^(page_ref|section_ref|table_ref|figure_ref|location_ref)_[A-Za-z0-9_-]+$");

    private final ProductReadingReActHarness readingHarness;
    private final PythonResearchHarnessClient pythonHarnessClient;
    private final ConversationService conversationService;

    public ProductReadingConversationService(ProductReadingReActHarness readingHarness) {
        this(readingHarness, null, null);
    }

    public ProductReadingConversationService(ProductReadingReActHarness readingHarness,
                                             ConversationService conversationService) {
        this(readingHarness, null, conversationService);
    }

    private ProductReadingConversationService(ProductReadingReActHarness readingHarness,
                                              PythonResearchHarnessClient pythonHarnessClient,
                                              ConversationService conversationService) {
        this.readingHarness = readingHarness;
        this.pythonHarnessClient = pythonHarnessClient;
        this.conversationService = conversationService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductReadingConversationService(PythonResearchHarnessClient pythonHarnessClient,
                                             ConversationService conversationService) {
        this(null, pythonHarnessClient, conversationService);
    }

    public ProductTurnResult runTurn(Long userId,
                                     String conversationId,
                                     String generationId,
                                     String userMessage,
                                     SourceScope lockedScope,
                                     ProductModelContext modelContext) {
        return runTurn(userId, conversationId, generationId, userMessage, lockedScope, modelContext,
                (Consumer<ToolProgressEvent>) null);
    }

    public ProductTurnResult runTurn(Long userId,
                                     String conversationId,
                                     String generationId,
                                     String userMessage,
                                     SourceScope lockedScope,
                                     ProductModelContext modelContext,
                                     Map<String, Object> effectiveScope) {
        return runTurn(userId, conversationId, generationId, userMessage, lockedScope, modelContext, effectiveScope, null);
    }

    public ProductTurnResult runTurn(Long userId,
                                     String conversationId,
                                     String generationId,
                                     String userMessage,
                                     SourceScope lockedScope,
                                     ProductModelContext modelContext,
                                     Consumer<ToolProgressEvent> progressListener) {
        return runTurn(userId, conversationId, generationId, userMessage, lockedScope, modelContext, Map.of(), progressListener);
    }

    public ProductTurnResult runTurn(Long userId,
                                     String conversationId,
                                     String generationId,
                                     String userMessage,
                                     SourceScope lockedScope,
                                     ProductModelContext modelContext,
                                     Map<String, Object> effectiveScope,
        Consumer<ToolProgressEvent> progressListener) {
        if (pythonHarnessClient == null && readingHarness == null) {
            return failed("The paper-reading service is not ready.");
        }
        List<String> clickedSourceQuoteRefs = clickedSourceQuoteRefs(effectiveScope);
        List<String> clickedPaperHandles = clickedPaperHandles(effectiveScope);
        List<String> clickedLocationRefs = clickedLocationRefs(effectiveScope);
        String readingAction = readingAction(effectiveScope);
        Map<String, Object> latestReadingStatePatch = latestReadingStatePatch(userId, conversationId);
        ProductTurnRequest request = new ProductTurnRequest(
                userId,
                conversationId,
                generationId,
                userMessage,
                lockedScope,
                conversationHistory(userId, conversationId),
                readingMemory(
                        clickedSourceQuoteRefs,
                        clickedPaperHandles,
                        clickedLocationRefs,
                        readingAction,
                        latestReadingStatePatch,
                        latestResearchMemory(userId, conversationId)
                ),
                modelContext,
                progressListener
        );
        return pythonHarnessClient != null ? pythonHarnessClient.run(request) : readingHarness.run(request);
    }

    public CompletableFuture<ProductTurnResult> submitTurn(Long userId,
                                                           String conversationId,
                                                           String generationId,
                                                           String userMessage,
                                                           SourceScope lockedScope,
                                                           ProductModelContext modelContext,
                                                           Map<String, Object> effectiveScope,
                                                           Consumer<Map<String, Object>> progressListener) {
        if (pythonHarnessClient == null) {
            return CompletableFuture.supplyAsync(() -> runTurn(
                    userId, conversationId, generationId, userMessage, lockedScope, modelContext, effectiveScope, null));
        }
        List<String> clickedSourceQuoteRefs = clickedSourceQuoteRefs(effectiveScope);
        List<String> clickedPaperHandles = clickedPaperHandles(effectiveScope);
        List<String> clickedLocationRefs = clickedLocationRefs(effectiveScope);
        ProductTurnRequest request = new ProductTurnRequest(
                userId,
                conversationId,
                generationId,
                userMessage,
                lockedScope,
                conversationHistory(userId, conversationId),
                readingMemory(
                        clickedSourceQuoteRefs,
                        clickedPaperHandles,
                        clickedLocationRefs,
                        readingAction(effectiveScope),
                        latestReadingStatePatch(userId, conversationId),
                        latestResearchMemory(userId, conversationId)
                ),
                modelContext,
                null
        );
        return pythonHarnessClient.submit(request, progressListener);
    }

    public void cancelTurn(String generationId) {
        if (pythonHarnessClient != null) {
            pythonHarnessClient.cancel(generationId);
        }
    }

    private Map<String, Object> readingMemory(List<String> clickedSourceQuoteRefs,
                                              List<String> clickedPaperHandles,
                                              List<String> clickedLocationRefs,
                                              String readingAction,
                                              Map<String, Object> latestReadingStatePatch,
                                              Map<String, Object> latestResearchMemory) {
        boolean hasSourceQuoteRefs = clickedSourceQuoteRefs != null && !clickedSourceQuoteRefs.isEmpty();
        boolean hasPaperHandles = clickedPaperHandles != null && !clickedPaperHandles.isEmpty();
        boolean hasLocationRefs = clickedLocationRefs != null && !clickedLocationRefs.isEmpty();
        boolean hasReadingAction = readingAction != null && !readingAction.isBlank();
        boolean hasLatestReadingStatePatch = latestReadingStatePatch != null && !latestReadingStatePatch.isEmpty();
        boolean hasLatestResearchMemory = latestResearchMemory != null && !latestResearchMemory.isEmpty();
        if (!hasSourceQuoteRefs && !hasPaperHandles && !hasLocationRefs && !hasReadingAction
                && !hasLatestReadingStatePatch && !hasLatestResearchMemory) {
            return Map.of();
        }
        Map<String, Object> memory = new java.util.LinkedHashMap<>();
        Map<String, Object> anchors = new java.util.LinkedHashMap<>();
        if (hasSourceQuoteRefs) {
            anchors.put("clickedSourceQuoteRefs", clickedSourceQuoteRefs);
        }
        if (hasPaperHandles) {
            anchors.put("clickedPaperHandles", clickedPaperHandles);
        }
        if (hasLocationRefs) {
            anchors.put("clickedLocationRefs", clickedLocationRefs);
        }
        if (!anchors.isEmpty()) {
            memory.put("readingTurnAnchors", anchors);
        }
        if (hasReadingAction) {
            memory.put("readingTurnAction", readingAction);
        }
        if (hasLatestReadingStatePatch) {
            memory.put("readingStatePatch", new LinkedHashMap<>(latestReadingStatePatch));
        }
        if (hasLatestResearchMemory) {
            memory.putAll(latestResearchMemory);
        }
        return Map.copyOf(memory);
    }

    private List<Map<String, String>> conversationHistory(Long userId, String conversationId) {
        if (conversationService == null || userId == null || conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> messages = conversationService.getMessagesByConversationId(userId, conversationId);
        int from = Math.max(0, messages.size() - 20);
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, Object> message : messages.subList(from, messages.size())) {
            String role = String.valueOf(message.getOrDefault("role", "")).trim();
            String content = String.valueOf(message.getOrDefault("content", "")).trim();
            if (("user".equals(role) || "assistant".equals(role)) && !content.isBlank()) {
                result.add(Map.of("role", role, "content", content));
            }
        }
        return List.copyOf(result);
    }

    private Map<String, Object> latestResearchMemory(Long userId, String conversationId) {
        if (conversationService == null || userId == null || conversationId == null || conversationId.isBlank()) {
            return Map.of();
        }
        Optional<Map<String, Object>> latest = conversationService.findLatestReferenceFocus(userId, conversationId);
        if (latest.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<String> paperIds = new LinkedHashSet<>();
        LinkedHashSet<String> evidenceIds = new LinkedHashSet<>();
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Object raw : latest.get().values()) {
            if (!(raw instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> item.put(String.valueOf(key), value));
            String paperId = String.valueOf(item.getOrDefault("paperId", "")).trim();
            String evidenceId = String.valueOf(item.getOrDefault("evidenceRef", "")).trim();
            if (!paperId.isBlank()) {
                paperIds.add(paperId);
            }
            if (!evidenceId.startsWith("ev_")) {
                continue;
            }
            evidenceIds.add(evidenceId);
            evidence.add(Map.ofEntries(
                    Map.entry("evidence_id", evidenceId),
                    Map.entry("paper_id", paperId),
                    Map.entry("title", String.valueOf(item.getOrDefault("paperTitle", ""))),
                    Map.entry("section", String.valueOf(item.getOrDefault("sectionTitle", ""))),
                    Map.entry("page", String.valueOf(item.getOrDefault("pageNumber", "unknown"))),
                    Map.entry("location_ref", String.valueOf(item.getOrDefault("locationRef", ""))),
                    Map.entry("element_type", String.valueOf(item.getOrDefault("elementType", "paragraph"))),
                    Map.entry("span_text", String.valueOf(item.getOrDefault("matchedChunkText",
                            item.getOrDefault("evidenceSnippet", item.getOrDefault("anchorText", ""))))),
                    Map.entry("citeable", true)
            ));
        }
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("selected_paper_ids", List.copyOf(paperIds));
        memory.put("selected_evidence_ids", List.copyOf(evidenceIds));
        memory.put("previous_evidence", List.copyOf(evidence));
        return memory;
    }

    private Map<String, Object> latestReadingStatePatch(Long userId, String conversationId) {
        if (conversationService == null || userId == null || conversationId == null || conversationId.isBlank()) {
            return Map.of();
        }
        Optional<Map<String, Object>> patch = conversationService.findLatestReadingStatePatch(userId, conversationId);
        return patch.<Map<String, Object>>map(LinkedHashMap::new).orElseGet(Map::of);
    }

    private List<String> clickedSourceQuoteRefs(Map<String, Object> effectiveScope) {
        if (effectiveScope == null || !effectiveScope.containsKey("clickedSourceQuoteRefs")) {
            return List.of();
        }
        Object rawValue = effectiveScope.get("clickedSourceQuoteRefs");
        List<Object> rawRefs = rawRefValues(rawValue);
        if (rawRefs.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (Object rawRef : rawRefs) {
            String sourceQuoteRef = rawRef == null ? "" : String.valueOf(rawRef).trim();
            if (SOURCE_QUOTE_REF_PATTERN.matcher(sourceQuoteRef).matches()) {
                refs.add(sourceQuoteRef);
            }
            if (refs.size() >= MAX_CLICKED_SOURCE_QUOTE_REFS) {
                break;
            }
        }
        return List.copyOf(refs);
    }

    private List<String> clickedPaperHandles(Map<String, Object> effectiveScope) {
        if (effectiveScope == null || !effectiveScope.containsKey("clickedPaperHandles")) {
            return List.of();
        }
        Object rawValue = effectiveScope.get("clickedPaperHandles");
        List<Object> rawHandles = rawRefValues(rawValue);
        if (rawHandles.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> handles = new LinkedHashSet<>();
        for (Object rawHandle : rawHandles) {
            String paperHandle = rawHandle == null ? "" : String.valueOf(rawHandle).trim();
            if (PAPER_HANDLE_PATTERN.matcher(paperHandle).matches()) {
                handles.add(paperHandle);
            }
            if (handles.size() >= MAX_CLICKED_PAPER_HANDLES) {
                break;
            }
        }
        return List.copyOf(handles);
    }

    private List<String> clickedLocationRefs(Map<String, Object> effectiveScope) {
        if (effectiveScope == null || !effectiveScope.containsKey("clickedLocationRefs")) {
            return List.of();
        }
        Object rawValue = effectiveScope.get("clickedLocationRefs");
        List<Object> rawRefs = rawRefValues(rawValue);
        if (rawRefs.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (Object rawRef : rawRefs) {
            String locationRef = rawRef == null ? "" : String.valueOf(rawRef).trim();
            if (LOCATION_REF_PATTERN.matcher(locationRef).matches()) {
                refs.add(locationRef);
            }
            if (refs.size() >= MAX_CLICKED_LOCATION_REFS) {
                break;
            }
        }
        return List.copyOf(refs);
    }

    private String readingAction(Map<String, Object> effectiveScope) {
        if (effectiveScope == null || !effectiveScope.containsKey("readingAction")) {
            return null;
        }
        Object rawValue = effectiveScope.get("readingAction");
        if (rawValue == null) {
            return null;
        }
        String normalized = String.valueOf(rawValue).trim().toUpperCase(java.util.Locale.ROOT);
        return READING_ACTIONS.contains(normalized) ? normalized : null;
    }

    private List<Object> rawRefValues(Object rawValue) {
        if (rawValue instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (rawValue != null && rawValue.getClass().isArray()) {
            int length = Array.getLength(rawValue);
            List<Object> values = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                values.add(Array.get(rawValue, index));
            }
            return values;
        }
        return List.of();
    }

    private ProductTurnResult failed(String message) {
        String answer = """
                I understand your goal as: unresolved paper-reading goal.

                Short answer: I cannot produce a validated paper-reading answer because the reading service is not ready.

                Start here: no checkable reading target was observed.

                How to verify: no paper, location, or quote was validated in this turn.

                Not verified yet: the reading flow did not reach a validated observation.

                Next step: try the paper-reading action again after the reading service is ready.
                """;
        return new ProductTurnResult(
                answer,
                new AnswerEnvelope(
                        AnswerType.INSUFFICIENT_EVIDENCE,
                        answer,
                        List.of(),
                        List.of(),
                        List.of(message),
                        List.of("No structured reading artifacts were produced."),
                        List.of("validated_reading_service"),
                        ProductStopReason.ANSWER_SCHEMA_INVALID.name()
                ),
                List.of(),
                List.of(),
                ProductStopReason.ANSWER_SCHEMA_INVALID,
                ProductResultStatus.INCOMPLETE_PRECISE
        );
    }
}
