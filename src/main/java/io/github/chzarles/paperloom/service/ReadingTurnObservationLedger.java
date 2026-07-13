package io.github.chzarles.paperloom.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ReadingTurnObservationLedger(
        String userGoal,
        ReadingIntentFrame intentFrame,
        Map<String, Object> sessionStatePayload,
        List<Map<String, Object>> productStateItems,
        Map<String, Map<String, Object>> paperPayloadsByHandle,
        Map<String, Map<String, Object>> locationPayloads,
        Map<String, Map<String, Object>> sourceQuotePayloads,
        Map<String, Object> retrievalStatusPayload
) {
    public ReadingTurnObservationLedger(String userGoal,
                                        ReadingIntentFrame intentFrame,
                                        Map<String, Object> sessionStatePayload,
                                        List<Map<String, Object>> productStateItems,
                                        Map<String, Map<String, Object>> paperPayloadsByHandle,
                                        Map<String, Map<String, Object>> locationPayloads,
                                        Map<String, Map<String, Object>> sourceQuotePayloads) {
        this(userGoal,
                intentFrame,
                sessionStatePayload,
                productStateItems,
                paperPayloadsByHandle,
                locationPayloads,
                sourceQuotePayloads,
                Map.of());
    }

    public ReadingTurnObservationLedger {
        userGoal = userGoal == null ? "" : userGoal.trim();
        intentFrame = intentFrame == null ? ReadingIntentFrame.empty(userGoal) : intentFrame;
        sessionStatePayload = copyMap(sessionStatePayload);
        productStateItems = copyMapList(productStateItems);
        paperPayloadsByHandle = copyMapOfMaps(paperPayloadsByHandle);
        locationPayloads = copyMapOfMaps(locationPayloads);
        sourceQuotePayloads = copyMapOfMaps(sourceQuotePayloads);
        retrievalStatusPayload = copyMap(retrievalStatusPayload);
    }

    static ReadingTurnObservationLedger empty(String userGoal) {
        return new ReadingTurnObservationLedger(
                userGoal,
                ReadingIntentFrame.empty(userGoal),
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
    }

    boolean hasObservations() {
        return !sessionStatePayload.isEmpty()
                || !productStateItems.isEmpty()
                || !locationPayloads.isEmpty()
                || !sourceQuotePayloads.isEmpty()
                || !retrievalStatusPayload.isEmpty();
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(source));
    }

    private static List<Map<String, Object>> copyMapList(List<Map<String, Object>> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copies = new ArrayList<>(source.size());
        for (Map<String, Object> item : source) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            copies.add(new LinkedHashMap<>(item));
        }
        return List.copyOf(copies);
    }

    private static Map<String, Map<String, Object>> copyMapOfMaps(Map<String, Map<String, Object>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> copies = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            copies.put(entry.getKey(), Map.copyOf(new LinkedHashMap<>(entry.getValue())));
        }
        return Map.copyOf(copies);
    }
}
