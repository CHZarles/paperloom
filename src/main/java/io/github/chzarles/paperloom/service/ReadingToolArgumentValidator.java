package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperLocationType;
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

    private static final Set<String> OUTLINE_FORBIDDEN_ARGUMENTS = Set.of(
            "paperId",
            "paperIds",
            "paperRef",
            "paperRefs",
            "locationId",
            "locationIds",
            "locationRef",
            "locationRefs",
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
            "pageRange",
            "mapMode",
            "depth",
            "includeText",
            "includeContent",
            "limit",
            "topK",
            "maxCandidates",
            "pageSize",
            "budget",
            "rerank",
            "rerankEnabled",
            "searchMode"
    );

    private static final Set<String> LIST_PAPERS_FORBIDDEN_ARGUMENTS = Set.of(
            "paperId",
            "paperIds",
            "paperRef",
            "paperRefs",
            "locationRef",
            "locationRefs",
            "sourceQuoteRef",
            "sourceQuoteRefs",
            "modelVersion",
            "indexVersion",
            "indexName",
            "chunkRef",
            "readingElementId",
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
            "page",
            "pageSize",
            "pageRange",
            "limit",
            "topK",
            "maxCandidates",
            "budget",
            "rerank",
            "rerankEnabled",
            "searchMode",
            "score",
            "rank"
    );

    private static final Set<String> IDENTITY_FORBIDDEN_ARGUMENTS = Set.of(
            "paperId",
            "paperIds",
            "paperRef",
            "paperRefs",
            "paperHandle",
            "paperHandles",
            "locationId",
            "locationIds",
            "locationRef",
            "locationRefs",
            "sourceQuoteId",
            "sourceQuoteIds",
            "sourceQuoteRef",
            "sourceQuoteRefs",
            "modelVersion",
            "indexVersion",
            "indexName",
            "chunkId",
            "chunkIds",
            "chunkRef",
            "readingElementId",
            "query",
            "queryText",
            "question",
            "readingNeed",
            "semanticNeed",
            "topicText",
            "recommendationNeed",
            "relatedTo",
            "ordinal",
            "ordinals",
            "candidateOrdinal",
            "resultOrdinal",
            "page",
            "pageSize",
            "pageRange",
            "limit",
            "topK",
            "maxCandidates",
            "budget",
            "rerank",
            "rerankEnabled",
            "searchMode",
            "score",
            "rank"
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

    private static final Set<String> SESSION_ALLOWED_ARGUMENTS = Set.of();
    private static final Set<String> LIST_PAPERS_ALLOWED_ARGUMENTS =
            Set.of("filters", "includeFacets", "sort");
    private static final Set<String> LIST_PAPERS_FILTER_ALLOWED_ARGUMENTS = Set.of(
            "titleContains",
            "titleExact",
            "filenameContains",
            "filenameExact",
            "authorName",
            "doiExact",
            "arxivIdExact",
            "yearRange",
            "venue"
    );
    private static final Set<String> IDENTITY_ALLOWED_ARGUMENTS = Set.of("identityHints");
    private static final Set<String> IDENTITY_HINT_ALLOWED_ARGUMENTS = Set.of(
            "titleContains",
            "titleExact",
            "filenameContains",
            "filenameExact",
            "filename",
            "doiExact",
            "arxivIdExact",
            "authorName",
            "year"
    );
    private static final Set<String> SEARCH_ALLOWED_ARGUMENTS = Set.of("queryText");
    private static final Set<String> LOCATION_ALLOWED_ARGUMENTS =
            Set.of("paperHandles", "locationTypes", "queryPlan");
    private static final Set<String> LOCATION_QUERY_PLAN_ALLOWED_ARGUMENTS = Set.of(
            "queryText",
            "intent",
            "sourceLanguage",
            "retrievalLanguage",
            "sectionRoles",
            "locationTypes"
    );
    private static final Set<String> LOCATION_QUERY_PLAN_INTENTS = Set.of(
            "METHOD",
            "EXPERIMENT_SETUP",
            "MAIN_CLAIM",
            "LIMITATION",
            "DATASET",
            "BASELINE",
            "ABLATION",
            "METRIC",
            "GENERAL"
    );
    private static final Set<String> LOCATION_QUERY_PLAN_SECTION_ROLES = Set.of(
            "ABSTRACT",
            "INTRODUCTION",
            "BACKGROUND",
            "METHOD",
            "EXPERIMENT",
            "RESULT",
            "DISCUSSION",
            "LIMITATION",
            "CONCLUSION",
            "APPENDIX"
    );
    private static final Set<String> LIST_LOCATIONS_ALLOWED_ARGUMENTS =
            Set.of("paperHandles", "pageRange", "locationTypes");
    private static final Set<String> OUTLINE_ALLOWED_ARGUMENTS = Set.of("paperHandles");
    private static final Set<String> READ_ALLOWED_ARGUMENTS = Set.of("locationRefs");
    private static final Set<String> TRACE_ALLOWED_ARGUMENTS = Set.of("sourceQuoteRefs");
    private static final Pattern SOURCE_QUOTE_REF_PATTERN =
            Pattern.compile("^source_quote_[A-Za-z0-9_-]+$");

    public ValidationResult validateGetSessionState(Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String forbiddenArgument = firstForbiddenArgument(safeArguments, LIST_PAPERS_FORBIDDEN_ARGUMENTS);
        if (forbiddenArgument != null) {
            return ValidationResult.invalid("forbidden_argument", forbiddenArgument);
        }
        String unsupportedArgument = firstUnsupportedTopLevelArgument(safeArguments, SESSION_ALLOWED_ARGUMENTS);
        if (unsupportedArgument != null) {
            return ValidationResult.invalid("unsupported_argument", unsupportedArgument);
        }
        return ValidationResult.validResult();
    }

    public ValidationResult validateListPapers(Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String forbiddenArgument = firstForbiddenArgument(safeArguments, LIST_PAPERS_FORBIDDEN_ARGUMENTS);
        if (forbiddenArgument != null) {
            return ValidationResult.invalid("forbidden_argument", forbiddenArgument);
        }
        String unsupportedArgument = firstUnsupportedTopLevelArgument(safeArguments, LIST_PAPERS_ALLOWED_ARGUMENTS);
        if (unsupportedArgument != null) {
            return ValidationResult.invalid("unsupported_argument", unsupportedArgument);
        }
        if (safeArguments.containsKey("filters")) {
            if (!(safeArguments.get("filters") instanceof Map<?, ?> filters)) {
                return ValidationResult.invalid("unsupported_argument", "filters");
            }
            String unsupportedFilter = firstUnsupportedFilterArgument(filters);
            if (unsupportedFilter != null) {
                return ValidationResult.invalid("unsupported_argument", unsupportedFilter);
            }
            if (filters.containsKey("yearRange")) {
                ValidationResult yearRangeResult = validateYearRange(filters.get("yearRange"));
                if (!yearRangeResult.valid()) {
                    return yearRangeResult;
                }
            }
        }
        if (safeArguments.containsKey("includeFacets")
                && !(safeArguments.get("includeFacets") instanceof Boolean)) {
            return ValidationResult.invalid("invalid_boolean", "includeFacets");
        }
        if (safeArguments.containsKey("sort") && !validListPaperSort(safeArguments.get("sort"))) {
            return ValidationResult.invalid("unsupported_sort", "sort");
        }
        return ValidationResult.validResult();
    }

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

    public ValidationResult validateFindPapersByIdentity(Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String forbiddenArgument = firstForbiddenArgument(safeArguments, IDENTITY_FORBIDDEN_ARGUMENTS);
        if (forbiddenArgument != null) {
            return ValidationResult.invalid("forbidden_argument", forbiddenArgument);
        }
        String unsupportedArgument = firstUnsupportedTopLevelArgument(safeArguments, IDENTITY_ALLOWED_ARGUMENTS);
        if (unsupportedArgument != null) {
            return ValidationResult.invalid("unsupported_argument", unsupportedArgument);
        }
        if (!safeArguments.containsKey("identityHints")) {
            return ValidationResult.invalid("missing_argument", "identityHints");
        }
        if (!(safeArguments.get("identityHints") instanceof Map<?, ?> rawHints)) {
            return ValidationResult.invalid("unsupported_argument", "identityHints");
        }
        String unsupportedHint = firstUnsupportedIdentityHintArgument(rawHints);
        if (unsupportedHint != null) {
            return ValidationResult.invalid("unsupported_argument", unsupportedHint);
        }
        if (rawHints.containsKey("year")) {
            Integer year = integerValue(rawHints.get("year"));
            if (year == null || year < 1) {
                return ValidationResult.invalid("invalid_year", "year");
            }
        }
        if (!identityHints(rawHints).hasTextualOrExternalHint()) {
            return ValidationResult.invalid("missing_argument", "identityHints");
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
        if (!safeArguments.containsKey("queryPlan")) {
            return ValidationResult.invalid("missing_argument", "queryPlan");
        }
        ValidationResult queryPlanResult = validateLocationQueryPlan(safeArguments.get("queryPlan"));
        if (!queryPlanResult.valid()) {
            return queryPlanResult;
        }
        if (SearchText.tokens(locationQueryText(safeArguments)).isEmpty()) {
            return ValidationResult.invalid("missing_argument", "queryPlan.queryText");
        }
        if (safeArguments.containsKey("locationTypes")) {
            ValidationResult locationTypesResult = validateLocationTypes(safeArguments.get("locationTypes"));
            if (!locationTypesResult.valid()) {
                return locationTypesResult;
            }
        }
        if (safeArguments.containsKey("locationTypes")
                && safeArguments.containsKey("queryPlan")
                && !locationTypes(safeArguments.get("locationTypes")).isEmpty()
                && !locationQueryPlan(safeArguments.get("queryPlan")).locationTypes().isEmpty()
                && !locationTypes(safeArguments.get("locationTypes"))
                .equals(locationQueryPlan(safeArguments.get("queryPlan")).locationTypes())) {
            return ValidationResult.invalid("conflicting_argument", "locationTypes");
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

    public ValidationResult validateGetPaperOutline(Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String forbiddenArgument = firstForbiddenArgument(safeArguments, OUTLINE_FORBIDDEN_ARGUMENTS);
        if (forbiddenArgument != null) {
            return ValidationResult.invalid("forbidden_argument", forbiddenArgument);
        }
        String unsupportedArgument = firstUnsupportedTopLevelArgument(safeArguments, OUTLINE_ALLOWED_ARGUMENTS);
        if (unsupportedArgument != null) {
            return ValidationResult.invalid("unsupported_argument", unsupportedArgument);
        }
        if (stringList(safeArguments.get("paperHandles")).isEmpty()) {
            return ValidationResult.invalid("missing_argument", "paperHandles");
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

    public LocationQueryPlan locationQueryPlan(Object value) {
        if (!(value instanceof Map<?, ?> rawPlan)) {
            return LocationQueryPlan.empty();
        }
        return new LocationQueryPlan(
                stringValue(rawPlan.get("queryText")),
                stringValue(rawPlan.get("intent")).toUpperCase(Locale.ROOT),
                stringValue(rawPlan.get("sourceLanguage")),
                stringValue(rawPlan.get("retrievalLanguage")),
                normalizedSectionRoles(rawPlan.get("sectionRoles")),
                locationTypes(rawPlan.get("locationTypes"))
        );
    }

    public String locationQueryText(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        LocationQueryPlan plan = locationQueryPlan(arguments.get("queryPlan"));
        return plan.queryText();
    }

    public List<PaperLocationType> effectiveLocationTypes(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }
        LocationQueryPlan plan = locationQueryPlan(arguments.get("queryPlan"));
        if (!plan.locationTypes().isEmpty()) {
            return plan.locationTypes();
        }
        return locationTypes(arguments.get("locationTypes"));
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

    public ListPaperFilters listPaperFilters(Object value) {
        if (!(value instanceof Map<?, ?> rawFilters)) {
            return ListPaperFilters.empty();
        }
        return new ListPaperFilters(
                stringValue(rawFilters.get("titleContains")),
                stringValue(rawFilters.get("titleExact")),
                stringValue(rawFilters.get("filenameContains")),
                stringValue(rawFilters.get("filenameExact")),
                stringValue(rawFilters.get("authorName")),
                stringValue(rawFilters.get("doiExact")),
                stringValue(rawFilters.get("arxivIdExact")),
                yearRange(rawFilters.get("yearRange")),
                stringValue(rawFilters.get("venue"))
        );
    }

    public IdentityHints identityHints(Object value) {
        if (!(value instanceof Map<?, ?> rawHints)) {
            return IdentityHints.empty();
        }
        return new IdentityHints(
                stringValue(rawHints.get("titleContains")),
                stringValue(rawHints.get("titleExact")),
                stringValue(rawHints.get("filenameContains")),
                firstNonBlank(
                        stringValue(rawHints.get("filenameExact")),
                        normalizeIdentityAlias(rawHints.get("filename"))
                ),
                canonicalDoiExact(rawHints.get("doiExact")),
                canonicalArxivIdExact(rawHints.get("arxivIdExact")),
                stringValue(rawHints.get("authorName")),
                integerValue(rawHints.get("year"))
        );
    }

    public boolean includeFacets(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private String normalizeIdentityAlias(Object value) {
        String text = stringValue(value);
        if (SearchText.isBlank(text)) {
            return "";
        }
        return text.trim().replaceFirst("(?i)^exact(?:\\s*[:=>-]\\s*|\\s+|(?=\\d))", "");
    }

    private String firstNonBlank(String first, String second) {
        return SearchText.isBlank(first) ? stringValue(second) : stringValue(first);
    }

    public ListPaperSort listPaperSort(Object value) {
        String normalized = stringValue(value).toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return ListPaperSort.RECENT;
        }
        return ListPaperSort.valueOf(normalized);
    }

    private boolean validListPaperSort(Object value) {
        try {
            listPaperSort(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String firstUnsupportedFilterArgument(Map<?, ?> filters) {
        for (Object rawKey : filters.keySet()) {
            String key = stringValue(rawKey);
            if (!LIST_PAPERS_FILTER_ALLOWED_ARGUMENTS.contains(key)) {
                return key;
            }
        }
        return null;
    }

    private String firstUnsupportedIdentityHintArgument(Map<?, ?> hints) {
        for (Object rawKey : hints.keySet()) {
            String key = stringValue(rawKey);
            if (!IDENTITY_HINT_ALLOWED_ARGUMENTS.contains(key)) {
                return key;
            }
        }
        return null;
    }

    private String firstUnsupportedLocationQueryPlanArgument(Map<?, ?> queryPlan) {
        for (Object rawKey : queryPlan.keySet()) {
            String key = stringValue(rawKey);
            if (!LOCATION_QUERY_PLAN_ALLOWED_ARGUMENTS.contains(key)) {
                return key;
            }
        }
        return null;
    }

    private ValidationResult validateYearRange(Object value) {
        YearRange range = yearRange(value);
        if (range == null || range.from() < 1 || range.to() < 1 || range.from() > range.to()) {
            return ValidationResult.invalid("invalid_year_range", "yearRange");
        }
        return ValidationResult.validResult();
    }

    private YearRange yearRange(Object value) {
        if (!(value instanceof Map<?, ?> rawRange)) {
            return null;
        }
        Integer from = integerValue(rawRange.get("from"));
        Integer to = integerValue(rawRange.get("to"));
        if (from == null || to == null) {
            return null;
        }
        return new YearRange(from, to);
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

    private ValidationResult validateLocationQueryPlan(Object value) {
        if (!(value instanceof Map<?, ?> rawPlan)) {
            return ValidationResult.invalid("unsupported_argument", "queryPlan");
        }
        String unsupportedArgument = firstUnsupportedLocationQueryPlanArgument(rawPlan);
        if (unsupportedArgument != null) {
            return ValidationResult.invalid("unsupported_argument", unsupportedArgument);
        }
        if (SearchText.tokens(stringValue(rawPlan.get("queryText"))).isEmpty()) {
            return ValidationResult.invalid("missing_argument", "queryPlan.queryText");
        }
        String intent = stringValue(rawPlan.get("intent")).toUpperCase(Locale.ROOT);
        if (intent.isBlank()) {
            return ValidationResult.invalid("missing_argument", "queryPlan.intent");
        }
        if (!LOCATION_QUERY_PLAN_INTENTS.contains(intent)) {
            return ValidationResult.invalid("unsupported_location_intent", stringValue(rawPlan.get("intent")));
        }
        ValidationResult sourceLanguageResult = validateRequiredString(rawPlan, "sourceLanguage");
        if (!sourceLanguageResult.valid()) {
            return sourceLanguageResult;
        }
        ValidationResult retrievalLanguageResult = validateRequiredString(rawPlan, "retrievalLanguage");
        if (!retrievalLanguageResult.valid()) {
            return retrievalLanguageResult;
        }
        if (!rawPlan.containsKey("sectionRoles") || normalizedSectionRoles(rawPlan.get("sectionRoles")).isEmpty()) {
            return ValidationResult.invalid("missing_argument", "queryPlan.sectionRoles");
        }
        ValidationResult sectionRolesResult = validateSectionRoles(rawPlan.get("sectionRoles"));
        if (!sectionRolesResult.valid()) {
            return sectionRolesResult;
        }
        if (rawPlan.containsKey("locationTypes")) {
            ValidationResult locationTypesResult = validateLocationTypes(rawPlan.get("locationTypes"));
            if (!locationTypesResult.valid()) {
                return locationTypesResult;
            }
        }
        return ValidationResult.validResult();
    }

    private ValidationResult validateRequiredString(Map<?, ?> rawPlan, String key) {
        if (!rawPlan.containsKey(key) || rawPlan.get(key) == null || stringValue(rawPlan.get(key)).isBlank()) {
            return ValidationResult.invalid("missing_argument", "queryPlan." + key);
        }
        return rawPlan.get(key) instanceof String
                ? ValidationResult.validResult()
                : ValidationResult.invalid("invalid_string", "queryPlan." + key);
    }

    private ValidationResult validateSectionRoles(Object value) {
        if (!(value instanceof List<?> rawValues)) {
            return ValidationResult.invalid("unsupported_section_role", "queryPlan.sectionRoles");
        }
        for (Object rawValue : rawValues) {
            String normalized = stringValue(rawValue).toUpperCase(Locale.ROOT);
            if (normalized.isBlank()) {
                continue;
            }
            if (!LOCATION_QUERY_PLAN_SECTION_ROLES.contains(normalized)) {
                return ValidationResult.invalid("unsupported_section_role", stringValue(rawValue));
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

    private List<String> normalizedSectionRoles(Object value) {
        if (!(value instanceof List<?> rawValues)) {
            return List.of();
        }
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        for (Object rawValue : rawValues) {
            String normalized = stringValue(rawValue).toUpperCase(Locale.ROOT);
            if (!normalized.isBlank()) {
                roles.add(normalized);
            }
        }
        return List.copyOf(roles);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    static String canonicalDoiExact(Object value) {
        String normalized = value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        boolean stripped;
        do {
            stripped = false;
            for (String prefix : List.of(
                    "https://doi.org/",
                    "http://doi.org/",
                    "https://dx.doi.org/",
                    "http://dx.doi.org/",
                    "doi:"
            )) {
                if (normalized.startsWith(prefix)) {
                    normalized = normalized.substring(prefix.length()).trim();
                    stripped = true;
                    break;
                }
            }
        } while (stripped);
        return normalized;
    }

    static String canonicalArxivIdExact(Object value) {
        String normalized = value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        boolean stripped;
        do {
            stripped = false;
            for (String prefix : List.of(
                    "https://arxiv.org/abs/",
                    "http://arxiv.org/abs/",
                    "https://arxiv.org/pdf/",
                    "http://arxiv.org/pdf/",
                    "arxiv:"
            )) {
                if (normalized.startsWith(prefix)) {
                    normalized = normalized.substring(prefix.length()).trim();
                    stripped = true;
                    break;
                }
            }
        } while (stripped);
        if (normalized.endsWith(".pdf")) {
            normalized = normalized.substring(0, normalized.length() - ".pdf".length()).trim();
        }
        return normalized.replaceFirst("v\\d+$", "");
    }

    public record PageRange(Integer from, Integer to) {
    }

    public record YearRange(Integer from, Integer to) {
    }

    public record ListPaperFilters(String titleContains,
                                   String titleExact,
                                   String filenameContains,
                                   String filenameExact,
                                   String authorName,
                                   String doiExact,
                                   String arxivIdExact,
                                   YearRange yearRange,
                                   String venue) {
        static ListPaperFilters empty() {
            return new ListPaperFilters("", "", "", "", "", "", "", null, "");
        }
    }

    public record IdentityHints(String titleContains,
                                String titleExact,
                                String filenameContains,
                                String filenameExact,
                                String doiExact,
                                String arxivIdExact,
                                String authorName,
                                Integer year) {
        static IdentityHints empty() {
            return new IdentityHints("", "", "", "", "", "", "", null);
        }

        boolean hasTextualOrExternalHint() {
            return !SearchText.isBlank(titleContains)
                    || !SearchText.isBlank(titleExact)
                    || !SearchText.isBlank(filenameContains)
                    || !SearchText.isBlank(filenameExact)
                    || !SearchText.isBlank(doiExact)
                    || !SearchText.isBlank(arxivIdExact)
                    || !SearchText.isBlank(authorName);
        }
    }

    public record LocationQueryPlan(String queryText,
                                    String intent,
                                    String sourceLanguage,
                                    String retrievalLanguage,
                                    List<String> sectionRoles,
                                    List<PaperLocationType> locationTypes) {
        public LocationQueryPlan {
            queryText = queryText == null ? "" : queryText.trim();
            intent = intent == null ? "" : intent.trim();
            sourceLanguage = sourceLanguage == null ? "" : sourceLanguage.trim();
            retrievalLanguage = retrievalLanguage == null ? "" : retrievalLanguage.trim();
            sectionRoles = sectionRoles == null ? List.of() : List.copyOf(sectionRoles);
            locationTypes = locationTypes == null ? List.of() : List.copyOf(locationTypes);
        }

        static LocationQueryPlan empty() {
            return new LocationQueryPlan("", "", "", "", List.of(), List.of());
        }
    }

    public enum ListPaperSort {
        RECENT,
        TITLE,
        YEAR
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
