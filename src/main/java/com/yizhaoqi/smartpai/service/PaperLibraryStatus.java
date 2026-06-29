package com.yizhaoqi.smartpai.service;

import java.util.List;

public record PaperLibraryStatus(
        int accessibleCount,
        int searchableCount,
        int parsingCount,
        int indexingCount,
        int failedCount,
        int selectedScopeCount,
        List<String> consistencyWarnings
) {
    public PaperLibraryStatus {
        accessibleCount = Math.max(0, accessibleCount);
        searchableCount = Math.max(0, searchableCount);
        parsingCount = Math.max(0, parsingCount);
        indexingCount = Math.max(0, indexingCount);
        failedCount = Math.max(0, failedCount);
        selectedScopeCount = Math.max(0, selectedScopeCount);
        consistencyWarnings = consistencyWarnings == null ? List.of() : List.copyOf(consistencyWarnings);
    }
}
