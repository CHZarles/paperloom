package com.yizhaoqi.smartpai.eval;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PaperPageLocatorScorer {

    private PaperPageLocatorScorer() {
    }

    public static Map<String, Double> score(Map<String, List<String>> goldPageKeysByCase,
                                            Map<String, List<PaperPageHit>> hitsByCase,
                                            int... recallKs) {
        Map<String, List<String>> safeGold = goldPageKeysByCase == null ? Map.of() : goldPageKeysByCase;
        Map<String, List<PaperPageHit>> safeHits = hitsByCase == null ? Map.of() : hitsByCase;
        Map<String, Double> metrics = new LinkedHashMap<>();
        int[] ks = recallKs == null || recallKs.length == 0 ? new int[]{1, 3} : recallKs;
        for (int k : ks) {
            metrics.put("pageRecallAt" + Math.max(1, k), 0.0d);
            metrics.put("positivePageRecallAt" + Math.max(1, k), 0.0d);
        }
        metrics.put("pageMrr", 0.0d);
        metrics.put("positivePageMrr", 0.0d);
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
            List<PaperPageHit> hits = safeHits.getOrDefault(entry.getKey(), List.of());
            List<String> retrieved = hits.stream()
                    .map(PaperPageLocatorScorer::pageKey)
                    .distinct()
                    .toList();
            List<String> positiveRetrieved = hits.stream()
                    .filter(hit -> hit != null && hit.score() > 0.0d)
                    .map(PaperPageLocatorScorer::pageKey)
                    .distinct()
                    .toList();
            for (int k : recallTotals.keySet()) {
                recallTotals.compute(k, (ignored, total) -> total + recallAt(gold, retrieved, k));
                positiveRecallTotals.compute(k, (ignored, total) -> total + recallAt(gold, positiveRetrieved, k));
            }
            reciprocalRankTotal += reciprocalRank(gold, retrieved);
            positiveReciprocalRankTotal += reciprocalRank(gold, positiveRetrieved);
        }

        double caseCount = safeGold.size();
        for (Map.Entry<Integer, Double> entry : recallTotals.entrySet()) {
            metrics.put("pageRecallAt" + entry.getKey(), entry.getValue() / caseCount);
        }
        for (Map.Entry<Integer, Double> entry : positiveRecallTotals.entrySet()) {
            metrics.put("positivePageRecallAt" + entry.getKey(), entry.getValue() / caseCount);
        }
        metrics.put("pageMrr", reciprocalRankTotal / caseCount);
        metrics.put("positivePageMrr", positiveReciprocalRankTotal / caseCount);
        return metrics;
    }

    public static String pageKey(PaperPageDocument page) {
        if (page == null) {
            return "";
        }
        return page.paperId() + ":" + page.pageNumber();
    }

    private static String pageKey(PaperPageHit hit) {
        return hit == null ? "" : pageKey(hit.page());
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

    private static double recallAt(Set<String> gold, List<String> retrieved, int k) {
        if (gold.isEmpty()) {
            return 0.0d;
        }
        int limit = Math.min(Math.max(1, k), retrieved.size());
        long hits = retrieved.subList(0, limit).stream()
                .filter(gold::contains)
                .count();
        return (double) hits / gold.size();
    }

    private static double reciprocalRank(Set<String> gold, List<String> retrieved) {
        if (gold.isEmpty()) {
            return 0.0d;
        }
        for (int i = 0; i < retrieved.size(); i++) {
            if (gold.contains(retrieved.get(i))) {
                return 1.0d / (i + 1);
            }
        }
        return 0.0d;
    }
}
