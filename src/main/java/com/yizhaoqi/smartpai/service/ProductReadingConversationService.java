package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.config.ProductReadingReactProperties;
import org.springframework.stereotype.Service;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Service
public class ProductReadingConversationService {

    private static final int MAX_CLICKED_SOURCE_QUOTE_REFS = 20;
    private static final int MAX_CLICKED_PAPER_HANDLES = 20;
    private static final Set<String> READING_ACTIONS = Set.of("SEARCH_PAPERS", "FIND_LOCATIONS");
    private static final Pattern SOURCE_QUOTE_REF_PATTERN =
            Pattern.compile("^source_quote_[A-Za-z0-9_-]+$");
    private static final Pattern PAPER_HANDLE_PATTERN =
            Pattern.compile("^paper_handle_[A-Za-z0-9_-]+$");

    private final ProductReadingReActHarness readingHarness;
    private final ProductReadingReactProperties properties;

    public ProductReadingConversationService(ProductReadingReActHarness readingHarness,
                                             ProductReadingReactProperties properties) {
        this.readingHarness = readingHarness;
        this.properties = properties == null ? new ProductReadingReactProperties() : properties;
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
        if (!properties.isEnabled()) {
            return disabledResult();
        }
        if (readingHarness == null) {
            return failed("Product Reading ReAct Phase 1 harness is unavailable.");
        }
        List<String> clickedSourceQuoteRefs = clickedSourceQuoteRefs(effectiveScope);
        List<String> clickedPaperHandles = clickedPaperHandles(effectiveScope);
        String readingAction = readingAction(effectiveScope);
        return readingHarness.run(new ProductTurnRequest(
                userId,
                conversationId,
                generationId,
                userMessage,
                lockedScope,
                List.of(),
                readingMemory(clickedSourceQuoteRefs, clickedPaperHandles, readingAction),
                modelContext,
                progressListener
        ));
    }

    private Map<String, Object> readingMemory(List<String> clickedSourceQuoteRefs,
                                              List<String> clickedPaperHandles,
                                              String readingAction) {
        boolean hasSourceQuoteRefs = clickedSourceQuoteRefs != null && !clickedSourceQuoteRefs.isEmpty();
        boolean hasPaperHandles = clickedPaperHandles != null && !clickedPaperHandles.isEmpty();
        boolean hasReadingAction = readingAction != null && !readingAction.isBlank();
        if (!hasSourceQuoteRefs && !hasPaperHandles && !hasReadingAction) {
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
        if (!anchors.isEmpty()) {
            memory.put("readingTurnAnchors", anchors);
        }
        if (hasReadingAction) {
            memory.put("readingTurnAction", readingAction);
        }
        return Map.copyOf(memory);
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

    private ProductTurnResult disabledResult() {
        return failed("Product Reading ReAct Phase 1 is disabled.");
    }

    private ProductTurnResult failed(String message) {
        return new ProductTurnResult(
                message,
                new AnswerEnvelope(
                        AnswerType.CLARIFICATION_NEEDED,
                        message,
                        List.of(),
                        List.of(),
                        List.of(message),
                        List.of(),
                        List.of(),
                        ProductStopReason.ANSWER_SCHEMA_INVALID.name()
                ),
                List.of(),
                List.of(),
                ProductStopReason.ANSWER_SCHEMA_INVALID,
                ProductResultStatus.FAILED
        );
    }
}
