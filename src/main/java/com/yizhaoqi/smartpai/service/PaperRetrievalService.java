package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PaperRetrievalService {

    private final PaperQueryPlanner paperQueryPlanner;
    private final HybridSearchService hybridSearchService;

    public PaperRetrievalService(PaperQueryPlanner paperQueryPlanner,
                                 HybridSearchService hybridSearchService) {
        this.paperQueryPlanner = paperQueryPlanner;
        this.hybridSearchService = hybridSearchService;
    }

    public RetrievalResult retrieve(String query, String userId, int topK) {
        PaperQueryPlanner.RetrievalPlan plan = paperQueryPlanner.plan(query);
        Map<String, SearchResult> uniqueResults = new LinkedHashMap<>();
        Map<String, Integer> routeHitCounts = new LinkedHashMap<>();

        for (String plannedQuery : plan.queryTexts()) {
            List<SearchResult> results = hybridSearchService.searchWithPermission(plannedQuery, userId, topK);
            routeHitCounts.put(plannedQuery, results.size());
            for (SearchResult result : results) {
                if (result.getPaperId() == null || result.getChunkId() == null) {
                    continue;
                }
                result.setRetrievalQuery(plannedQuery);
                result.setOriginalQuery(plan.originalQuery());
                result.setRetrievalRoute(plannedQuery.equals(plan.normalizedQuery()) ? "HYBRID" : "EXPANDED_HYBRID");
                result.setIntent(plan.intent().name());
                result.setRankReason(rankReason(plan, result));
                uniqueResults.putIfAbsent(result.getPaperId() + ":" + result.getChunkId(), result);
            }
        }

        List<SearchResult> ranked = new ArrayList<>(uniqueResults.values());
        ranked.sort(Comparator
                .comparingDouble((SearchResult result) -> rerankScore(plan, result)).reversed()
                .thenComparing(result -> result.getScore() == null ? 0.0d : result.getScore(), Comparator.reverseOrder()));
        if (ranked.size() > topK) {
            ranked = ranked.subList(0, topK);
        }
        return new RetrievalResult(plan, ranked, routeHitCounts);
    }

    private double rerankScore(PaperQueryPlanner.RetrievalPlan plan, SearchResult result) {
        double score = result.getScore() == null ? 0.0d : result.getScore();
        String sourceKind = upper(result.getSourceKind());
        String evidenceRole = upper(result.getEvidenceRole());
        String section = lower(result.getSectionTitle());
        if (plan.intent() == PaperQueryPlanner.RetrievalIntent.EXPERIMENT_RESULT) {
            if ("TABLE".equals(sourceKind) || "CHART".equals(sourceKind)) {
                score += 3.0d;
            } else if ("FIGURE".equals(sourceKind)) {
                score += 2.0d;
            }
            if ("EXPERIMENT_RESULT".equals(evidenceRole) || "FIGURE_CAPTION".equals(evidenceRole)) {
                score += 1.5d;
            }
            if (containsAny(section, "experiment", "evaluation", "results", "dataset", "appendix")) {
                score += 1.0d;
            }
            String text = lower(result.getTextContent());
            if (containsAny(text, "experiment", "accuracy", "evaluation", "benchmark", "table", "chart")) {
                score += 0.8d;
            }
        }
        return score;
    }

    private String rankReason(PaperQueryPlanner.RetrievalPlan plan, SearchResult result) {
        if (plan.intent() == PaperQueryPlanner.RetrievalIntent.EXPERIMENT_RESULT) {
            return "experiment-intent:" + nullToText(result.getSourceKind()) + ":" + nullToText(result.getEvidenceRole());
        }
        return plan.intent().name().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null) {
            return false;
        }
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private String nullToText(String value) {
        return value == null ? "-" : value;
    }

    public record RetrievalResult(
            PaperQueryPlanner.RetrievalPlan plan,
            List<SearchResult> results,
            Map<String, Integer> routeHitCounts
    ) {
        public List<String> attemptedQueries() {
            return plan.queryTexts();
        }

        public int finalHitCount() {
            return results == null ? 0 : results.size();
        }
    }
}
