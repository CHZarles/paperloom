package io.github.chzarles.paperloom.service;

import java.util.List;

public record SourceScope(
        ScopeMode mode,
        List<String> paperIds
) {
    public SourceScope {
        mode = mode == null ? ScopeMode.AUTO_SOURCE : mode;
        paperIds = paperIds == null ? List.of() : paperIds.stream()
                .filter(paperId -> paperId != null && !paperId.isBlank())
                .distinct()
                .toList();
    }

    public static SourceScope auto() {
        return new SourceScope(ScopeMode.AUTO_SOURCE, List.of());
    }

    public static SourceScope manual(List<String> paperIds) {
        return new SourceScope(ScopeMode.MANUAL_SOURCE, paperIds);
    }
}
