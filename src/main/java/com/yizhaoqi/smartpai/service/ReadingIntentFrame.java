package com.yizhaoqi.smartpai.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public record ReadingIntentFrame(
        String originalUserRequest,
        String readingAction,
        List<String> paperQueryTexts,
        List<String> locationQueryTexts,
        List<LocationQueryPlan> locationQueryPlans,
        List<String> locationTypes,
        List<String> locationIntents,
        List<String> sourceLanguages,
        List<String> retrievalLanguages,
        List<String> sectionRoles,
        String planningStatus,
        List<String> missing
) {
    private static final String FIND_LOCATIONS_ACTION = "FIND_LOCATIONS";

    public ReadingIntentFrame {
        originalUserRequest = originalUserRequest == null ? "" : originalUserRequest.trim();
        readingAction = readingAction == null ? "" : readingAction.trim();
        paperQueryTexts = dedupe(paperQueryTexts);
        locationQueryTexts = dedupe(locationQueryTexts);
        locationQueryPlans = locationQueryPlans == null ? List.of() : List.copyOf(locationQueryPlans);
        locationTypes = dedupe(locationTypes);
        locationIntents = dedupe(locationIntents);
        sourceLanguages = dedupe(sourceLanguages);
        retrievalLanguages = dedupe(retrievalLanguages);
        sectionRoles = dedupe(sectionRoles);
        planningStatus = planningStatus == null ? "" : planningStatus.trim();
        missing = dedupe(missing);
    }

    public static ReadingIntentFrame empty(String originalUserRequest) {
        return new ReadingIntentFrame(
                originalUserRequest,
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "not_planned",
                originalUserRequest == null || originalUserRequest.isBlank() ? List.of("user_request") : List.of()
        );
    }

    public static ReadingIntentFrame observed(String originalUserRequest,
                                              String readingAction,
                                              List<String> paperQueryTexts,
                                              List<String> locationQueryTexts,
                                              List<LocationQueryPlan> locationQueryPlans,
                                              List<String> locationTypes,
                                              List<String> locationIntents,
                                              List<String> sourceLanguages,
                                              List<String> retrievalLanguages,
                                              List<String> sectionRoles) {
        List<String> missing = new ArrayList<>();
        if (originalUserRequest == null || originalUserRequest.isBlank()) {
            missing.add("user_request");
        }
        if (FIND_LOCATIONS_ACTION.equals(readingAction) && empty(locationQueryTexts)) {
            missing.add("location_query_plan");
        }
        if (FIND_LOCATIONS_ACTION.equals(readingAction) && empty(locationIntents)) {
            missing.add("location_query_plan");
        }
        if (FIND_LOCATIONS_ACTION.equals(readingAction) && empty(sourceLanguages)) {
            missing.add("location_query_plan");
        }
        if (FIND_LOCATIONS_ACTION.equals(readingAction) && empty(retrievalLanguages)) {
            missing.add("location_query_plan");
        }
        if (FIND_LOCATIONS_ACTION.equals(readingAction) && empty(sectionRoles)) {
            missing.add("location_query_plan");
        }
        return new ReadingIntentFrame(
                originalUserRequest,
                readingAction,
                paperQueryTexts,
                locationQueryTexts,
                locationQueryPlans,
                locationTypes,
                locationIntents,
                sourceLanguages,
                retrievalLanguages,
                sectionRoles,
                planningStatus(
                        paperQueryTexts,
                        locationQueryTexts,
                        locationIntents,
                        sourceLanguages,
                        retrievalLanguages,
                        sectionRoles,
                        missing
                ),
                missing
        );
    }

    public boolean hasObservedQueryPlan() {
        return !paperQueryTexts.isEmpty()
                || (!locationQueryPlans.isEmpty()
                && locationQueryPlans.stream().anyMatch(LocationQueryPlan::isComplete))
                || (!locationQueryTexts.isEmpty()
                && !locationIntents.isEmpty()
                && !sourceLanguages.isEmpty()
                && !retrievalLanguages.isEmpty()
                && !sectionRoles.isEmpty());
    }

    private static String planningStatus(List<String> paperQueryTexts,
                                         List<String> locationQueryTexts,
                                         List<String> locationIntents,
                                         List<String> sourceLanguages,
                                         List<String> retrievalLanguages,
                                         List<String> sectionRoles,
                                         List<String> missing) {
        if (missing != null && missing.contains("location_query_plan")) {
            return "typed_location_query_plan_missing";
        }
        if (!empty(locationQueryTexts)
                && !empty(locationIntents)
                && !empty(sourceLanguages)
                && !empty(retrievalLanguages)
                && !empty(sectionRoles)) {
            return "typed_location_query_plan_observed";
        }
        if (!empty(paperQueryTexts)) {
            return "paper_query_observed";
        }
        return "not_planned";
    }

    private static boolean empty(List<String> values) {
        return values == null || values.stream().noneMatch(value -> value != null && !value.isBlank());
    }

    private static List<String> dedupe(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                deduped.add(value.trim());
            }
        }
        return List.copyOf(deduped);
    }

    public record LocationQueryPlan(
            String queryText,
            String intent,
            String sourceLanguage,
            String retrievalLanguage,
            List<String> sectionRoles,
            List<String> locationTypes
    ) {
        public LocationQueryPlan {
            queryText = queryText == null ? "" : queryText.trim();
            intent = intent == null ? "" : intent.trim();
            sourceLanguage = sourceLanguage == null ? "" : sourceLanguage.trim();
            retrievalLanguage = retrievalLanguage == null ? "" : retrievalLanguage.trim();
            sectionRoles = dedupe(sectionRoles);
            locationTypes = dedupe(locationTypes);
        }

        public boolean isComplete() {
            return !queryText.isBlank()
                    && !intent.isBlank()
                    && !sourceLanguage.isBlank()
                    && !retrievalLanguage.isBlank()
                    && !sectionRoles.isEmpty();
        }
    }
}
