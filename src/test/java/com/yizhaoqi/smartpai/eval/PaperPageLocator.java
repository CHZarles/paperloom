package com.yizhaoqi.smartpai.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PaperPageLocator {

    private PaperPageLocator() {
    }

    public static List<PaperPageHit> rank(String query, List<PaperPageDocument> pages, int topK) {
        return rank(query, pages, topK, RankingOptions.defaults());
    }

    public static List<PaperPageHit> rank(String query,
                                          List<PaperPageDocument> pages,
                                          int topK,
                                          RankingOptions options) {
        Set<String> queryTokens = queryTokens(query);
        RankingOptions effectiveOptions = options == null ? RankingOptions.defaults() : options;
        return (pages == null ? List.<PaperPageDocument>of() : pages).stream()
                .map(page -> score(queryTokens, page, effectiveOptions))
                .sorted(Comparator
                        .comparingDouble(PaperPageHit::score).reversed()
                        .thenComparing(hit -> hit.page().paperId())
                        .thenComparingInt(hit -> hit.page().pageNumber()))
                .limit(Math.max(0, topK))
                .toList();
    }

    public static List<PaperPageDocument> expandNeighbors(PaperPageDocument center,
                                                          List<PaperPageDocument> pages,
                                                          int radius) {
        if (center == null) {
            return List.of();
        }
        int effectiveRadius = Math.max(0, radius);
        return (pages == null ? List.<PaperPageDocument>of() : pages).stream()
                .filter(page -> center.paperId().equals(page.paperId()))
                .filter(page -> Math.abs(page.pageNumber() - center.pageNumber()) <= effectiveRadius)
                .sorted(Comparator.comparingInt(PaperPageDocument::pageNumber))
                .toList();
    }

    private static PaperPageHit score(Set<String> queryTokens,
                                      PaperPageDocument page,
                                      RankingOptions options) {
        double score = 0.0d;
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        Set<String> titleTokens = tokens(page.paperTitle());
        Set<String> textTokens = tokens(page.pageText());
        for (String token : queryTokens) {
            if (titleTokens.contains(token)) {
                score += 2.0d;
                reasons.add("title");
            }
            if (textTokens.contains(token)) {
                score += 1.0d;
                reasons.add("text:" + token);
            }
        }
        for (String section : page.sectionTitles()) {
            Set<String> sectionTokens = tokens(section);
            if (!disjoint(queryTokens, sectionTokens)) {
                score += 2.5d;
                reasons.add("section:" + section);
            }
        }
        for (String sourceKind : page.sourceKinds()) {
            String normalized = sourceKind == null ? "" : sourceKind.trim().toUpperCase(Locale.ROOT);
            if (normalized.isBlank()) {
                continue;
            }
            if (queryTokens.contains(normalized.toLowerCase(Locale.ROOT))) {
                score += 2.0d;
                reasons.add("source:" + normalized);
            }
        }
        if (options.targetTermBoost()) {
            score += targetScore(queryTokens, titleTokens, textTokens, page, reasons);
        }
        if (!page.tableIds().isEmpty() && queryTokens.contains("table")) {
            score += 1.0d;
            reasons.add("table");
        }
        if (!page.figureIds().isEmpty() && queryTokens.contains("figure")) {
            score += 1.0d;
            reasons.add("figure");
        }
        return new PaperPageHit(page, score, List.copyOf(reasons));
    }

    private static double targetScore(Set<String> queryTokens,
                                      Set<String> titleTokens,
                                      Set<String> textTokens,
                                      PaperPageDocument page,
                                      LinkedHashSet<String> reasons) {
        double score = 0.0d;
        score += targetScore(queryTokens, page, union(titleTokens, textTokens), reasons,
                Set.of("baseline", "baselines"),
                Set.of("baseline", "baselines"),
                8.0d);
        score += targetScore(queryTokens, page, union(titleTokens, textTokens), reasons,
                Set.of("dataset", "datasets", "data", "corpus", "corpora"),
                Set.of("dataset", "datasets", "data", "corpus", "corpora", "benchmark", "benchmarks"),
                4.0d);
        score += targetScore(queryTokens, page, union(titleTokens, textTokens), reasons,
                Set.of("model", "models", "method", "methods", "algorithm", "algorithms", "predictive"),
                Set.of("model", "models", "method", "methods", "algorithm", "algorithms",
                        "architecture", "framework", "approach", "approaches"),
                4.0d);
        score += targetScore(queryTokens, page, union(titleTokens, textTokens), reasons,
                Set.of("annotation", "annotations", "crowdsourcing", "crowd", "workers", "platform"),
                Set.of("annotation", "annotations", "annotator", "annotators", "crowdsourcing",
                        "crowd", "workers", "platform", "manual"),
                4.0d);
        score += targetScore(queryTokens, page, union(titleTokens, textTokens), reasons,
                Set.of("performance", "accuracy", "f1"),
                Set.of("performance", "accuracy", "f1", "score", "scores", "result", "results"),
                3.0d);
        return score;
    }

    private static double targetScore(Set<String> queryTokens,
                                      PaperPageDocument page,
                                      Set<String> pageTokens,
                                      LinkedHashSet<String> reasons,
                                      Set<String> triggers,
                                      Set<String> targetTerms,
                                      double weight) {
        if (disjoint(queryTokens, triggers)) {
            return 0.0d;
        }
        Set<String> sectionTokens = new LinkedHashSet<>();
        for (String section : page.sectionTitles()) {
            sectionTokens.addAll(tokens(section));
        }
        int matches = 0;
        for (String targetTerm : targetTerms) {
            if (pageTokens.contains(targetTerm) || sectionTokens.contains(targetTerm)) {
                matches++;
                reasons.add("target:" + targetTerm);
            }
        }
        return matches == 0 ? 0.0d : weight + Math.min(4, matches);
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.addAll(left == null ? Set.of() : left);
        values.addAll(right == null ? Set.of() : right);
        return values;
    }

    private static boolean disjoint(Set<String> left, Set<String> right) {
        for (String value : left) {
            if (right.contains(value)) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> queryTokens(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>(tokens(query));
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (lower.contains("实验") || lower.contains("experiment") || lower.contains("evaluation")) {
            tokens.add("experiment");
            tokens.add("experiments");
            tokens.add("evaluation");
            tokens.add("results");
        }
        if (lower.contains("噪声") || lower.contains("noise")) {
            tokens.add("noise");
            tokens.add("increasing");
            tokens.add("high");
        }
        if (lower.contains("表") || lower.contains("table")) {
            tokens.add("table");
        }
        if (lower.contains("图") || lower.contains("figure")) {
            tokens.add("figure");
        }
        return tokens;
    }

    private static Set<String> tokens(String text) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHan}]+", " ");
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    public record RankingOptions(boolean targetTermBoost) {
        public static RankingOptions defaults() {
            return new RankingOptions(false);
        }

        public static RankingOptions withTargetTermBoost() {
            return new RankingOptions(true);
        }
    }
}
