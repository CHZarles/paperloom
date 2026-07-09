package com.yizhaoqi.smartpai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ProductTurnResult(
        String finalAnswerMarkdown,
        AnswerEnvelope envelope,
        List<Map<String, Object>> references,
        List<ToolProgressEvent> progressEvents,
        List<Map<String, Object>> productStateItems,
        ReadingTurnArtifacts readingArtifacts,
        ReadingStatePatch readingStatePatch,
        ReadingResearchTrace researchTrace,
        ProductStopReason stopReason,
        ProductResultStatus resultStatus
) {
    public ProductTurnResult(String finalAnswerMarkdown,
                             AnswerEnvelope envelope,
                             List<Map<String, Object>> references,
                             List<ToolProgressEvent> progressEvents,
                             ProductStopReason stopReason,
                             ProductResultStatus resultStatus) {
        this(finalAnswerMarkdown, envelope, references, progressEvents, List.of(), null, null, null, stopReason, resultStatus);
    }

    public ProductTurnResult(String finalAnswerMarkdown,
                             AnswerEnvelope envelope,
                             List<Map<String, Object>> references,
                             List<ToolProgressEvent> progressEvents,
                             List<Map<String, Object>> productStateItems,
                             ProductStopReason stopReason,
                             ProductResultStatus resultStatus) {
        this(finalAnswerMarkdown, envelope, references, progressEvents, productStateItems, null, null, null, stopReason, resultStatus);
    }

    public ProductTurnResult(String finalAnswerMarkdown,
                             AnswerEnvelope envelope,
                             List<Map<String, Object>> references,
                             List<ToolProgressEvent> progressEvents,
                             List<Map<String, Object>> productStateItems,
                             ReadingTurnArtifacts readingArtifacts,
                             ReadingStatePatch readingStatePatch,
                             ProductStopReason stopReason,
                             ProductResultStatus resultStatus) {
        this(finalAnswerMarkdown, envelope, references, progressEvents, productStateItems, readingArtifacts,
                readingStatePatch, null, stopReason, resultStatus);
    }

    public ProductTurnResult {
        finalAnswerMarkdown = finalAnswerMarkdown == null ? "" : finalAnswerMarkdown.trim();
        references = references == null ? List.of() : List.copyOf(references);
        progressEvents = progressEvents == null ? List.of() : List.copyOf(progressEvents);
        productStateItems = copyMapList(productStateItems);
        readingArtifacts = readingArtifacts == null ? ReadingTurnArtifacts.empty("") : readingArtifacts;
        readingStatePatch = readingStatePatch == null ? ReadingStatePatch.empty() : readingStatePatch;
        researchTrace = researchTrace == null ? ReadingResearchTrace.empty() : researchTrace;
        stopReason = stopReason == null ? ProductStopReason.COMPLETED : stopReason;
        resultStatus = resultStatus == null ? ProductResultStatus.COMPLETED : resultStatus;
    }

    private static List<Map<String, Object>> copyMapList(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copies = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            copies.add(new LinkedHashMap<>(item));
        }
        return List.copyOf(copies);
    }
}
