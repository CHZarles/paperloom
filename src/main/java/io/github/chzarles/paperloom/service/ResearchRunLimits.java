package io.github.chzarles.paperloom.service;

import java.util.LinkedHashMap;
import java.util.Map;

public record ResearchRunLimits(
        int maxWallClockMs,
        int maxModelVisibleToolChars,
        int maxHistoryChars
) {
    public static ResearchRunLimits resolve(ProductModelContext context) {
        return new ResearchRunLimits(600000, 16000, 32000);
    }

    public Map<String, Object> toWire() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema_version", "paperloom-run-limits/v1");
        value.put("max_wall_clock_ms", maxWallClockMs);
        value.put("max_model_visible_tool_chars", maxModelVisibleToolChars);
        value.put("max_history_chars", maxHistoryChars);
        return value;
    }
}
