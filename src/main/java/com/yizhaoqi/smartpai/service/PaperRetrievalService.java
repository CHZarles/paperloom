package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class PaperRetrievalService {

    private final PaperQueryPlanner paperQueryPlanner;
    private final HybridSearchService hybridSearchService;
    private final ProductPaperCorpus productPaperCorpus;

    public PaperRetrievalService(PaperQueryPlanner paperQueryPlanner,
                                 HybridSearchService hybridSearchService) {
        this(paperQueryPlanner, hybridSearchService, null);
    }

    @Autowired
    public PaperRetrievalService(PaperQueryPlanner paperQueryPlanner,
                                 HybridSearchService hybridSearchService,
                                 ProductPaperCorpus productPaperCorpus) {
        this.paperQueryPlanner = paperQueryPlanner;
        this.hybridSearchService = hybridSearchService;
        this.productPaperCorpus = productPaperCorpus;
    }

    public RetrievalResult retrieve(String query, String userId, RetrievalBudget budget) {
        return retrieve(query, userId, budget, List.of());
    }

    public RetrievalResult retrieve(String query, String userId, RetrievalBudget budget, List<String> scopePaperIds) {
        return retrieve(query, userId, budget, scopePaperIds, null);
    }

    public RetrievalResult discoverPapers(String query, String userId, RetrievalBudget budget) {
        return discoverPapers(query, userId, budget, List.of());
    }

    public RetrievalResult discoverPapers(String query, String userId, RetrievalBudget budget, List<String> scopePaperIds) {
        return retrieve(query, userId, budget, scopePaperIds, PaperQueryPlanner.RetrievalIntent.LITERATURE_SEARCH);
    }

    private RetrievalResult retrieve(String query,
                                     String userId,
                                     RetrievalBudget budget,
                                     List<String> scopePaperIds,
                                     PaperQueryPlanner.RetrievalIntent forcedIntent) {
        RetrievalBudget effectiveBudget = budget == null ? RetrievalBudget.forQa() : budget;
        List<String> effectiveScopePaperIds = normalizeScopePaperIds(scopePaperIds);
        List<String> productPaperIds = resolveProductPaperIds(userId, effectiveScopePaperIds);
        PaperQueryPlanner.RetrievalPlan plan = forcedIntent == null
                ? paperQueryPlanner.plan(query)
                : paperQueryPlanner.plan(query, forcedIntent);
        if (productPaperCorpus != null && productPaperIds.isEmpty()) {
            return emptyResult(plan);
        }
        Map<String, SearchResult> uniqueResults = new LinkedHashMap<>();
        Map<String, Integer> routeHitCounts = new LinkedHashMap<>();
        int scannedCount = 0;
        StopReason combinedStopReason = StopReason.EXHAUSTED;

        for (String plannedQuery : plan.queryTexts()) {
            HybridSearchService.AdaptiveSearchResult searchResult =
                    searchForPlan(plan, plannedQuery, userId, effectiveBudget, productPaperIds);
            if (searchResult == null) {
                searchResult = new HybridSearchService.AdaptiveSearchResult(
                        List.of(),
                        0,
                        0,
                        0,
                        StopReason.NO_USABLE_EVIDENCE
                );
            }
            List<SearchResult> results = filterResultsByScope(searchResult.results(), productPaperIds);
            scannedCount += searchResult.scannedCount();
            combinedStopReason = mergeStopReason(combinedStopReason, searchResult.stopReason());
            for (SearchResult result : results) {
                if (result == null
                        || result.getPaperId() == null
                        || result.getChunkId() == null
                        || !EvidenceQuality.isUsable(result, effectiveBudget.minScore())) {
                    continue;
                }
                result.setRetrievalQuery(plannedQuery);
                result.setOriginalQuery(plan.originalQuery());
                result.setRetrievalRoute(retrievalRoute(plan, plannedQuery));
                result.setIntent(plan.intent().name());
                result.setRankReason(rankReason(plan, result));
                uniqueResults.putIfAbsent(result.getPaperId() + ":" + result.getChunkId(), result);
            }
            routeHitCounts.put(plannedQuery, uniqueResults.size());
        }

        List<SearchResult> ranked = new ArrayList<>(uniqueResults.values());
        ranked.sort(Comparator
                .comparingDouble((SearchResult result) -> rerankScore(plan, result)).reversed()
                .thenComparing(result -> result.getScore() == null ? 0.0d : result.getScore(), Comparator.reverseOrder()));
        if (plan.paperLevelSearch()) {
            ranked = collapseBestResultPerPaper(ranked);
        }
        RetrievalDiagnostics diagnostics = new RetrievalDiagnostics(
                scannedCount,
                ranked.size(),
                (int) ranked.stream().map(SearchResult::getPaperId).distinct().count(),
                ranked.isEmpty() ? StopReason.NO_USABLE_EVIDENCE : combinedStopReason
        );
        return new RetrievalResult(plan, ranked, routeHitCounts, diagnostics);
    }

    private List<String> resolveProductPaperIds(String userId, List<String> requestedScopePaperIds) {
        if (productPaperCorpus == null) {
            return requestedScopePaperIds;
        }
        SourceScope requestedScope = requestedScopePaperIds == null || requestedScopePaperIds.isEmpty()
                ? SourceScope.auto()
                : SourceScope.manual(requestedScopePaperIds);
        return productPaperCorpus.resolveAccessibleSearchablePaperIds(userId, requestedScope).paperIds();
    }

    private RetrievalResult emptyResult(PaperQueryPlanner.RetrievalPlan plan) {
        return new RetrievalResult(
                plan,
                List.of(),
                Map.of(),
                new RetrievalDiagnostics(0, 0, 0, StopReason.NO_USABLE_EVIDENCE)
        );
    }

    private HybridSearchService.AdaptiveSearchResult searchForPlan(PaperQueryPlanner.RetrievalPlan plan,
                                                                   String plannedQuery,
                                                                   String userId,
                                                                   RetrievalBudget budget,
                                                                   List<String> scopePaperIds) {
        if (!plan.paperLevelSearch()) {
            return filterAdaptiveResultByScope(
                    hybridSearchService.adaptiveSearchWithPermission(plannedQuery, userId, budget, scopePaperIds),
                    scopePaperIds
            );
        }
        HybridSearchService.AdaptiveSearchResult paperCandidates =
                filterAdaptiveResultByScope(
                        hybridSearchService.searchPaperCandidatesWithPermission(plannedQuery, userId, budget, scopePaperIds),
                        scopePaperIds
                );
        List<String> candidatePaperIds = paperCandidateIds(paperCandidates);
        if (candidatePaperIds.isEmpty()) {
            return filterAdaptiveResultByScope(
                    hybridSearchService.adaptiveSearchWithPermission(plannedQuery, userId, budget, scopePaperIds),
                    scopePaperIds
            );
        }
        HybridSearchService.AdaptiveSearchResult scopedEvidence =
                filterAdaptiveResultByScope(
                        hybridSearchService.adaptiveSearchWithPermission(plannedQuery, userId, budget, candidatePaperIds),
                        candidatePaperIds
                );
        if (scopedEvidence == null) {
            scopedEvidence = new HybridSearchService.AdaptiveSearchResult(
                    List.of(),
                    0,
                    0,
                    0,
                    StopReason.NO_USABLE_EVIDENCE
            );
        }
        List<SearchResult> combinedResults = scopedEvidenceWithMissingPaperCandidates(paperCandidates, scopedEvidence);
        return new HybridSearchService.AdaptiveSearchResult(
                combinedResults,
                paperCandidates.scannedCount() + scopedEvidence.scannedCount(),
                combinedResults.size(),
                (int) combinedResults.stream()
                        .map(SearchResult::getPaperId)
                        .filter(paperId -> paperId != null && !paperId.isBlank())
                        .distinct()
                        .count(),
                mergeStopReason(paperCandidates.stopReason(), scopedEvidence.stopReason())
        );
    }

    private List<SearchResult> scopedEvidenceWithMissingPaperCandidates(
            HybridSearchService.AdaptiveSearchResult paperCandidates,
            HybridSearchService.AdaptiveSearchResult scopedEvidence
    ) {
        List<SearchResult> combined = new ArrayList<>();
        for (SearchResult candidate : paperCandidates == null ? List.<SearchResult>of() : paperCandidates.results()) {
            if (candidate == null || candidate.getPaperId() == null || candidate.getPaperId().isBlank()) {
                continue;
            }
            combined.add(candidate);
        }
        for (SearchResult evidence : scopedEvidence == null ? List.<SearchResult>of() : scopedEvidence.results()) {
            combined.add(evidence);
        }
        return combined;
    }

    private HybridSearchService.AdaptiveSearchResult filterAdaptiveResultByScope(
            HybridSearchService.AdaptiveSearchResult searchResult,
            List<String> scopePaperIds
    ) {
        if (searchResult == null) {
            return null;
        }
        List<SearchResult> filteredResults = filterResultsByScope(searchResult.results(), scopePaperIds);
        if (filteredResults.size() == searchResult.results().size()) {
            return searchResult;
        }
        PaperRetrievalService.StopReason stopReason = filteredResults.isEmpty()
                ? StopReason.NO_USABLE_EVIDENCE
                : searchResult.stopReason();
        return new HybridSearchService.AdaptiveSearchResult(
                filteredResults,
                searchResult.scannedCount(),
                filteredResults.size(),
                (int) filteredResults.stream().map(SearchResult::getPaperId).filter(id -> id != null && !id.isBlank()).distinct().count(),
                stopReason
        );
    }

    private List<SearchResult> filterResultsByScope(List<SearchResult> results, List<String> scopePaperIds) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<String> effectiveScopePaperIds = normalizeScopePaperIds(scopePaperIds);
        if (effectiveScopePaperIds.isEmpty()) {
            return results;
        }
        Set<String> allowedPaperIds = Set.copyOf(effectiveScopePaperIds);
        return results.stream()
                .filter(result -> result != null && allowedPaperIds.contains(result.getPaperId()))
                .toList();
    }

    private List<String> paperCandidateIds(HybridSearchService.AdaptiveSearchResult paperCandidates) {
        if (paperCandidates == null || paperCandidates.results().isEmpty()) {
            return List.of();
        }
        return paperCandidates.results().stream()
                .filter(result -> result != null && result.getPaperId() != null && !result.getPaperId().isBlank())
                .map(SearchResult::getPaperId)
                .distinct()
                .toList();
    }

    private List<SearchResult> collapseBestResultPerPaper(List<SearchResult> rankedResults) {
        Map<String, SearchResult> bestByPaper = new LinkedHashMap<>();
        for (SearchResult result : rankedResults == null ? List.<SearchResult>of() : rankedResults) {
            if (result == null || result.getPaperId() == null || result.getPaperId().isBlank()) {
                continue;
            }
            bestByPaper.putIfAbsent(result.getPaperId(), result);
        }
        return new ArrayList<>(bestByPaper.values());
    }

    private List<String> normalizeScopePaperIds(List<String> paperIds) {
        if (paperIds == null || paperIds.isEmpty()) {
            return List.of();
        }
        return paperIds.stream()
                .filter(paperId -> paperId != null && !paperId.isBlank())
                .distinct()
                .toList();
    }

    private double rerankScore(PaperQueryPlanner.RetrievalPlan plan, SearchResult result) {
        double score = result.getScore() == null ? 0.0d : result.getScore();
        String sourceKind = upper(result.getSourceKind());
        String evidenceRole = upper(result.getEvidenceRole());
        if (plan.intent() == PaperQueryPlanner.RetrievalIntent.LITERATURE_SEARCH) {
            String query = lower(plan.normalizedQuery());
            String title = lower(result.getPaperTitle());
            String evidence = lower(EvidenceQuality.bestEvidenceText(result));
            if ("PAPER".equals(sourceKind)
                    || "PAPER_METADATA".equals(evidenceRole)
                    || "PAPER_METADATA".equals(upper(result.getRetrievalMode()))) {
                score += 2.0d;
            }
            score += 2.5d * tokenCoverage(query, title);
            score += 1.4d * tokenCoverage(query, title + " " + evidence);
        }
        return score;
    }

    private String retrievalRoute(PaperQueryPlanner.RetrievalPlan plan, String plannedQuery) {
        if (plan.paperLevelSearch()) {
            return plannedQuery.equals(plan.normalizedQuery()) ? "PAPER_LEVEL" : "EXPANDED_PAPER_LEVEL";
        }
        return plannedQuery.equals(plan.normalizedQuery()) ? "HYBRID" : "EXPANDED_HYBRID";
    }

    private StopReason mergeStopReason(StopReason current, StopReason next) {
        if (next == null) {
            return current == null ? StopReason.EXHAUSTED : current;
        }
        if (next == StopReason.LATENCY_BUDGET || current == StopReason.LATENCY_BUDGET) {
            return StopReason.LATENCY_BUDGET;
        }
        if (next == StopReason.CONTEXT_BUDGET || current == StopReason.CONTEXT_BUDGET) {
            return StopReason.CONTEXT_BUDGET;
        }
        if (next == StopReason.PLATEAU || current == StopReason.PLATEAU) {
            return StopReason.PLATEAU;
        }
        if (next == StopReason.NO_USABLE_EVIDENCE) {
            return current == null ? StopReason.NO_USABLE_EVIDENCE : current;
        }
        return current == null || current == StopReason.NO_USABLE_EVIDENCE ? next : current;
    }

    private String rankReason(PaperQueryPlanner.RetrievalPlan plan, SearchResult result) {
        if (plan.intent() == PaperQueryPlanner.RetrievalIntent.LITERATURE_SEARCH) {
            return "literature-search:" + nullToText(result.getPaperTitle());
        }
        return plan.intent().name().toLowerCase(Locale.ROOT);
    }

    private double tokenCoverage(String query, String text) {
        if (query == null || query.isBlank() || text == null || text.isBlank()) {
            return 0.0d;
        }
        String[] queryTokens = query.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+");
        int usefulCount = 0;
        int hitCount = 0;
        String lowerText = text.toLowerCase(Locale.ROOT);
        for (String token : queryTokens) {
            if (token.length() < 3) {
                continue;
            }
            usefulCount++;
            if (lowerText.contains(token)) {
                hitCount++;
            }
        }
        return usefulCount == 0 ? 0.0d : (double) hitCount / usefulCount;
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
            Map<String, Integer> routeHitCounts,
            RetrievalDiagnostics diagnostics
    ) {
        public RetrievalResult(PaperQueryPlanner.RetrievalPlan plan,
                               List<SearchResult> results,
                               Map<String, Integer> routeHitCounts) {
            this(
                    plan,
                    results,
                    routeHitCounts,
                    new RetrievalDiagnostics(
                            results == null ? 0 : results.size(),
                            results == null ? 0 : results.size(),
                            results == null ? 0 : (int) results.stream().map(SearchResult::getPaperId).distinct().count(),
                            results == null || results.isEmpty() ? StopReason.NO_USABLE_EVIDENCE : StopReason.EXHAUSTED
                    )
            );
        }

        public List<String> attemptedQueries() {
            return plan.queryTexts();
        }

        public int finalHitCount() {
            return results == null ? 0 : results.size();
        }
    }

    public record RetrievalDiagnostics(
            int scannedCount,
            int acceptedEvidenceCount,
            int sourceCount,
            StopReason stopReason
    ) {
    }

    public enum StopReason {
        EXHAUSTED,
        PLATEAU,
        CONTEXT_BUDGET,
        LATENCY_BUDGET,
        NO_USABLE_EVIDENCE
    }
}
