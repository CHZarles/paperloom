package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperLocationType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ReadingToolArgumentValidator {

    private static final Set<String> SEARCH_FORBIDDEN_ARGUMENTS = Set.of(
            "paperId",
            "paperIds",
            "paperRef",
            "paperRefs",
            "chunkId",
            "chunkIds",
            "chunkRef",
            "readingElementId",
            "modelVersion",
            "indexVersion",
            "indexName",
            "query",
            "question",
            "semanticNeed",
            "recommendationNeed",
            "limit",
            "topK",
            "pageSize",
            "maxCandidates",
            "budget",
            "rerank",
            "rerankEnabled",
            "searchMode"
    );

    private static final Set<String> LOCATION_FORBIDDEN_ARGUMENTS = Set.of(
            "paperId",
            "paperIds",
            "paperRef",
            "paperRefs",
            "locationId",
            "locationIds",
            "chunkId",
            "chunkIds",
            "chunkRef",
            "readingElementId",
            "modelVersion",
            "indexVersion",
            "indexName",
            "query",
            "question",
            "readingNeed",
            "semanticNeed",
            "coverageTargets",
            "limit",
            "topK",
            "maxCandidates",
            "pageSize",
            "pageWindow",
            "budget",
            "rerank",
            "rerankEnabled",
            "searchMode"
    );

    private static final Set<String> SEARCH_ALLOWED_ARGUMENTS = Set.of("queryText");
    private static final Set<String> LOCATION_ALLOWED_ARGUMENTS = Set.of("paperHandles", "queryText", "locationTypes");

    public ValidationResult validateSearchPaperCandidates(Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String forbiddenArgument = firstForbiddenArgument(safeArguments, SEARCH_FORBIDDEN_ARGUMENTS);
        if (forbiddenArgument != null) {
            return ValidationResult.invalid("forbidden_argument", forbiddenArgument);
        }
        String unsupportedArgument = firstUnsupportedTopLevelArgument(safeArguments, SEARCH_ALLOWED_ARGUMENTS);
        if (unsupportedArgument != null) {
            return ValidationResult.invalid("unsupported_argument", unsupportedArgument);
        }
        if (!safeArguments.containsKey("queryText") || SearchText.tokens(stringValue(safeArguments.get("queryText"))).isEmpty()) {
            return ValidationResult.invalid("missing_argument", "queryText");
        }
        return ValidationResult.validResult();
    }

    public ValidationResult validateFindReadingLocations(Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String forbiddenArgument = firstForbiddenArgument(safeArguments, LOCATION_FORBIDDEN_ARGUMENTS);
        if (forbiddenArgument != null) {
            return ValidationResult.invalid("forbidden_argument", forbiddenArgument);
        }
        String unsupportedArgument = firstUnsupportedTopLevelArgument(safeArguments, LOCATION_ALLOWED_ARGUMENTS);
        if (unsupportedArgument != null) {
            return ValidationResult.invalid("unsupported_argument", unsupportedArgument);
        }
        if (stringList(safeArguments.get("paperHandles")).isEmpty()) {
            return ValidationResult.invalid("missing_argument", "paperHandles");
        }
        if (!safeArguments.containsKey("queryText") || SearchText.tokens(stringValue(safeArguments.get("queryText"))).isEmpty()) {
            return ValidationResult.invalid("missing_argument", "queryText");
        }
        if (safeArguments.containsKey("locationTypes")) {
            ValidationResult locationTypesResult = validateLocationTypes(safeArguments.get("locationTypes"));
            if (!locationTypesResult.valid()) {
                return locationTypesResult;
            }
        }
        return ValidationResult.validResult();
    }

    public List<PaperLocationType> locationTypes(Object value) {
        if (!(value instanceof List<?> rawValues)) {
            return List.of();
        }
        LinkedHashSet<PaperLocationType> types = new LinkedHashSet<>();
        for (Object rawValue : rawValues) {
            String normalized = stringValue(rawValue).toUpperCase(Locale.ROOT);
            if (normalized.isBlank()) {
                continue;
            }
            types.add(PaperLocationType.valueOf(normalized));
        }
        return List.copyOf(types);
    }

    public List<String> stringList(Object value) {
        if (!(value instanceof List<?> rawValues)) {
            return List.of();
        }
        return rawValues.stream()
                .map(this::stringValue)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private ValidationResult validateLocationTypes(Object value) {
        if (!(value instanceof List<?> rawValues)) {
            return ValidationResult.invalid("unsupported_location_type", "locationTypes");
        }
        for (Object rawValue : rawValues) {
            String normalized = stringValue(rawValue).toUpperCase(Locale.ROOT);
            if (normalized.isBlank()) {
                continue;
            }
            try {
                PaperLocationType.valueOf(normalized);
            } catch (IllegalArgumentException exception) {
                return ValidationResult.invalid("unsupported_location_type", stringValue(rawValue));
            }
        }
        return ValidationResult.validResult();
    }

    private String firstForbiddenArgument(Object value, Set<String> forbiddenArguments) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = stringValue(entry.getKey());
                if (forbiddenArguments.contains(key)) {
                    return key;
                }
                String nestedForbiddenArgument = firstForbiddenArgument(entry.getValue(), forbiddenArguments);
                if (nestedForbiddenArgument != null) {
                    return nestedForbiddenArgument;
                }
            }
            return null;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String nestedForbiddenArgument = firstForbiddenArgument(item, forbiddenArguments);
                if (nestedForbiddenArgument != null) {
                    return nestedForbiddenArgument;
                }
            }
        }
        return null;
    }

    private String firstUnsupportedTopLevelArgument(Map<String, Object> arguments, Set<String> allowedArguments) {
        for (String key : arguments.keySet()) {
            if (!allowedArguments.contains(key)) {
                return key;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record ValidationResult(boolean valid, String error, String argument) {
        static ValidationResult validResult() {
            return new ValidationResult(true, "", "");
        }

        static ValidationResult invalid(String error, String argument) {
            return new ValidationResult(false, error, argument);
        }
    }
}
