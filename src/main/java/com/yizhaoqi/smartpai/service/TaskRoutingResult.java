package com.yizhaoqi.smartpai.service;

public record TaskRoutingResult(
        TaskDecision decision,
        TaskRoutingFailure failure
) {
    public TaskRoutingResult {
        if ((decision == null) == (failure == null)) {
            throw new IllegalArgumentException("Exactly one of decision or failure is required");
        }
    }

    public static TaskRoutingResult routed(TaskDecision decision) {
        return new TaskRoutingResult(decision, null);
    }

    public static TaskRoutingResult failed(TaskRoutingFailure failure) {
        return new TaskRoutingResult(null, failure);
    }

    public boolean routed() {
        return decision != null;
    }

    public boolean failed() {
        return failure != null;
    }
}
