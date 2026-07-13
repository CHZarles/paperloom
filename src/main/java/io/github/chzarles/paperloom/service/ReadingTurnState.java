package io.github.chzarles.paperloom.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

class ReadingTurnState {

    private static final Pattern SOURCE_QUOTE_REF_PATTERN =
            Pattern.compile("^source_quote_[A-Za-z0-9_-]+$");
    private static final Pattern PAPER_HANDLE_PATTERN =
            Pattern.compile("^paper_handle_[A-Za-z0-9_-]+$");
    private static final Pattern LOCATION_REF_PATTERN =
            Pattern.compile("^(page_ref|section_ref|table_ref|figure_ref|location_ref)_[A-Za-z0-9_-]+$");

    final Set<String> clickedSourceQuoteRefs;
    final Set<String> clickedPaperHandles;
    final Set<String> clickedLocationRefs;
    final String readingAction;
    final String userGoal;
    final Map<String, Object> persistedReadingStatePatch;
    final Set<String> traceableSourceQuoteRefs = new LinkedHashSet<>();
    final Set<String> semanticPaperHandles = new LinkedHashSet<>();
    final Set<String> deterministicLocationPaperHandles = new LinkedHashSet<>();
    final Set<String> disclosedLocationRefs = new LinkedHashSet<>();
    final Set<String> allowedSourceQuoteRefs = new LinkedHashSet<>();
    final Map<String, Map<String, Object>> sourceQuotePayloads = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> locationPayloads = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> paperPayloadsByHandle = new LinkedHashMap<>();
    final Map<String, Object> retrievalStatusPayload = new LinkedHashMap<>();
    final Map<String, Object> sessionStatePayload = new LinkedHashMap<>();
    final Set<String> locationSourceTools = new LinkedHashSet<>();
    final List<Map<String, Object>> productStateItems = new ArrayList<>();
    final Set<String> productStatePaperHandles = new LinkedHashSet<>();
    final List<String> paperQueryTexts = new ArrayList<>();
    final List<String> locationQueryTexts = new ArrayList<>();
    final List<ReadingIntentFrame.LocationQueryPlan> locationQueryPlans = new ArrayList<>();
    final Set<String> locationTypes = new LinkedHashSet<>();
    final Set<String> locationIntents = new LinkedHashSet<>();
    final Set<String> sourceLanguages = new LinkedHashSet<>();
    final Set<String> retrievalLanguages = new LinkedHashSet<>();
    final Set<String> sectionRoles = new LinkedHashSet<>();
    boolean searchPapersActionSatisfied;
    boolean listLocationsActionSatisfied;
    boolean findLocationsActionSatisfied;
    boolean semanticLocationSearchUsed;
    boolean semanticLocationEvidenceMissing;
    boolean paperChoiceToolUsed;
    boolean deterministicNavigationToolUsed;
    boolean readLocationsUsed;
    boolean traceSourceQuotesUsed;

    ReadingTurnState(ReadingTurnFrame frame) {
        ReadingTurnFrame safeFrame = frame == null
                ? new ReadingTurnFrame(Set.of(), Set.of(), Set.of(), "", "", Map.of())
                : frame;
        this.clickedSourceQuoteRefs = new LinkedHashSet<>(safeFrame.clickedSourceQuoteRefs());
        this.clickedPaperHandles = new LinkedHashSet<>(safeFrame.clickedPaperHandles());
        this.clickedLocationRefs = new LinkedHashSet<>(safeFrame.clickedLocationRefs());
        this.readingAction = safeFrame.readingAction().trim().toUpperCase(Locale.ROOT);
        this.userGoal = safeFrame.userGoal().trim();
        this.persistedReadingStatePatch = new LinkedHashMap<>(safeFrame.persistedReadingStatePatch());
        this.traceableSourceQuoteRefs.addAll(this.clickedSourceQuoteRefs);
        this.semanticPaperHandles.addAll(this.clickedPaperHandles);
        this.deterministicLocationPaperHandles.addAll(this.clickedPaperHandles);
        Set<String> persistedPaperHandles = persistedPaperHandles(this.persistedReadingStatePatch);
        this.semanticPaperHandles.addAll(persistedPaperHandles);
        this.deterministicLocationPaperHandles.addAll(persistedPaperHandles);
        this.disclosedLocationRefs.addAll(this.clickedLocationRefs);
        this.disclosedLocationRefs.addAll(persistedLocationRefs(this.persistedReadingStatePatch));
        this.traceableSourceQuoteRefs.addAll(persistedSourceQuoteRefs(this.persistedReadingStatePatch));
    }

    private Set<String> persistedPaperHandles(Map<String, Object> patch) {
        Map<String, Object> selectedPaper = objectMap(patch.get("selectedPaper"));
        String paperHandle = stringValue(selectedPaper.get("paperHandle"));
        if (PAPER_HANDLE_PATTERN.matcher(paperHandle).matches()) {
            return Set.of(paperHandle);
        }
        return Set.of();
    }

    private Set<String> persistedLocationRefs(Map<String, Object> patch) {
        Map<String, Object> selectedLocation = objectMap(patch.get("selectedLocation"));
        String locationRef = stringValue(selectedLocation.get("locationRef"));
        if (LOCATION_REF_PATTERN.matcher(locationRef).matches()) {
            return Set.of(locationRef);
        }
        return Set.of();
    }

    private Set<String> persistedSourceQuoteRefs(Map<String, Object> patch) {
        Map<String, Object> selectedSourceQuote = objectMap(patch.get("selectedSourceQuote"));
        String sourceQuoteRef = stringValue(selectedSourceQuote.get("sourceQuoteRef"));
        if (SOURCE_QUOTE_REF_PATTERN.matcher(sourceQuoteRef).matches()) {
            return Set.of(sourceQuoteRef);
        }
        return Set.of();
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
