package com.yizhaoqi.smartpai.service;

public record TaskDecision(
        TaskType taskType,
        TaskOperation operation,
        String query,
        double confidence,
        String reason
) {
    public TaskDecision {
        taskType = taskType == null ? TaskType.CLARIFY : taskType;
        operation = operation == null ? defaultOperation(taskType) : operation;
        query = query == null ? "" : query.trim();
        confidence = Math.max(0d, Math.min(1d, confidence));
        reason = reason == null ? "" : reason.trim();
    }

    static TaskOperation defaultOperation(TaskType taskType) {
        return switch (taskType == null ? TaskType.CLARIFY : taskType) {
            case SMALLTALK -> TaskOperation.DIRECT_RESPONSE;
            case CLARIFY -> TaskOperation.ASK_CLARIFICATION;
            case LIBRARY_STATUS -> TaskOperation.COUNT_SEARCHABLE_PAPERS;
            case PAPER_DISCOVERY -> TaskOperation.SEARCH_PAPER_METADATA;
            case PAPER_QA, FOLLOW_UP -> TaskOperation.ANSWER_FROM_EVIDENCE;
            case REFERENCE_QA -> TaskOperation.INSPECT_REFERENCE;
        };
    }
}
