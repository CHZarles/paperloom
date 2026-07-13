package io.github.chzarles.paperloom.service;

import java.util.Map;

public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> parameters
) {
    public ToolDefinition {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        parameters = parameters == null ? Map.of() : parameters;
    }
}
