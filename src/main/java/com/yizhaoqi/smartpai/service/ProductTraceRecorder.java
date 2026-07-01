package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ProductTraceRecorder {

    private final ObjectMapper objectMapper;
    private final Path traceRoot;

    @Autowired
    public ProductTraceRecorder(ObjectMapper objectMapper) {
        this(objectMapper, Path.of("data", "traces", "product-react"));
    }

    public ProductTraceRecorder(ObjectMapper objectMapper, Path traceRoot) {
        this.objectMapper = objectMapper;
        this.traceRoot = traceRoot == null ? Path.of("data", "traces", "product-react") : traceRoot;
    }

    public boolean record(ProductTurnRequest request,
                          ProductTurnResult result,
                          List<Map<String, Object>> llmCalls,
                          List<Map<String, Object>> toolCalls,
                          Instant startedAt,
                          Instant finishedAt) {
        try {
            ProductTurnRequest safeRequest = request == null
                    ? new ProductTurnRequest(null, "", "", "", SourceScope.auto(), List.of(), Map.of(), ProductModelContext.defaults())
                    : request;
            ProductTurnResult safeResult = result == null
                    ? new ProductTurnResult("", null, List.of(), List.of(), ProductStopReason.COMPLETED, ProductResultStatus.COMPLETED)
                    : result;
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("traceVersion", 1);
            trace.put("conversationId", safeRequest.conversationId());
            trace.put("generationId", safeRequest.generationId());
            trace.put("userId", safeRequest.userId());
            trace.put("scopeSnapshot", scopeSnapshot(safeRequest.lockedScope()));
            trace.put("startedAt", startedAt == null ? null : startedAt.toString());
            trace.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
            trace.put("input", Map.of(
                    "userMessage", safeRequest.userMessage(),
                    "historyMessageCount", safeRequest.history().size(),
                    "memory", safeRequest.memory()
            ));
            trace.put("llmCalls", llmCalls == null ? List.of() : llmCalls);
            trace.put("toolCalls", toolCalls == null ? List.of() : toolCalls);
            trace.put("retrievals", List.of());
            trace.put("diagnostics", diagnostics(safeRequest.memory(), toolCalls, safeResult));
            trace.put("answerEnvelope", safeResult.envelope());
            trace.put("references", safeResult.references());
            trace.put("stopReason", safeResult.stopReason().name());
            trace.put("resultStatus", safeResult.resultStatus().name());
            trace.put("errors", List.of());

            Path directory = traceRoot.resolve("conversation-" + safeSegment(safeRequest.conversationId()));
            Files.createDirectories(directory);
            Path target = directory.resolve("turn-" + safeSegment(safeRequest.generationId()) + ".json");
            Path temp = directory.resolve(target.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), trace);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    public boolean recordMemoryUpdate(String conversationId,
                                      String generationId,
                                      ProductMemoryService.MemoryUpdateResult memoryUpdate) {
        try {
            Path directory = traceRoot.resolve("conversation-" + safeSegment(conversationId));
            Path target = directory.resolve("turn-" + safeSegment(generationId) + ".json");
            if (!Files.exists(target)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> trace = objectMapper.readValue(target.toFile(), LinkedHashMap.class);
            List<Object> memoryCalls = new ArrayList<>();
            Object existing = trace.get("memoryCompressionCalls");
            if (existing instanceof List<?> list) {
                memoryCalls.addAll(list);
            }
            ProductMemoryService.MemoryUpdateResult safeUpdate = memoryUpdate == null
                    ? new ProductMemoryService.MemoryUpdateResult(false, Map.of(), Map.of(), "missing_memory_update")
                    : memoryUpdate;
            Map<String, Object> call = new LinkedHashMap<>(safeUpdate.trace());
            call.put("success", safeUpdate.success());
            call.put("error", safeUpdate.error());
            call.put("memory", safeUpdate.memory());
            memoryCalls.add(call);
            trace.put("memoryCompressionCalls", memoryCalls);
            trace.put("diagnostics", mergeDiagnostics(
                    trace.get("diagnostics"),
                    diagnostics(safeUpdate.memory(), List.of(), null)
            ));
            Path temp = directory.resolve(target.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), trace);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private Map<String, Object> scopeSnapshot(SourceScope scope) {
        SourceScope safeScope = scope == null ? SourceScope.auto() : scope;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("scopeMode", safeScope.mode().name());
        snapshot.put("paperCount", safeScope.paperIds().size());
        snapshot.put("immutable", true);
        return snapshot;
    }

    private Map<String, Object> diagnostics(Map<String, Object> memory,
                                            List<Map<String, Object>> toolCalls,
                                            ProductTurnResult result) {
        List<Map<String, Object>> events = new ArrayList<>();
        collectMemoryIdentityDiagnostics(memory, "$.input.memory", events);
        List<Map<String, Object>> safeToolCalls = toolCalls == null ? List.of() : toolCalls;
        for (int index = 0; index < safeToolCalls.size(); index++) {
            Map<String, Object> toolCall = safeToolCalls.get(index);
            if (toolCall == null) {
                continue;
            }
            String toolName = stringValue(toolCall.get("toolName"));
            Object arguments = toolCall.get("argumentsJson");
            collectToolArgumentPaperRefDiagnostics(arguments,
                    "$.toolCalls[" + index + "].argumentsJson", toolName, events);
            Object resultJson = toolCall.get("resultJson");
            if (Boolean.TRUE.equals(toolCall.get("rejected"))) {
                events.add(event(
                        "tool_call_rejected",
                        toolName,
                        "$.toolCalls[" + index + "]",
                        Map.of("result", resultJson == null ? Map.of() : resultJson)
                ));
            }
            if (resultJson instanceof Map<?, ?> resultMap
                    && "unresolved_paper_constraints".equals(stringValue(resultMap.get("reason")))) {
                Object missingPaperRefs = resultMap.containsKey("missingPaperRefs")
                        ? resultMap.get("missingPaperRefs")
                        : List.of();
                events.add(event(
                        "tool_result_unresolved_paper_ref",
                        toolName,
                        "$.toolCalls[" + index + "].resultJson",
                        Map.of("missingPaperRefs", missingPaperRefs == null ? List.of() : missingPaperRefs)
                ));
            }
        }
        if (result != null && result.stopReason() != null && result.resultStatus() != null) {
            events.add(event(
                    "turn_result",
                    "",
                    "$.resultStatus",
                    Map.of("stopReason", result.stopReason().name(), "resultStatus", result.resultStatus().name())
            ));
        }
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("eventCount", events.size());
        diagnostics.put("events", events);
        return diagnostics;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeDiagnostics(Object existing, Map<String, Object> additional) {
        List<Object> events = new ArrayList<>();
        if (existing instanceof Map<?, ?> map && map.get("events") instanceof List<?> list) {
            events.addAll(list);
        }
        if (additional != null && additional.get("events") instanceof List<?> list) {
            events.addAll(list);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("eventCount", events.size());
        merged.put("events", events);
        return merged;
    }

    private void collectMemoryIdentityDiagnostics(Object value, String path, List<Map<String, Object>> events) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object nestedValue = entry.getValue();
                String nestedPath = path + "." + key;
                if (("id".equals(key) || "paperId".equals(key) || "paperRef".equals(key))
                        && !isAllowedMemoryIdentity(key, nestedValue)) {
                    events.add(event(
                            "memory_contains_non_opaque_paper_id",
                            "",
                            nestedPath,
                            Map.of("field", key, "value", stringValue(nestedValue))
                    ));
                    continue;
                }
                collectMemoryIdentityDiagnostics(nestedValue, nestedPath, events);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                collectMemoryIdentityDiagnostics(list.get(index), path + "[" + index + "]", events);
            }
        }
    }

    private void collectToolArgumentPaperRefDiagnostics(Object value,
                                                       String path,
                                                       String toolName,
                                                       List<Map<String, Object>> events) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object nestedValue = entry.getValue();
                String nestedPath = path + "." + key;
                if ("paperRef".equals(key)) {
                    String ref = stringValue(nestedValue);
                    if (!ref.isBlank() && !isOpaquePaperRef(ref)) {
                        events.add(event(
                                "non_opaque_paper_ref_in_tool_args",
                                toolName,
                                nestedPath,
                                Map.of(
                                        "paperRef", ref,
                                        "nextAction", "use resolve_papers before passing paperRef"
                                )
                        ));
                    }
                    continue;
                }
                if ("paperRefs".equals(key) && nestedValue instanceof List<?> list) {
                    for (int index = 0; index < list.size(); index++) {
                        String ref = stringValue(list.get(index));
                        if (!ref.isBlank() && !isOpaquePaperRef(ref)) {
                            events.add(event(
                                    "non_opaque_paper_ref_in_tool_args",
                                    toolName,
                                    nestedPath + "[" + index + "]",
                                    Map.of(
                                            "paperRef", ref,
                                            "nextAction", "use resolve_papers before passing paperRef"
                                    )
                            ));
                        }
                    }
                    continue;
                }
                collectToolArgumentPaperRefDiagnostics(nestedValue, nestedPath, toolName, events);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                collectToolArgumentPaperRefDiagnostics(list.get(index), path + "[" + index + "]", toolName, events);
            }
        }
    }

    private Map<String, Object> event(String code,
                                      String toolName,
                                      String path,
                                      Map<String, Object> details) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("code", code == null ? "" : code.trim());
        if (toolName != null && !toolName.isBlank()) {
            event.put("toolName", toolName.trim());
        }
        event.put("path", path == null ? "" : path.trim());
        event.put("details", details == null ? Map.of() : details);
        return event;
    }

    private boolean isAllowedMemoryIdentity(String key, Object value) {
        String text = stringValue(value);
        if ("paperRef".equals(key)) {
            return text.startsWith("paper_");
        }
        return false;
    }

    private boolean isOpaquePaperRef(String value) {
        String text = value == null ? "" : value.trim();
        return text.startsWith("paper_");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safeSegment(String value) {
        String raw = value == null || value.isBlank() ? "unknown" : value.trim();
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
