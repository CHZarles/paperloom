package com.yizhaoqi.smartpai.service;

import java.util.List;
import java.util.Map;

public record ProductTurnResult(
        String finalAnswerMarkdown,
        AnswerEnvelope envelope,
        List<Map<String, Object>> references,
        List<ToolProgressEvent> progressEvents,
        ProductStopReason stopReason,
        ProductResultStatus resultStatus
) {
    public ProductTurnResult {
        finalAnswerMarkdown = finalAnswerMarkdown == null ? "" : finalAnswerMarkdown.trim();
        references = references == null ? List.of() : List.copyOf(references);
        progressEvents = progressEvents == null ? List.of() : List.copyOf(progressEvents);
        stopReason = stopReason == null ? ProductStopReason.COMPLETED : stopReason;
        resultStatus = resultStatus == null ? ProductResultStatus.COMPLETED : resultStatus;
    }
}
