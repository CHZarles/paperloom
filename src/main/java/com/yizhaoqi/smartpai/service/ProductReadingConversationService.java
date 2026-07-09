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
    private final ConversationService conversationService;

    public ProductReadingConversationService(ProductReadingReActHarness readingHarness) {
        this(readingHarness, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductReadingConversationService(ProductReadingReActHarness readingHarness,
                                             ConversationService conversationService) {
        this.readingHarness = readingHarness;
        this.conversationService = conversationService;
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
        if (readingHarness == null) {
            return failed("The paper-reading service is not ready.");
        }
        List<String> clickedSourceQuoteRefs = clickedSourceQuoteRefs(effectiveScope);
        List<String> clickedPaperHandles = clickedPaperHandles(effectiveScope);
        List<String> clickedLocationRefs = clickedLocationRefs(effectiveScope);
        String readingAction = readingAction(effectiveScope);
        Map<String, Object> latestReadingStatePatch = latestReadingStatePatch(userId, conversationId);
        return readingHarness.run(new ProductTurnRequest(
                userId,
                conversationId,
                generationId,
                userMessage,
                lockedScope,
                List.of(),
                readingMemory(clickedSourceQuoteRefs, clickedPaperHandles, clickedLocationRefs, readingAction, latestReadingStatePatch),
                modelContext,
                progressListener
        ));
    }

    private Map<String, Object> readingMemory(List<String> clickedSourceQuoteRefs,
                                              List<String> clickedPaperHandles,
                                              List<String> clickedLocationRefs,
                                              String readingAction,
                                              Map<String, Object> latestReadingStatePatch) {
        boolean hasSourceQuoteRefs = clickedSourceQuoteRefs != null && !clickedSourceQuoteRefs.isEmpty();
        boolean hasPaperHandles = clickedPaperHandles != null && !clickedPaperHandles.isEmpty();
        boolean hasLocationRefs = clickedLocationRefs != null && !clickedLocationRefs.isEmpty();
        boolean hasReadingAction = readingAction != null && !readingAction.isBlank();
        boolean hasLatestReadingStatePatch = latestReadingStatePatch != null && !latestReadingStatePatch.isEmpty();
        if (!hasSourceQuoteRefs && !hasPaperHandles && !hasLocationRefs && !hasReadingAction && !hasLatestReadingStatePatch) {
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
        return Map.copyOf(memory);
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
