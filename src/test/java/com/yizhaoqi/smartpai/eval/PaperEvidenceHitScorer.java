package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class PaperEvidenceHitScorer {

    private PaperEvidenceHitScorer() {
    }

    public static Map<String, Double> scoreChunkEvidence(List<RagBenchmarkCase> cases,
                                                         Map<String, List<SearchResult>> chunksByCase,
                                                         int... ks) {
        return scoreEvidence(cases, ks, (testCase, k) -> chunkText(
                chunksByCase == null ? List.of() : chunksByCase.getOrDefault(testCase.id(), List.of()),
                k
        ), "chunk");
    }

    public static Map<String, Double> scoreWindowEvidence(List<RagBenchmarkCase> cases,
                                                          Map<String, List<PaperPageInspection>> inspectionsByCase,
                                                          int... ks) {
        return scoreEvidence(cases, ks, (testCase, k) -> inspectionText(
                inspectionsByCase == null ? List.of() : inspectionsByCase.getOrDefault(testCase.id(), List.of()),
                k
        ), "window");
    }

    public static Map<String, Double> scoreAllChunkEvidence(List<RagBenchmarkCase> cases,
                                                            Map<String, List<SearchResult>> chunksByCase,
                                                            String prefix) {
        String metricPrefix = prefix == null || prefix.isBlank() ? "candidate" : prefix;
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put(metricPrefix + "EvidenceCaseCount", 0.0d);
        metrics.put(metricPrefix + "EvidenceHitRate", 0.0d);

        List<RagBenchmarkCase> scoredCases = (cases == null ? List.<RagBenchmarkCase>of() : cases).stream()
                .filter(testCase -> !usefulEvidencePatterns(testCase).isEmpty())
                .toList();
        metrics.put(metricPrefix + "EvidenceCaseCount", (double) scoredCases.size());
        if (scoredCases.isEmpty()) {
            return metrics;
        }

        long hits = scoredCases.stream()
                .filter(testCase -> evidenceMatches(
                        usefulEvidencePatterns(testCase),
                        chunkText(chunksByCase == null ? List.of() : chunksByCase.getOrDefault(testCase.id(), List.of()))
                ))
                .count();
        metrics.put(metricPrefix + "EvidenceHitRate", (double) hits / scoredCases.size());
        return metrics;
    }

    private static Map<String, Double> scoreEvidence(List<RagBenchmarkCase> cases,
                                                     int[] ks,
                                                     EvidenceTextProvider evidenceTextProvider,
                                                     String prefix) {
        int[] effectiveKs = ks == null || ks.length == 0 ? new int[]{1, 3} : ks;
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put(prefix + "EvidenceCaseCount", 0.0d);
        for (int k : effectiveKs) {
            metrics.put(prefix + "EvidenceHitAt" + Math.max(1, k), 0.0d);
        }

        List<RagBenchmarkCase> scoredCases = (cases == null ? List.<RagBenchmarkCase>of() : cases).stream()
                .filter(testCase -> !usefulEvidencePatterns(testCase).isEmpty())
                .toList();
        metrics.put(prefix + "EvidenceCaseCount", (double) scoredCases.size());
        if (scoredCases.isEmpty()) {
            return metrics;
        }

        for (int k : effectiveKs) {
            int limit = Math.max(1, k);
            long hits = scoredCases.stream()
                    .filter(testCase -> evidenceMatches(usefulEvidencePatterns(testCase), evidenceTextProvider.text(testCase, limit)))
                    .count();
            metrics.put(prefix + "EvidenceHitAt" + limit, (double) hits / scoredCases.size());
        }
        return metrics;
    }

    private static List<String> usefulEvidencePatterns(RagBenchmarkCase testCase) {
        return (testCase == null ? List.<String>of() : testCase.requiredEvidenceRegex()).stream()
                .map(pattern -> pattern == null ? "" : pattern.trim())
                .filter(PaperEvidenceHitScorer::isUsefulPattern)
                .toList();
    }

    private static boolean isUsefulPattern(String pattern) {
        return !pattern.isBlank()
                && !".".equals(pattern)
                && !".*".equals(pattern)
                && !".+".equals(pattern);
    }

    private static boolean evidenceMatches(List<String> patterns, String text) {
        for (String pattern : patterns) {
            if (!matches(pattern, text)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(String pattern, String value) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        String safeValue = value == null ? "" : value;
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL)
                    .matcher(safeValue)
                    .find();
        } catch (PatternSyntaxException ignored) {
            return safeValue.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
        }
    }

    private static String chunkText(List<SearchResult> chunks, int k) {
        return (chunks == null ? List.<SearchResult>of() : chunks).stream()
                .limit(Math.max(1, k))
                .map(PaperEvidenceHitScorer::chunkText)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static String chunkText(List<SearchResult> chunks) {
        return (chunks == null ? List.<SearchResult>of() : chunks).stream()
                .map(PaperEvidenceHitScorer::chunkText)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static String chunkText(SearchResult chunk) {
        if (chunk == null) {
            return "";
        }
        String text = chunk.getMatchedChunkText();
        if (text != null && !text.isBlank()) {
            return text;
        }
        return chunk.getTextContent() == null ? "" : chunk.getTextContent();
    }

    private static String inspectionText(List<PaperPageInspection> inspections, int k) {
        return (inspections == null ? List.<PaperPageInspection>of() : inspections).stream()
                .limit(Math.max(1, k))
                .map(PaperPageInspection::text)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    @FunctionalInterface
    private interface EvidenceTextProvider {
        String text(RagBenchmarkCase testCase, int k);
    }
}
