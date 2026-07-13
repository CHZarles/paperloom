package io.github.chzarles.paperloom.service;

public record ToolProgressEvent(
        String type,
        String toolName
) {
    public ToolProgressEvent {
        type = type == null || type.isBlank() ? "calling_tool" : type.trim();
        toolName = toolName == null ? "" : toolName.trim();
    }
}
