package com.yizhaoqi.smartpai.eval;

import java.util.List;

public record LitSearchBenchmarkCase(
        String id,
        String taskType,
        String querySet,
        String query,
        int specificity,
        int quality,
        List<String> goldCorpusIds
) {
    public LitSearchBenchmarkCase {
        taskType = taskType == null || taskType.isBlank() ? "LITSEARCH_RETRIEVAL" : taskType;
        querySet = querySet == null ? "" : querySet;
        query = query == null ? "" : query;
        goldCorpusIds = goldCorpusIds == null ? List.of() : List.copyOf(goldCorpusIds);
    }
}
