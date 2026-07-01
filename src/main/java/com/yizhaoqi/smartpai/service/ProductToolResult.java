package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public record ProductToolResult(
        String toolName,
        boolean success,
        Map<String, Object> data,
        ProductToolEffect effect,
        Map<String, Map<String, Object>> evidencePayloads
) {
    public ProductToolResult(String toolName,
                             boolean success,
                             Map<String, Object> data,
                             ProductToolEffect effect) {
        this(toolName, success, data, effect, Map.of());
    }

    public ProductToolResult {
        toolName = toolName == null ? "" : toolName.trim();
        data = data == null ? Map.of() : Map.copyOf(data);
        effect = effect == null ? ProductToolEffect.NONE : effect;
        evidencePayloads = evidencePayloads == null ? Map.of() : Map.copyOf(evidencePayloads);
    }

    public String contentJson(ObjectMapper objectMapper) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", toolName);
        payload.put("success", success);
        payload.put("effect", effect.name());
        payload.put("data", data);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            return "{\"success\":false,\"error\":\"tool_result_json_failed\"}";
        }
    }
}
