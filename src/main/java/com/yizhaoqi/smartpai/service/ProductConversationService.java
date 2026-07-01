package com.yizhaoqi.smartpai.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class ProductConversationService {

    private final ConversationService conversationService;
    private final ProductMemoryService memoryService;
    private final ProductReActHarness harness;
    private final ProductTraceRecorder traceRecorder;

    public ProductConversationService(ConversationService conversationService,
                                      ProductMemoryService memoryService,
                                      ProductReActHarness harness) {
        this(conversationService, memoryService, harness, null);
    }

    @Autowired
    public ProductConversationService(ConversationService conversationService,
                                      ProductMemoryService memoryService,
                                      ProductReActHarness harness,
                                      ProductTraceRecorder traceRecorder) {
        this.conversationService = conversationService;
        this.memoryService = memoryService;
        this.harness = harness;
        this.traceRecorder = traceRecorder;
    }

    public ProductTurnResult runTurn(Long userId,
                                     String conversationId,
                                     String generationId,
                                     String userMessage,
                                     SourceScope lockedScope,
                                     ProductModelContext modelContext) {
        return runTurn(userId, conversationId, generationId, userMessage, lockedScope, modelContext,
                effectiveScopeFromLockedScope(lockedScope));
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
                                     Map<String, Object> effectiveScope,
                                     Consumer<ToolProgressEvent> progressListener) {
        Map<String, Object> memory = memoryService.loadMemory(userId, conversationId);
        ProductTurnRequest request = new ProductTurnRequest(
                userId,
                conversationId,
                generationId,
                userMessage,
                lockedScope,
                history(userId, conversationId, modelContext),
                memory,
                modelContext,
                progressListener
        );
        ProductTurnResult result = harness.run(request);
        if (result.resultStatus() == ProductResultStatus.FAILED) {
            return result;
        }
        try {
            persistConversation(userId, conversationId, userMessage, result, effectiveScope);
        } catch (Exception exception) {
            return failedForConversationPersistence(result);
        }
        try {
            ProductMemoryService.MemoryUpdateResult memoryUpdate =
                    memoryService.updateMemory(userId, conversationId, memory, userMessage, result, lockedScope);
            if (!memoryUpdate.success()) {
                return degradedForMemoryFailure(result);
            }
            if (traceRecorder != null && !traceRecorder.recordMemoryUpdate(conversationId, generationId, memoryUpdate)) {
                return degradedForTraceFailure(result);
            }
            return result;
        } catch (Exception exception) {
            return degradedForMemoryFailure(result);
        }
    }

    private void persistConversation(Long userId,
                                     String conversationId,
                                     String userMessage,
                                     ProductTurnResult result,
                                     Map<String, Object> effectiveScope) {
        conversationService.recordConversation(
                userId,
                userMessage,
                result.finalAnswerMarkdown(),
                conversationId,
                referenceMappings(result.references()),
                effectiveScope == null ? Map.of() : effectiveScope
        );
    }

    private ProductTurnResult failedForConversationPersistence(ProductTurnResult result) {
        return new ProductTurnResult(
                "Product conversation persistence failed.",
                result.envelope(),
                result.references(),
                result.progressEvents(),
                ProductStopReason.REFERENCE_PERSISTENCE_FAILED,
                ProductResultStatus.FAILED
        );
    }

    private ProductTurnResult degradedForMemoryFailure(ProductTurnResult result) {
        return new ProductTurnResult(
                result.finalAnswerMarkdown(),
                result.envelope(),
                result.references(),
                result.progressEvents(),
                ProductStopReason.MEMORY_UPDATE_FAILED,
                ProductResultStatus.DEGRADED
        );
    }

    private ProductTurnResult degradedForTraceFailure(ProductTurnResult result) {
        return new ProductTurnResult(
                result.finalAnswerMarkdown(),
                result.envelope(),
                result.references(),
                result.progressEvents(),
                ProductStopReason.TRACE_WRITE_FAILED,
                ProductResultStatus.DEGRADED
        );
    }

    private Map<String, Map<String, Object>> referenceMappings(List<Map<String, Object>> references) {
        if (references == null || references.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> mappings = new LinkedHashMap<>();
        int fallbackNumber = 1;
        for (Map<String, Object> reference : references) {
            Integer referenceNumber = integerValue(reference.get("referenceNumber"));
            if (referenceNumber == null || referenceNumber <= 0) {
                referenceNumber = fallbackNumber;
            }
            fallbackNumber = Math.max(fallbackNumber + 1, referenceNumber + 1);
            mappings.put(String.valueOf(referenceNumber), new LinkedHashMap<>(reference));
        }
        return mappings;
    }

    private Map<String, Object> effectiveScopeFromLockedScope(SourceScope lockedScope) {
        SourceScope safeScope = lockedScope == null ? SourceScope.auto() : lockedScope;
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("scopeMode", safeScope.mode().name());
        scope.put("paperIds", safeScope.paperIds());
        scope.put("paperCount", safeScope.paperIds().size());
        return scope;
    }

    private List<Map<String, String>> history(Long userId, String conversationId, ProductModelContext modelContext) {
        List<Map<String, Object>> messages = conversationService.getMessagesByConversationId(userId, conversationId);
        List<Map<String, String>> history = new ArrayList<>();
        for (Map<String, Object> message : messages == null ? List.<Map<String, Object>>of() : messages) {
            String role = stringValue(message.get("role"));
            String content = "assistant".equals(role)
                    ? assistantHistoryContent(message)
                    : stringValue(message.get("content"));
            if (content.isBlank() || (!"user".equals(role) && !"assistant".equals(role))) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", role);
            item.put("content", content);
            history.add(item);
        }
        return fitHistoryToContext(history, modelContext);
    }

    private List<Map<String, String>> fitHistoryToContext(List<Map<String, String>> history,
                                                          ProductModelContext modelContext) {
        List<Map<String, String>> safeHistory = history == null ? List.of() : history;
        ProductModelContext safeContext = modelContext == null ? ProductModelContext.defaults() : modelContext;
        if (historyCharacters(safeHistory) <= safeContext.maxHistoryCharacters()) {
            return safeHistory;
        }
        int fromIndex = Math.max(0, safeHistory.size() - safeContext.recentHistoryMessages());
        List<Map<String, String>> recent = new ArrayList<>(safeHistory.subList(fromIndex, safeHistory.size()));
        while (!recent.isEmpty() && historyCharacters(recent) > safeContext.maxHistoryCharacters()) {
            recent.remove(0);
        }
        return recent;
    }

    private int historyCharacters(List<Map<String, String>> history) {
        int total = 0;
        for (Map<String, String> item : history == null ? List.<Map<String, String>>of() : history) {
            total += stringValue(item.get("role")).length();
            total += stringValue(item.get("content")).length();
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private String assistantHistoryContent(Map<String, Object> message) {
        String content = stringValue(message.get("content"));
        Object rawMappings = message.get("referenceMappings");
        if (!(rawMappings instanceof Map<?, ?> mappings) || mappings.isEmpty()) {
            return content;
        }
        List<String> referenceLines = new ArrayList<>();
        for (Map.Entry<?, ?> entry : mappings.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> rawDetail)) {
                continue;
            }
            Map<String, Object> detail = (Map<String, Object>) rawDetail;
            String citationRef = stringValue(detail.get("citationRef"));
            String evidenceRef = stringValue(detail.get("evidenceRef"));
            if (citationRef.isBlank() && evidenceRef.isBlank()) {
                continue;
            }
            String referenceNumber = stringValue(entry.getKey());
            String paperTitle = stringValue(detail.get("paperTitle"));
            String pageNumber = stringValue(detail.get("pageNumber"));
            StringBuilder line = new StringBuilder();
            line.append("[").append(referenceNumber).append("]");
            if (!citationRef.isBlank()) {
                line.append(" citationRef=").append(citationRef);
            }
            if (!evidenceRef.isBlank()) {
                line.append(" evidenceRef=").append(evidenceRef);
            }
            if (!paperTitle.isBlank()) {
                line.append(" paperTitle=").append(paperTitle);
            }
            if (!pageNumber.isBlank()) {
                line.append(" page=").append(pageNumber);
            }
            referenceLines.add(line.toString());
        }
        if (referenceLines.isEmpty()) {
            return content;
        }
        return content + "\n\nStructured reference registry mappings available to tools:\n"
                + String.join("\n", referenceLines);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
