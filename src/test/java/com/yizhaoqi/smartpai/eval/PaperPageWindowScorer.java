package com.yizhaoqi.smartpai.eval;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PaperPageWindowScorer {

    private PaperPageWindowScorer() {
    }

    public static Map<String, Double> score(Map<String, List<String>> goldPageKeysByCase,
                                            Map<String, List<PaperPageWindow>> windowsByCase,
                                            int... recallKs) {
        Map<String, List<String>> safeGold = goldPageKeysByCase == null ? Map.of() : goldPageKeysByCase;
        Map<String, List<PaperPageWindow>> safeWindows = windowsByCase == null ? Map.of() : windowsByCase;
        int[] ks = recallKs == null || recallKs.length == 0 ? new int[]{1, 3} : recallKs;
        Map<String, Double> metrics = zeroMetrics(ks);
        if (safeGold.isEmpty()) {
            return metrics;
        }

        Map<Integer, Double> recallTotals = new LinkedHashMap<>();
        Map<Integer, Double> positiveRecallTotals = new LinkedHashMap<>();
        for (int k : ks) {
            recallTotals.put(Math.max(1, k), 0.0d);
            positiveRecallTotals.put(Math.max(1, k), 0.0d);
        }
        double reciprocalRankTotal = 0.0d;
        double positiveReciprocalRankTotal = 0.0d;
        for (Map.Entry<String, List<String>> entry : safeGold.entrySet()) {
            Set<String> gold = normalizeGold(entry.getValue());
            List<PaperPageWindow> windows = safeWindows.getOrDefault(entry.getKey(), List.of());
            List<Set<String>> retrievedWindows = windows.stream()
                    .map(PaperPageWindowScorer::pageKeySet)
                    .toList();
            List<Set<String>> positiveWindows = windows.stream()
                    .filter(window -> window != null && window.score() > 0.0d)
                    .map(PaperPageWindowScorer::pageKeySet)
                    .toList();
            for (int k : recallTotals.keySet()) {
                recallTotals.compute(k, (ignored, total) -> total + recallAt(gold, retrievedWindows, k));
                positiveRecallTotals.compute(k, (ignored, total) -> total + recallAt(gold, positiveWindows, k));
            }
            reciprocalRankTotal += reciprocalRank(gold, retrievedWindows);
            positiveReciprocalRankTotal += reciprocalRank(gold, positiveWindows);
        }

        double caseCount = safeGold.size();
        for (Map.Entry<Integer, Double> entry : recallTotals.entrySet()) {
            metrics.put("windowRecallAt" + entry.getKey(), entry.getValue() / caseCount);
        }
        for (Map.Entry<Integer, Double> entry : positiveRecallTotals.entrySet()) {
            metrics.put("positiveWindowRecallAt" + entry.getKey(), entry.getValue() / caseCount);
        }
        metrics.put("windowMrr", reciprocalRankTotal / caseCount);
        metrics.put("positiveWindowMrr", positiveReciprocalRankTotal / caseCount);
        return metrics;
    }

    private static Map<String, Double> zeroMetrics(int[] ks) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        for (int k : ks) {
            metrics.put("windowRecallAt" + Math.max(1, k), 0.0d);
            metrics.put("positiveWindowRecallAt" + Math.max(1, k), 0.0d);
        }
        metrics.put("windowMrr", 0.0d);
        metrics.put("positiveWindowMrr", 0.0d);
        return metrics;
    }

    private static Set<String> pageKeySet(PaperPageWindow window) {
        return new LinkedHashSet<>(window == null ? List.of() : window.pageKeys());
    }

    private static Set<String> normalizeGold(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return normalized;
    }

    private static double recallAt(Set<String> gold, List<Set<String>> retrievedWindows, int k) {
        if (gold.isEmpty()) {
            return 0.0d;
        }
        int limit = Math.min(Math.max(1, k), retrievedWindows.size());
        long hits = gold.stream()
                .filter(goldPage -> retrievedWindows.subList(0, limit).stream()
                        .anyMatch(windowPages -> windowPages.contains(goldPage)))
                .count();
        return (double) hits / gold.size();
    }

    private static double reciprocalRank(Set<String> gold, List<Set<String>> retrievedWindows) {
        if (gold.isEmpty()) {
            return 0.0d;
        }
        for (int i = 0; i < retrievedWindows.size(); i++) {
            Set<String> windowPages = retrievedWindows.get(i);
            if (gold.stream().anyMatch(windowPages::contains)) {
                return 1.0d / (i + 1);
            }
        }
        return 0.0d;
    }
}
