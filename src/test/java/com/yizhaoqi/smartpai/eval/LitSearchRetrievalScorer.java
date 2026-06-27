package com.yizhaoqi.smartpai.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LitSearchRetrievalScorer {

    private LitSearchRetrievalScorer() {
    }

    public static Map<String, Double> score(List<LitSearchBenchmarkCase> cases,
                                            Map<String, List<String>> retrievedCorpusIdsByCaseId) {
        List<LitSearchBenchmarkCase> safeCases = cases == null ? List.of() : cases;
        Map<String, List<String>> safeRetrieved = retrievedCorpusIdsByCaseId == null ? Map.of() : retrievedCorpusIdsByCaseId;
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("recallAt5", averageRecallAt(safeCases, safeRetrieved, 5));
        metrics.put("recallAt20", averageRecallAt(safeCases, safeRetrieved, 20));
        metrics.put("mrr", meanReciprocalRank(safeCases, safeRetrieved));
        return metrics;
    }

    private static double averageRecallAt(List<LitSearchBenchmarkCase> cases,
                                          Map<String, List<String>> retrievedCorpusIdsByCaseId,
                                          int k) {
        return cases.stream()
                .mapToDouble(testCase -> recallAt(testCase.goldCorpusIds(), retrievedCorpusIdsByCaseId.get(testCase.id()), k))
                .average()
                .orElse(0.0d);
    }

    private static double recallAt(List<String> goldCorpusIds, List<String> retrievedCorpusIds, int k) {
        List<String> safeGold = goldCorpusIds == null ? List.of() : goldCorpusIds;
        if (safeGold.isEmpty()) {
            return 0.0d;
        }
        List<String> safeRetrieved = retrievedCorpusIds == null ? List.of() : retrievedCorpusIds;
        long hits = safeGold.stream()
                .filter(gold -> safeRetrieved.stream().limit(k).anyMatch(gold::equals))
                .count();
        return (double) hits / safeGold.size();
    }

    private static double meanReciprocalRank(List<LitSearchBenchmarkCase> cases,
                                             Map<String, List<String>> retrievedCorpusIdsByCaseId) {
        return cases.stream()
                .mapToDouble(testCase -> reciprocalRank(testCase.goldCorpusIds(), retrievedCorpusIdsByCaseId.get(testCase.id())))
                .average()
                .orElse(0.0d);
    }

    private static double reciprocalRank(List<String> goldCorpusIds, List<String> retrievedCorpusIds) {
        List<String> safeGold = goldCorpusIds == null ? List.of() : goldCorpusIds;
        List<String> safeRetrieved = retrievedCorpusIds == null ? List.of() : retrievedCorpusIds;
        for (int index = 0; index < safeRetrieved.size(); index++) {
            if (safeGold.contains(safeRetrieved.get(index))) {
                return 1.0d / (index + 1);
            }
        }
        return 0.0d;
    }
}
