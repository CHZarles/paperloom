package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.EvidenceLedger;
import com.yizhaoqi.smartpai.service.EvidenceLedgerService;
import com.yizhaoqi.smartpai.service.PaperPageWindowService;
import com.yizhaoqi.smartpai.service.PaperRetrievalService;
import com.yizhaoqi.smartpai.service.RetrievalBudget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ServiceBackedPageWindowHarness {

    private final PaperRetrievalService paperRetrievalService;
    private final PaperPageWindowService pageWindowService;
    private final EvidenceLedgerService evidenceLedgerService;

    public ServiceBackedPageWindowHarness(PaperRetrievalService paperRetrievalService,
                                          PaperPageWindowService pageWindowService,
                                          EvidenceLedgerService evidenceLedgerService) {
        this.paperRetrievalService = paperRetrievalService;
        this.pageWindowService = pageWindowService;
        this.evidenceLedgerService = evidenceLedgerService;
    }

    public HarnessResult run(String query,
                             String userId,
                             RetrievalBudget budget,
                             List<String> scopePaperIds,
                             Options options) {
        RetrievalBudget effectiveBudget = budget == null ? RetrievalBudget.forQa() : budget;
        Options effectiveOptions = options == null ? Options.defaults() : options;
        List<String> effectiveScopePaperIds = scopePaperIds == null ? List.of() : scopePaperIds;
        PaperRetrievalService.RetrievalResult firstStage = shouldUseScopedPaperCandidates(effectiveOptions, effectiveScopePaperIds)
                ? null
                : paperRetrievalService.retrieve(
                query,
                userId,
                effectiveBudget,
                effectiveScopePaperIds
        );
        List<SearchResult> firstStageHits = firstStage == null ? List.of() : firstStage.results();
        List<SearchResult> candidateHits = shouldUseScopedPaperCandidates(effectiveOptions, effectiveScopePaperIds)
                ? scopedPaperHits(effectiveScopePaperIds)
                : firstStageHits;
        List<PaperPageDocument> candidatePages = PaperPageIndexBuilder.fromSearchResults(candidateHits);
        PaperPageLocatorQueryPlanner.PlannedQuery plannedQuery = plannedQuery(
                query,
                candidatePages,
                effectiveOptions.queryPlanner()
        );
        List<PaperPageWindow> windows = rankPages(
                        query,
                        plannedQuery,
                        candidatePages,
                        candidateHits,
                        effectiveOptions
                )
                .stream()
                .map(hit -> new PaperPageWindow(
                        hit.page(),
                        PaperPageLocator.expandNeighbors(hit.page(), candidatePages, effectiveOptions.windowRadius()),
                        hit.score(),
                        hit.reasons()
                ))
                .toList();

        List<PaperPageInspection> inspections = new ArrayList<>();
        Map<String, SearchResult> inspectedChunks = new LinkedHashMap<>();
        for (PaperPageWindow window : windows) {
            List<SearchResult> chunks = pageWindowService.inspectPageWindow(
                    window.centerPage().paperId(),
                    window.centerPage().pageNumber(),
                    effectiveOptions.windowRadius()
            );
            inspections.add(PaperPageInspection.from(window, chunks));
            for (SearchResult chunk : chunks == null ? List.<SearchResult>of() : chunks) {
                if (chunk == null || chunk.getPaperId() == null || chunk.getChunkId() == null) {
                    continue;
                }
                inspectedChunks.putIfAbsent(chunk.getPaperId() + ":" + chunk.getChunkId(), chunk);
            }
        }
        EvidenceLedger ledger = evidenceLedgerService.fromSearchResults(
                List.copyOf(inspectedChunks.values()),
                effectiveBudget
        );
        return new HarnessResult(
                firstStage,
                normalizedCandidateSource(effectiveOptions.candidateSource()),
                candidateHits,
                plannedQuery.expandedQuery(),
                plannedQuery.expansions(),
                windows,
                inspections,
                ledger
        );
    }

    private boolean shouldUseScopedPaperCandidates(Options options, List<String> scopePaperIds) {
        return "scoped-paper".equalsIgnoreCase(options.candidateSource())
                && scopePaperIds != null
                && !scopePaperIds.isEmpty();
    }

    private List<SearchResult> scopedPaperHits(List<String> scopePaperIds) {
        Map<String, SearchResult> hits = new LinkedHashMap<>();
        for (String paperId : scopePaperIds == null ? List.<String>of() : scopePaperIds) {
            for (SearchResult chunk : pageWindowService.inspectPaper(paperId)) {
                if (chunk == null || chunk.getPaperId() == null || chunk.getChunkId() == null) {
                    continue;
                }
                hits.putIfAbsent(chunk.getPaperId() + ":" + chunk.getChunkId(), chunk);
            }
        }
        return List.copyOf(hits.values());
    }

    private PaperPageLocatorQueryPlanner.PlannedQuery plannedQuery(String query,
                                                                   List<PaperPageDocument> pages,
                                                                   String queryPlanner) {
        String planner = queryPlanner == null ? "" : queryPlanner.trim().toLowerCase(Locale.ROOT);
        if ("scientific-qa".equals(planner)) {
            return PaperPageLocatorQueryPlanner.planScientificQa(query, pages);
        }
        if ("scientific-qa-targets".equals(planner) || "scientific-qa-target-rerank".equals(planner)
                || isChunkAwarePlanner(planner)
                || isDiverseWindowPlanner(planner)
                || isCenterDiverseWindowPlanner(planner)) {
            return PaperPageLocatorQueryPlanner.planScientificQa(query, pages);
        }
        if ("page-locator".equals(planner)) {
            return PaperPageLocatorQueryPlanner.plan(query, pages);
        }
        String rawQuery = query == null ? "" : query;
        return new PaperPageLocatorQueryPlanner.PlannedQuery(rawQuery, rawQuery, List.of());
    }

    private List<PaperPageHit> rankPages(String query,
                                         PaperPageLocatorQueryPlanner.PlannedQuery plannedQuery,
                                         List<PaperPageDocument> candidatePages,
                                         List<SearchResult> candidateHits,
                                         Options options) {
        List<PaperPageHit> pageHits = PaperPageLocator.rank(
                plannedQuery.expandedQuery(),
                candidatePages,
                candidatePages.size(),
                rankingOptions(options.queryPlanner())
        );
        if (isChunkAwarePlanner(options.queryPlanner())) {
            pageHits = rerankWithChunkScores(query, candidateHits, pageHits);
        }
        if (isDiverseWindowPlanner(options.queryPlanner())) {
            return selectDiverseWindows(pageHits, options.topK(), options.windowRadius() * 2);
        }
        if (isCenterDiverseWindowPlanner(options.queryPlanner())) {
            return selectDiverseWindows(pageHits, options.topK(), options.windowRadius());
        }
        return pageHits.stream()
                .limit(options.topK())
                .toList();
    }

    private List<PaperPageHit> rerankWithChunkScores(String query,
                                                     List<SearchResult> candidateHits,
                                                     List<PaperPageHit> pageHits) {
        Map<String, ChunkPageScore> chunkScores = chunkPageScores(query, candidateHits);
        return pageHits.stream()
                .map(hit -> withChunkScore(hit, chunkScores.get(pageKey(hit.page()))))
                .sorted(Comparator
                        .comparingDouble(PaperPageHit::score).reversed()
                        .thenComparing(hit -> hit.page().paperId())
                        .thenComparingInt(hit -> hit.page().pageNumber()))
                .toList();
    }

    private List<PaperPageHit> selectDiverseWindows(List<PaperPageHit> pageHits, int topK, int maxCenterDistance) {
        int effectiveTopK = Math.max(1, topK);
        int effectiveMaxCenterDistance = Math.max(0, maxCenterDistance);
        List<PaperPageHit> selected = new ArrayList<>();
        for (PaperPageHit hit : pageHits == null ? List.<PaperPageHit>of() : pageHits) {
            if (selected.size() >= effectiveTopK) {
                break;
            }
            if (overlapsAny(hit, selected, effectiveMaxCenterDistance)) {
                continue;
            }
            selected.add(withReason(hit, "diverse-window"));
        }
        if (selected.size() >= effectiveTopK) {
            return selected;
        }
        Set<String> selectedCenters = new LinkedHashSet<>();
        for (PaperPageHit hit : selected) {
            selectedCenters.add(pageKey(hit.page()));
        }
        for (PaperPageHit hit : pageHits == null ? List.<PaperPageHit>of() : pageHits) {
            if (selected.size() >= effectiveTopK) {
                break;
            }
            if (selectedCenters.add(pageKey(hit.page()))) {
                selected.add(hit);
            }
        }
        return selected;
    }

    private boolean overlapsAny(PaperPageHit hit, List<PaperPageHit> selected, int maxCenterDistance) {
        for (PaperPageHit existing : selected) {
            if (!existing.page().paperId().equals(hit.page().paperId())) {
                continue;
            }
            if (Math.abs(existing.page().pageNumber() - hit.page().pageNumber()) <= maxCenterDistance) {
                return true;
            }
        }
        return false;
    }

    private PaperPageHit withReason(PaperPageHit hit, String reason) {
        List<String> reasons = new ArrayList<>(hit.reasons());
        reasons.add(reason);
        return new PaperPageHit(hit.page(), hit.score(), reasons);
    }

    private PaperPageHit withChunkScore(PaperPageHit hit, ChunkPageScore chunkScore) {
        if (chunkScore == null || chunkScore.score() <= 0.0d) {
            return hit;
        }
        List<String> reasons = new ArrayList<>(hit.reasons());
        reasons.add("chunk:" + chunkScore.reasonToken());
        return new PaperPageHit(hit.page(), hit.score() + chunkScore.score() * 4.0d, reasons);
    }

    private Map<String, ChunkPageScore> chunkPageScores(String query, List<SearchResult> chunks) {
        Set<String> queryTokens = tokens(query);
        Map<String, ChunkPageScore> bestByPage = new HashMap<>();
        for (SearchResult chunk : chunks == null ? List.<SearchResult>of() : chunks) {
            if (chunk == null || chunk.getPaperId() == null || chunk.getPageNumber() == null) {
                continue;
            }
            ChunkPageScore score = chunkScore(queryTokens, chunk);
            if (score.score() <= 0.0d) {
                continue;
            }
            String key = chunk.getPaperId() + ":" + chunk.getPageNumber();
            ChunkPageScore previous = bestByPage.get(key);
            if (previous == null || score.score() > previous.score()) {
                bestByPage.put(key, score);
            }
        }
        return bestByPage;
    }

    private ChunkPageScore chunkScore(Set<String> queryTokens, SearchResult chunk) {
        Set<String> titleTokens = tokens(chunk.getPaperTitle());
        Set<String> sectionTokens = tokens(chunk.getSectionTitle());
        Set<String> textTokens = tokens(chunkText(chunk));
        double score = 0.0d;
        String bestToken = "";
        double bestTokenScore = 0.0d;
        for (String token : queryTokens) {
            double tokenScore = 0.0d;
            if (titleTokens.contains(token)) {
                tokenScore += 1.0d;
            }
            if (sectionTokens.contains(token)) {
                tokenScore += 2.0d;
            }
            if (textTokens.contains(token)) {
                tokenScore += 1.5d;
            }
            score += tokenScore;
            if (tokenScore > bestTokenScore) {
                bestTokenScore = tokenScore;
                bestToken = token;
            }
        }
        String sourceKind = chunk.getSourceKind() == null ? "" : chunk.getSourceKind().toLowerCase(Locale.ROOT);
        if (queryTokens.contains("table") && ("table".equals(sourceKind) || chunk.getTableId() != null)) {
            score += 1.5d;
            bestToken = bestToken.isBlank() ? "table" : bestToken;
        }
        if (queryTokens.contains("figure") && ("figure".equals(sourceKind) || chunk.getFigureId() != null)) {
            score += 1.5d;
            bestToken = bestToken.isBlank() ? "figure" : bestToken;
        }
        return new ChunkPageScore(score, bestToken.isBlank() ? "match" : bestToken);
    }

    private String chunkText(SearchResult chunk) {
        String matched = chunk.getMatchedChunkText();
        if (matched != null && !matched.isBlank()) {
            return matched;
        }
        return chunk.getTextContent() == null ? "" : chunk.getTextContent();
    }

    private String pageKey(PaperPageDocument page) {
        return page.paperId() + ":" + page.pageNumber();
    }

    private boolean isChunkAwarePlanner(String queryPlanner) {
        String planner = queryPlanner == null ? "" : queryPlanner.trim().toLowerCase(Locale.ROOT);
        return "scientific-qa-chunk-window".equals(planner) || "scientific-qa-chunk-rerank".equals(planner);
    }

    private boolean isDiverseWindowPlanner(String queryPlanner) {
        String planner = queryPlanner == null ? "" : queryPlanner.trim().toLowerCase(Locale.ROOT);
        return "scientific-qa-diverse-windows".equals(planner);
    }

    private boolean isCenterDiverseWindowPlanner(String queryPlanner) {
        String planner = queryPlanner == null ? "" : queryPlanner.trim().toLowerCase(Locale.ROOT);
        return "scientific-qa-center-diverse-windows".equals(planner);
    }

    private Set<String> tokens(String text) {
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

    private PaperPageLocator.RankingOptions rankingOptions(String queryPlanner) {
        String planner = queryPlanner == null ? "" : queryPlanner.trim().toLowerCase(Locale.ROOT);
        if ("scientific-qa-targets".equals(planner) || "scientific-qa-target-rerank".equals(planner)) {
            return PaperPageLocator.RankingOptions.withTargetTermBoost();
        }
        return PaperPageLocator.RankingOptions.defaults();
    }

    private record ChunkPageScore(double score, String reasonToken) {
    }

    public record Options(
            int topK,
            int windowRadius,
            String queryPlanner,
            String candidateSource
    ) {
        public Options(int topK, int windowRadius, String queryPlanner) {
            this(topK, windowRadius, queryPlanner, "first-stage");
        }

        public Options {
            topK = Math.max(1, topK);
            windowRadius = Math.max(0, windowRadius);
            queryPlanner = queryPlanner == null ? "none" : queryPlanner;
            candidateSource = candidateSource == null || candidateSource.isBlank() ? "first-stage" : candidateSource;
        }

        public static Options defaults() {
            return new Options(3, 1, "scientific-qa", "first-stage");
        }
    }

    public record HarnessResult(
            PaperRetrievalService.RetrievalResult firstStage,
            String candidateSource,
            List<SearchResult> candidateHits,
            String locatorQuery,
            List<String> queryExpansions,
            List<PaperPageWindow> windows,
            List<PaperPageInspection> inspections,
            EvidenceLedger ledger
    ) {
        public HarnessResult(PaperRetrievalService.RetrievalResult firstStage,
                             String locatorQuery,
                             List<String> queryExpansions,
                             List<PaperPageWindow> windows,
                             List<PaperPageInspection> inspections,
                             EvidenceLedger ledger) {
            this(firstStage,
                    "first-stage",
                    firstStage == null ? List.of() : firstStage.results(),
                    locatorQuery,
                    queryExpansions,
                    windows,
                    inspections,
                    ledger);
        }

        public HarnessResult(PaperRetrievalService.RetrievalResult firstStage,
                             String candidateSource,
                             String locatorQuery,
                             List<String> queryExpansions,
                             List<PaperPageWindow> windows,
                             List<PaperPageInspection> inspections,
                             EvidenceLedger ledger) {
            this(firstStage,
                    candidateSource,
                    firstStage == null ? List.of() : firstStage.results(),
                    locatorQuery,
                    queryExpansions,
                    windows,
                    inspections,
                    ledger);
        }

        public HarnessResult {
            candidateSource = candidateSource == null || candidateSource.isBlank()
                    ? "FIRST_STAGE"
                    : normalizedCandidateSource(candidateSource);
            candidateHits = candidateHits == null ? List.of() : List.copyOf(candidateHits);
            queryExpansions = queryExpansions == null ? List.of() : List.copyOf(queryExpansions);
            windows = windows == null ? List.of() : List.copyOf(windows);
            inspections = inspections == null ? List.of() : List.copyOf(inspections);
            ledger = ledger == null ? EvidenceLedger.empty() : ledger;
        }
    }

    private static String normalizedCandidateSource(String candidateSource) {
        String source = candidateSource == null || candidateSource.isBlank() ? "first-stage" : candidateSource;
        return source.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
