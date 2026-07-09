package com.yizhaoqi.smartpai.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ReadingToolCallGate {

    private static final String OUTLINE_TOOL_NAME = "get_paper_outline";
    private static final String LIST_LOCATIONS_TOOL_NAME = "list_paper_locations";
    private static final String LOCATION_TOOL_NAME = "find_reading_locations";
    private static final String READ_LOCATIONS_TOOL_NAME = "read_locations";
    private static final String TRACE_SOURCE_QUOTES_TOOL_NAME = "trace_source_quotes";

    ReadingToolCallValidation validate(String toolName,
                                       Map<String, Object> arguments,
                                       ReadingTurnState state) {
        String safeToolName = stringValue(toolName);
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        if (state != null
                && state.semanticLocationEvidenceMissing
                && (OUTLINE_TOOL_NAME.equals(safeToolName) || LIST_LOCATIONS_TOOL_NAME.equals(safeToolName))) {
            return ReadingToolCallValidation.rejected("semantic_location_evidence_missing");
        }
        if (LOCATION_TOOL_NAME.equals(safeToolName)) {
            if (!hasDeclaredLocationQueryPlan(safeArguments)) {
                return ReadingToolCallValidation.rejected("typed_location_query_plan_missing");
            }
            List<String> paperHandles = stringList(safeArguments.get("paperHandles"));
            if (state == null || paperHandles.isEmpty() || !state.semanticPaperHandles.containsAll(paperHandles)) {
                return ReadingToolCallValidation.rejected("hidden_paper_handle");
            }
        }
        if (LIST_LOCATIONS_TOOL_NAME.equals(safeToolName)) {
            List<String> paperHandles = stringList(safeArguments.get("paperHandles"));
            if (state == null || paperHandles.isEmpty() || !state.deterministicLocationPaperHandles.containsAll(paperHandles)) {
                return ReadingToolCallValidation.rejected("hidden_paper_handle");
            }
        }
        if (OUTLINE_TOOL_NAME.equals(safeToolName)) {
            List<String> paperHandles = stringList(safeArguments.get("paperHandles"));
            if (state == null || paperHandles.isEmpty() || !state.deterministicLocationPaperHandles.containsAll(paperHandles)) {
                return ReadingToolCallValidation.rejected("hidden_paper_handle");
            }
        }
        if (READ_LOCATIONS_TOOL_NAME.equals(safeToolName)) {
            List<String> locationRefs = stringList(safeArguments.get("locationRefs"));
            if (state == null || locationRefs.isEmpty() || !state.disclosedLocationRefs.containsAll(locationRefs)) {
                return ReadingToolCallValidation.rejected("hidden_location_ref");
            }
        }
        if (TRACE_SOURCE_QUOTES_TOOL_NAME.equals(safeToolName)) {
            List<String> sourceQuoteRefs = stringList(safeArguments.get("sourceQuoteRefs"));
            if (state == null || sourceQuoteRefs.isEmpty() || !state.traceableSourceQuoteRefs.containsAll(sourceQuoteRefs)) {
                return ReadingToolCallValidation.rejected("hidden_source_quote_ref");
            }
        }
        return ReadingToolCallValidation.allowed();
    }

    private boolean hasDeclaredLocationQueryPlan(Map<String, Object> arguments) {
        Map<String, Object> queryPlan = objectMap(arguments == null ? null : arguments.get("queryPlan"));
        return !SearchText.tokens(stringValue(queryPlan.get("queryText"))).isEmpty()
                && !stringValue(queryPlan.get("intent")).isBlank()
                && !stringValue(queryPlan.get("sourceLanguage")).isBlank()
                && !stringValue(queryPlan.get("retrievalLanguage")).isBlank()
                && !stringList(queryPlan.get("sectionRoles")).isEmpty();
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> rawValues)) {
            return List.of();
        }
        return rawValues.stream()
                .map(this::stringValue)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
