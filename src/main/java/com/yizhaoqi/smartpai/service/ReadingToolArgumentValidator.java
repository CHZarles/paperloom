package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperLocationType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    private static final Set<String> LIST_LOCATIONS_FORBIDDEN_ARGUMENTS = Set.of(
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
            "queryText",
            "question",
            "readingNeed",
            "semanticNeed",
            "topicText",
            "ordinal",
            "ordinals",
            "candidateOrdinal",
            "resultOrdinal",
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

    private static final Set<String> READ_FORBIDDEN_ARGUMENTS = Set.of(
            "paperId",
            "paperIds",
            "paperRef",
            "paperRefs",
            "modelVersion",
            "chunkId",
            "chunkIds",
            "chunkRef",
            "readingElementId",
            "query",
            "queryText",
            "question",
            "readingNeed",
            "semanticNeed",
            "subQuestions",
            "coverageTargets",
            "limit",
            "topK",
            "pageSize",
            "maxCandidates",
            "maxChars",
            "maxQuotes",
            "maxCharsPerLocation",
            "maxTotalChars",
            "maxQuotesPerLocation",
            "budget",
            "chunkSize",
            "chunkOverlap",
            "overlap",
            "pageWindow",
            "indexName",
            "indexVersion",
            "splitPolicyVersion",
            "contentHash",
            "quoteKinds",
            "sourceQuoteRef"
    );

    private static final Set<String> TRACE_FORBIDDEN_ARGUMENTS = Set.of(
            "paperId",
            "paperIds",
            "paperRef",
            "paperRefs",
            "modelVersion",
            "locationRef",
            "locationRefs",
            "chunkId",
            "chunkIds",
            "chunkRef",
            "readingElementId",
            "pageNumber",
            "pageWindow",
            "query",
            "queryText",
            "question",
            "semanticNeed",
            "readingNeed",
            "limit",
            "topK",
            "pageSize",
            "maxQuotes",
            "maxChars",
            "budget",
            "splitPolicyVersion",
            "contentHash",
            "quoteKinds"
    );

    private static final Set<String> SEARCH_ALLOWED_ARGUMENTS = Set.of("queryText");
    private static final Set<String> LOCATION_ALLOWED_ARGUMENTS = Set.of("paperHandles", "queryText", "locationTypes");
    private static final Set<String> LIST_LOCATIONS_ALLOWED_ARGUMENTS =
            Set.of("paperHandles", "pageRange", "locationTypes");
    private static final Set<String> READ_ALLOWED_ARGUMENTS = Set.of("locationRefs");
    private static final Set<String> TRACE_ALLOWED_ARGUMENTS = Set.of("sourceQuoteRefs");
    private static final Pattern SOURCE_QUOTE_REF_PATTERN =
            Pattern.compile("^source_quote_[A-Za-z0-9_-]+$");

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

    public ValidationResult validateListPaperLocations(Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String forbiddenArgument = firstForbiddenArgument(safeArguments, LIST_LOCATIONS_FORBIDDEN_ARGUMENTS);
        if (forbiddenArgument != null) {
            return ValidationResult.invalid("forbidden_argument", forbiddenArgument);
        }
        String unsupportedArgument = firstUnsupportedTopLevelArgument(safeArguments, LIST_LOCATIONS_ALLOWED_ARGUMENTS);
        if (unsupportedArgument != null) {
            return ValidationResult.invalid("unsupported_argument", unsupportedArgument);
        }
        if (stringList(safeArguments.get("paperHandles")).isEmpty()) {
            return ValidationResult.invalid("missing_argument", "paperHandles");
        }
        if (safeArguments.containsKey("pageRange")) {
            ValidationResult pageRangeResult = validatePageRange(safeArguments.get("pageRange"));
            if (!pageRangeResult.valid()) {
                return pageRangeResult;
            }
        }
        if (safeArguments.containsKey("locationTypes")) {
            ValidationResult locationTypesResult = validateLocationTypes(safeArguments.get("locationTypes"));
            if (!locationTypesResult.valid()) {
                return locationTypesResult;
            }
        }
        return ValidationResult.validResult();
    }

    public ValidationResult validateReadLocations(Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String forbiddenArgument = firstForbiddenArgument(safeArguments, READ_FORBIDDEN_ARGUMENTS);
        if (forbiddenArgument != null) {
            return ValidationResult.invalid("forbidden_argument", forbiddenArgument);
        }
        String unsupportedArgument = firstUnsupportedTopLevelArgument(safeArguments, READ_ALLOWED_ARGUMENTS);
        if (unsupportedArgument != null) {
            return ValidationResult.invalid("unsupported_argument", unsupportedArgument);
        }
        if (stringList(safeArguments.get("locationRefs")).isEmpty()) {
            return ValidationResult.invalid("missing_argument", "locationRefs");
        }
        if (stringList(safeArguments.get("locationRefs")).stream().anyMatch(this::looksLikeOrdinalReference)) {
            return ValidationResult.invalid("invalid_location_ref", "locationRefs");
        }
        return ValidationResult.validResult();
    }

    public ValidationResult validateTraceSourceQuotes(Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String forbiddenArgument = firstForbiddenArgument(safeArguments, TRACE_FORBIDDEN_ARGUMENTS);
        if (forbiddenArgument != null) {
            return ValidationResult.invalid("forbidden_argument", forbiddenArgument);
        }
        String unsupportedArgument = firstUnsupportedTopLevelArgument(safeArguments, TRACE_ALLOWED_ARGUMENTS);
        if (unsupportedArgument != null) {
            return ValidationResult.invalid("unsupported_argument", unsupportedArgument);
        }
        List<String> sourceQuoteRefs = stringList(safeArguments.get("sourceQuoteRefs"));
        if (sourceQuoteRefs.isEmpty()) {
            return ValidationResult.invalid("missing_argument", "sourceQuoteRefs");
        }
        if (sourceQuoteRefs.stream().anyMatch(this::invalidSourceQuoteRef)) {
            return ValidationResult.invalid("invalid_source_quote_ref", "sourceQuoteRefs");
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

    public PageRange pageRange(Object value) {
        if (!(value instanceof Map<?, ?> rawRange)) {
            return null;
        }
        Integer from = integerValue(rawRange.get("from"));
        Integer to = integerValue(rawRange.get("to"));
        if (from == null || to == null) {
            return null;
        }
        return new PageRange(from, to);
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

    private ValidationResult validatePageRange(Object value) {
        PageRange range = pageRange(value);
        if (range == null || range.from() < 1 || range.to() < 1 || range.from() > range.to()) {
            return ValidationResult.invalid("invalid_page_range", "pageRange");
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

    private boolean looksLikeOrdinalReference(String value) {
        String normalized = stringValue(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.matches("#?\\d+")
                || normalized.matches("\\[\\d+]")
                || normalized.matches("(candidate|item|location|result|ref)[ _:-]*\\d+");
    }

    private boolean invalidSourceQuoteRef(String value) {
        String normalized = stringValue(value);
        return looksLikeOrdinalReference(normalized)
                || !SOURCE_QUOTE_REF_PATTERN.matcher(normalized).matches();
    }

    private Integer integerValue(Object value) {
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Long longValue
                && longValue >= Integer.MIN_VALUE
                && longValue <= Integer.MAX_VALUE) {
            return longValue.intValue();
        }
        if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            if (Math.floor(doubleValue) == doubleValue
                    && doubleValue >= Integer.MIN_VALUE
                    && doubleValue <= Integer.MAX_VALUE) {
                return (int) doubleValue;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record PageRange(Integer from, Integer to) {
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
