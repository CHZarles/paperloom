package com.yizhaoqi.smartpai.service;

import java.lang.reflect.Array;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

record ReadingTurnFrame(
        Set<String> clickedSourceQuoteRefs,
        Set<String> clickedPaperHandles,
        Set<String> clickedLocationRefs,
        String readingAction,
        String userGoal,
        Map<String, Object> persistedReadingStatePatch
) {
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

    ReadingTurnFrame {
        clickedSourceQuoteRefs = copyStringSet(clickedSourceQuoteRefs);
        clickedPaperHandles = copyStringSet(clickedPaperHandles);
        clickedLocationRefs = copyStringSet(clickedLocationRefs);
        readingAction = readingAction == null ? "" : readingAction.trim().toUpperCase(java.util.Locale.ROOT);
        if (!READING_ACTIONS.contains(readingAction)) {
            readingAction = "";
        }
        userGoal = userGoal == null ? "" : userGoal.trim();
        persistedReadingStatePatch = persistedReadingStatePatch == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(persistedReadingStatePatch));
    }

    static ReadingTurnFrame from(ProductTurnRequest request) {
        ProductTurnRequest safeRequest = request == null
                ? new ProductTurnRequest(null, "", "", "", SourceScope.auto(), List.of(), Map.of(), ProductModelContext.defaults())
                : request;
        Map<String, Object> memory = safeRequest.memory();
        return new ReadingTurnFrame(
                clickedSourceQuoteRefs(memory),
                clickedPaperHandles(memory),
                clickedLocationRefs(memory),
                readingTurnAction(memory),
                safeRequest.userMessage(),
                objectMap(memory == null ? null : memory.get("readingStatePatch"))
        );
    }

    private static Set<String> clickedSourceQuoteRefs(Map<String, Object> memory) {
        if (memory == null || !(memory.get("readingTurnAnchors") instanceof Map<?, ?> anchors)) {
            return Set.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (Object rawRef : rawRefValues(anchors.get("clickedSourceQuoteRefs"))) {
            String sourceQuoteRef = stringValue(rawRef);
            if (SOURCE_QUOTE_REF_PATTERN.matcher(sourceQuoteRef).matches()) {
                refs.add(sourceQuoteRef);
            }
            if (refs.size() >= MAX_CLICKED_SOURCE_QUOTE_REFS) {
                break;
            }
        }
        return refs;
    }

    private static Set<String> clickedPaperHandles(Map<String, Object> memory) {
        if (memory == null || !(memory.get("readingTurnAnchors") instanceof Map<?, ?> anchors)) {
            return Set.of();
        }
        LinkedHashSet<String> handles = new LinkedHashSet<>();
        for (Object rawHandle : rawRefValues(anchors.get("clickedPaperHandles"))) {
            String paperHandle = stringValue(rawHandle);
            if (PAPER_HANDLE_PATTERN.matcher(paperHandle).matches()) {
                handles.add(paperHandle);
            }
            if (handles.size() >= MAX_CLICKED_PAPER_HANDLES) {
                break;
            }
        }
        return handles;
    }

    private static Set<String> clickedLocationRefs(Map<String, Object> memory) {
        if (memory == null || !(memory.get("readingTurnAnchors") instanceof Map<?, ?> anchors)) {
            return Set.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (Object rawRef : rawRefValues(anchors.get("clickedLocationRefs"))) {
            String locationRef = stringValue(rawRef);
            if (LOCATION_REF_PATTERN.matcher(locationRef).matches()) {
                refs.add(locationRef);
            }
            if (refs.size() >= MAX_CLICKED_LOCATION_REFS) {
                break;
            }
        }
        return refs;
    }

    private static String readingTurnAction(Map<String, Object> memory) {
        if (memory == null) {
            return "";
        }
        return stringValue(memory.get("readingTurnAction")).toUpperCase(java.util.Locale.ROOT);
    }

    private static List<Object> rawRefValues(Object rawValue) {
        if (rawValue instanceof List<?> list) {
            return List.copyOf(list);
        }
        if (rawValue != null && rawValue.getClass().isArray()) {
            int length = Array.getLength(rawValue);
            java.util.ArrayList<Object> values = new java.util.ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                values.add(Array.get(rawValue, index));
            }
            return List.copyOf(values);
        }
        return List.of();
    }

    private static Map<String, Object> objectMap(Object value) {
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

    private static Set<String> copyStringSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                copy.add(value.trim());
            }
        }
        return Collections.unmodifiableSet(copy);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
