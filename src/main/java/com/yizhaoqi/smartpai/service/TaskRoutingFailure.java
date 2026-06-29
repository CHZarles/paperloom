package com.yizhaoqi.smartpai.service;

import java.util.Map;

public record TaskRoutingFailure(
        ReasonCode reasonCode,
        String userMessage,
        String diagnostics,
        Map<String, Object> metadata
) {
    public TaskRoutingFailure {
        reasonCode = reasonCode == null ? ReasonCode.UNSUPPORTED_TASK : reasonCode;
        userMessage = userMessage == null || userMessage.isBlank()
                ? "我没有可靠判断这个问题应该使用哪种论文能力，因此不会执行检索。请换一种更明确的问法。"
                : userMessage.trim();
        diagnostics = diagnostics == null ? "" : diagnostics.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public enum ReasonCode {
        EMPTY_QUERY,
        LLM_UNAVAILABLE,
        INVALID_JSON,
        INVALID_TASK,
        LOW_CONFIDENCE,
        UNSUPPORTED_TASK
    }
}
