package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PaperPageLocatorBenchmarkCli {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PaperPageLocatorBenchmarkCli() {
    }

    public static void main(String[] args) throws Exception {
        run(Options.parse(args));
    }

    public static Path run(Options options) throws Exception {
        List<PaperPageLocatorCase> cases = PaperPageLocatorCaseDataset.load(options.casesPath());
        List<SearchResult> chunks = PaperPageChunkDataset.loadSearchResults(options.chunksPath());
        List<RagBenchmarkCase> ragCases = options.ragCasesPath() == null
                ? List.of()
                : RagBenchmarkDataset.load(options.ragCasesPath());
        List<PaperPageDocument> pages = PaperPageIndexBuilder.fromSearchResults(chunks);
        LocationResults locationResults = locateWithQueries(
                cases,
                pages,
                options.topK(),
                options.windowRadius(),
                options.queryPlanner(),
                scopedPaperIdsByCase(ragCases)
        );
        writeRetrieved(options.retrievedPath(), locationResults);
        RagBenchmarkRun run = runFrom(cases, locationResults);
        Map<String, Double> metrics = new LinkedHashMap<>(PaperPageLocatorScorer.score(
                goldPageKeys(cases),
                locationResults.hitsByCase(),
                1,
                options.topK()
        ));
        metrics.putAll(PaperPageWindowScorer.score(
                goldPageKeys(cases),
                locationResults.windowsByCase(),
                1,
                options.topK()
        ));
        metrics.putAll(evidenceMetrics(ragCases, cases, chunks, locationResults, options.topK()));
        Path runDir = RagEvalRunWriter.write(
                options.runsRoot(),
                options.runId(),
                options.startedAt(),
                options.harnessId(),
                options.datasetId(),
                options.casesPath().toString(),
                run,
                metrics
        );
        RagCheatsheetWriter.write(
                options.cheatsheetPath(),
                options.registryPath(),
                options.runsRoot(),
                options.startedAt()
        );
        return runDir;
    }

    private static Map<String, Double> evidenceMetrics(List<RagBenchmarkCase> ragCases,
                                                       List<PaperPageLocatorCase> pageCases,
                                                       List<SearchResult> chunks,
                                                       LocationResults locationResults,
                                                       int topK) {
        List<RagBenchmarkCase> alignedRagCases = alignRagCasesToPageCases(ragCases, pageCases);
        if (alignedRagCases.isEmpty()) {
            return Map.of();
        }
        int[] ks = metricKs(topK);
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.putAll(PaperEvidenceHitScorer.scoreChunkEvidence(
                alignedRagCases,
                rankedChunksByCase(alignedRagCases, chunks, topK),
                ks
        ));
        metrics.putAll(PaperEvidenceHitScorer.scoreWindowEvidence(
                alignedRagCases,
                inspectionsByCase(locationResults.windowsByCase(), chunks),
                ks
        ));
        return metrics;
    }

    private static List<RagBenchmarkCase> alignRagCasesToPageCases(List<RagBenchmarkCase> ragCases,
                                                                   List<PaperPageLocatorCase> pageCases) {
        Set<String> pageCaseIds = (pageCases == null ? List.<PaperPageLocatorCase>of() : pageCases).stream()
                .map(PaperPageLocatorCase::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (pageCaseIds.isEmpty()) {
            return List.of();
        }
        return (ragCases == null ? List.<RagBenchmarkCase>of() : ragCases).stream()
                .filter(testCase -> pageCaseIds.contains(testCase.id()))
                .toList();
    }

    static Map<String, List<PaperPageHit>> locate(List<PaperPageLocatorCase> cases,
                                                  List<PaperPageDocument> pages,
                                                  int topK) {
        return locateWithQueries(cases, pages, topK, 0, "none").hitsByCase();
    }

    static LocationResults locateWithQueries(List<PaperPageLocatorCase> cases,
                                             List<PaperPageDocument> pages,
                                             int topK,
                                             int windowRadius,
                                             String queryPlanner) {
        return locateWithQueries(cases, pages, topK, windowRadius, queryPlanner, Map.of());
    }

    private static LocationResults locateWithQueries(List<PaperPageLocatorCase> cases,
                                                     List<PaperPageDocument> pages,
                                                     int topK,
                                                     int windowRadius,
                                                     String queryPlanner,
                                                     Map<String, Set<String>> scopedPaperIdsByCase) {
        Map<String, List<PaperPageHit>> hitsByCase = new LinkedHashMap<>();
        Map<String, List<PaperPageWindow>> windowsByCase = new LinkedHashMap<>();
        Map<String, String> locatorQueriesByCase = new LinkedHashMap<>();
        Map<String, List<String>> expansionsByCase = new LinkedHashMap<>();
        for (PaperPageLocatorCase testCase : cases == null ? List.<PaperPageLocatorCase>of() : cases) {
            List<PaperPageDocument> candidatePages = scopedPages(
                    pages,
                    scopedPaperIdsByCase == null ? Set.of() : scopedPaperIdsByCase.getOrDefault(testCase.id(), Set.of())
            );
            PaperPageLocatorQueryPlanner.PlannedQuery plannedQuery = plannedQuery(testCase.query(), candidatePages, queryPlanner);
            List<PaperPageHit> hits = PaperPageLocator.rank(
                    plannedQuery.expandedQuery(),
                    candidatePages,
                    topK,
                    rankingOptions(queryPlanner)
            );
            hitsByCase.put(testCase.id(), hits);
            windowsByCase.put(testCase.id(), pageWindows(hits, candidatePages, windowRadius));
            locatorQueriesByCase.put(testCase.id(), plannedQuery.expandedQuery());
            expansionsByCase.put(testCase.id(), plannedQuery.expansions());
        }
        return new LocationResults(hitsByCase, windowsByCase, locatorQueriesByCase, expansionsByCase);
    }

    private static RagBenchmarkRun runFrom(List<PaperPageLocatorCase> cases,
                                           LocationResults locationResults) {
        List<RagBenchmarkCase> ragCases = new ArrayList<>();
        List<RagBenchmarkActual> actuals = new ArrayList<>();
        List<RagBenchmarkVerdict> verdicts = new ArrayList<>();
        for (PaperPageLocatorCase testCase : cases) {
            List<PaperPageHit> hits = locationResults.hitsByCase().getOrDefault(testCase.id(), List.of());
            List<String> retrievedPageKeys = pageKeys(hits);
            double pageRecallAtK = recallAt(testCase.goldPageKeys(), retrievedPageKeys, hits.size());
            boolean passed = pageRecallAtK > 0.0d;
            ragCases.add(new RagBenchmarkCase(
                    testCase.id(),
                    testCase.query(),
                    "mixed",
                    "PAGE_LOCATION",
                    "AUTO_SOURCE",
                    new RagBenchmarkCase.Scope(List.of(), List.of()),
                    "PAGE_LOCATION",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    testCase.goldPageKeys(),
                    false
            ));
            actuals.add(new RagBenchmarkActual(
                    "PAGE_LOCATION",
                    String.join(", ", retrievedPageKeys),
                    Map.of(),
                    Map.of(
                            "retrievedPageCount", retrievedPageKeys.size(),
                            "retrievedPageKeys", retrievedPageKeys,
                            "goldPageKeys", testCase.goldPageKeys(),
                            "locatorQuery", locationResults.locatorQueriesByCase().getOrDefault(testCase.id(), testCase.query()),
                            "queryExpansions", locationResults.expansionsByCase().getOrDefault(testCase.id(), List.of()),
                            "pageRecallAtK", pageRecallAtK
                    )
            ));
            verdicts.add(new RagBenchmarkVerdict(
                    testCase.id(),
                    passed,
                    passed ? List.of() : List.of("GOLD_PAGE_MISSING:" + String.join(",", testCase.goldPageKeys())),
                    passed ? List.of() : List.of("PAGE_LOCATION_MISS")
            ));
        }
        return new RagBenchmarkRun(ragCases, actuals, verdicts);
    }

    private static PaperPageLocatorQueryPlanner.PlannedQuery plannedQuery(String query,
                                                                          List<PaperPageDocument> pages,
                                                                          String queryPlanner) {
        String planner = queryPlanner == null ? "" : queryPlanner.trim();
        if ("page-locator".equalsIgnoreCase(planner)) {
            return PaperPageLocatorQueryPlanner.plan(query, pages);
        }
        if ("scientific-qa".equalsIgnoreCase(planner)) {
            return PaperPageLocatorQueryPlanner.planScientificQa(query, pages);
        }
        if ("scientific-qa-targets".equalsIgnoreCase(planner)
                || "scientific-qa-target-rerank".equalsIgnoreCase(planner)) {
            return PaperPageLocatorQueryPlanner.planScientificQa(query, pages);
        }
        String rawQuery = query == null ? "" : query;
        return new PaperPageLocatorQueryPlanner.PlannedQuery(rawQuery, rawQuery, List.of());
    }

    private static PaperPageLocator.RankingOptions rankingOptions(String queryPlanner) {
        String planner = queryPlanner == null ? "" : queryPlanner.trim();
        if ("scientific-qa-targets".equalsIgnoreCase(planner)
                || "scientific-qa-target-rerank".equalsIgnoreCase(planner)) {
            return PaperPageLocator.RankingOptions.withTargetTermBoost();
        }
        return PaperPageLocator.RankingOptions.defaults();
    }

    private static List<PaperPageWindow> pageWindows(List<PaperPageHit> hits,
                                                     List<PaperPageDocument> pages,
                                                     int windowRadius) {
        int radius = Math.max(0, windowRadius);
        return (hits == null ? List.<PaperPageHit>of() : hits).stream()
                .map(hit -> new PaperPageWindow(
                        hit.page(),
                        PaperPageLocator.expandNeighbors(hit.page(), pages, radius),
                        hit.score(),
                        hit.reasons()
                ))
                .toList();
    }

    private static Map<String, Set<String>> scopedPaperIdsByCase(List<RagBenchmarkCase> ragCases) {
        Map<String, Set<String>> scopedByCase = new LinkedHashMap<>();
        for (RagBenchmarkCase testCase : ragCases == null ? List.<RagBenchmarkCase>of() : ragCases) {
            scopedByCase.put(testCase.id(), allowedPaperIds(testCase));
        }
        return scopedByCase;
    }

    private static List<PaperPageDocument> scopedPages(List<PaperPageDocument> pages, Set<String> allowedPaperIds) {
        if (allowedPaperIds == null || allowedPaperIds.isEmpty()) {
            return pages == null ? List.of() : pages;
        }
        return (pages == null ? List.<PaperPageDocument>of() : pages).stream()
                .filter(page -> allowedPaperIds.contains(page.paperId()))
                .toList();
    }

    private static Map<String, List<SearchResult>> rankedChunksByCase(List<RagBenchmarkCase> cases,
                                                                      List<SearchResult> chunks,
                                                                      int topK) {
        Map<String, List<SearchResult>> rankedByCase = new LinkedHashMap<>();
        for (RagBenchmarkCase testCase : cases == null ? List.<RagBenchmarkCase>of() : cases) {
            rankedByCase.put(testCase.id(), rankedChunks(testCase, chunks, topK));
        }
        return rankedByCase;
    }

    private static List<SearchResult> rankedChunks(RagBenchmarkCase testCase,
                                                   List<SearchResult> chunks,
                                                   int topK) {
        Set<String> allowedPaperIds = allowedPaperIds(testCase);
        return (chunks == null ? List.<SearchResult>of() : chunks).stream()
                .filter(chunk -> chunk != null)
                .filter(chunk -> allowedPaperIds.isEmpty() || allowedPaperIds.contains(chunk.getPaperId()))
                .sorted(Comparator
                        .comparingDouble((SearchResult chunk) -> chunkScore(testCase.query(), chunk)).reversed()
                        .thenComparing(chunk -> chunk.getPaperId() == null ? "" : chunk.getPaperId())
                        .thenComparing(chunk -> chunk.getPageNumber() == null ? Integer.MAX_VALUE : chunk.getPageNumber())
                        .thenComparing(chunk -> chunk.getChunkId() == null ? Integer.MAX_VALUE : chunk.getChunkId()))
                .limit(Math.max(1, topK))
                .toList();
    }

    private static Map<String, List<PaperPageInspection>> inspectionsByCase(Map<String, List<PaperPageWindow>> windowsByCase,
                                                                            List<SearchResult> chunks) {
        Map<String, List<PaperPageInspection>> inspectionsByCase = new LinkedHashMap<>();
        for (Map.Entry<String, List<PaperPageWindow>> entry : windowsByCase.entrySet()) {
            inspectionsByCase.put(entry.getKey(), (entry.getValue() == null ? List.<PaperPageWindow>of() : entry.getValue()).stream()
                    .map(window -> PaperPageLocatorTool.inspectPage(window, chunks))
                    .toList());
        }
        return inspectionsByCase;
    }

    private static Set<String> allowedPaperIds(RagBenchmarkCase testCase) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (testCase == null) {
            return ids;
        }
        ids.addAll(testCase.scope().paperIds());
        ids.addAll(testCase.expectedPaperIds());
        ids.removeIf(String::isBlank);
        return ids;
    }

    private static double chunkScore(String query, SearchResult chunk) {
        Set<String> queryTokens = tokens(query);
        Set<String> titleTokens = tokens(chunk.getPaperTitle());
        Set<String> sectionTokens = tokens(chunk.getSectionTitle());
        Set<String> textTokens = tokens(chunkText(chunk));
        double score = 0.0d;
        for (String token : queryTokens) {
            if (titleTokens.contains(token)) {
                score += 2.0d;
            }
            if (sectionTokens.contains(token)) {
                score += 1.5d;
            }
            if (textTokens.contains(token)) {
                score += 1.0d;
            }
        }
        String sourceKind = chunk.getSourceKind() == null ? "" : chunk.getSourceKind().toLowerCase();
        if (queryTokens.contains("table") && ("table".equals(sourceKind) || chunk.getTableId() != null)) {
            score += 1.0d;
        }
        if (queryTokens.contains("figure") && ("figure".equals(sourceKind) || chunk.getFigureId() != null)) {
            score += 1.0d;
        }
        return score;
    }

    private static String chunkText(SearchResult chunk) {
        String matched = chunk.getMatchedChunkText();
        if (matched != null && !matched.isBlank()) {
            return matched;
        }
        return chunk.getTextContent() == null ? "" : chunk.getTextContent();
    }

    private static Set<String> tokens(String text) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        String normalized = (text == null ? "" : text.toLowerCase())
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHan}]+", " ");
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static int[] metricKs(int topK) {
        int effectiveTopK = Math.max(1, topK);
        if (effectiveTopK == 1) {
            return new int[]{1};
        }
        return new int[]{1, effectiveTopK};
    }

    private static Map<String, List<String>> goldPageKeys(List<PaperPageLocatorCase> cases) {
        Map<String, List<String>> gold = new LinkedHashMap<>();
        for (PaperPageLocatorCase testCase : cases == null ? List.<PaperPageLocatorCase>of() : cases) {
            gold.put(testCase.id(), testCase.goldPageKeys());
        }
        return gold;
    }

    private static List<String> pageKeys(List<PaperPageHit> hits) {
        return (hits == null ? List.<PaperPageHit>of() : hits).stream()
                .map(PaperPageHit::page)
                .map(PaperPageLocatorScorer::pageKey)
                .toList();
    }

    private static double recallAt(List<String> goldPageKeys, List<String> retrievedPageKeys, int k) {
        List<String> safeGold = goldPageKeys == null ? List.of() : goldPageKeys;
        if (safeGold.isEmpty()) {
            return 0.0d;
        }
        long hits = safeGold.stream()
                .filter(gold -> retrievedPageKeys.stream().limit(Math.max(0, k)).anyMatch(gold::equals))
                .count();
        return (double) hits / safeGold.size();
    }

    private static void writeRetrieved(Path output, LocationResults locationResults) throws Exception {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<PaperPageHit>> entry : locationResults.hitsByCase().entrySet()) {
            lines.add(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "caseId", entry.getKey(),
                    "locatorQuery", locationResults.locatorQueriesByCase().getOrDefault(entry.getKey(), ""),
                    "queryExpansions", locationResults.expansionsByCase().getOrDefault(entry.getKey(), List.of()),
                    "retrievedPageKeys", pageKeys(entry.getValue()),
                    "windows", windows(locationResults.windowsByCase().getOrDefault(entry.getKey(), List.of())),
                    "scores", entry.getValue().stream().map(PaperPageHit::score).toList()
            )));
        }
        Files.write(output, lines);
    }

    private static List<Map<String, Object>> windows(List<PaperPageWindow> windows) {
        return (windows == null ? List.<PaperPageWindow>of() : windows).stream()
                .map(window -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("centerPageKey", window.centerPageKey());
                    row.put("pageKeys", window.pageKeys());
                    row.put("pageNumbers", window.pageNumbers());
                    row.put("score", window.score());
                    row.put("reasons", window.reasons());
                    return row;
                })
                .toList();
    }

    record LocationResults(
            Map<String, List<PaperPageHit>> hitsByCase,
            Map<String, List<PaperPageWindow>> windowsByCase,
            Map<String, String> locatorQueriesByCase,
            Map<String, List<String>> expansionsByCase
    ) {
        LocationResults {
            hitsByCase = hitsByCase == null ? Map.of() : new LinkedHashMap<>(hitsByCase);
            windowsByCase = windowsByCase == null ? Map.of() : new LinkedHashMap<>(windowsByCase);
            locatorQueriesByCase = locatorQueriesByCase == null ? Map.of() : new LinkedHashMap<>(locatorQueriesByCase);
            expansionsByCase = expansionsByCase == null ? Map.of() : new LinkedHashMap<>(expansionsByCase);
        }
    }

    public record Options(
            Path casesPath,
            Path chunksPath,
            Path ragCasesPath,
            Path retrievedPath,
            Path runsRoot,
            Path registryPath,
            Path cheatsheetPath,
            String harnessId,
            String datasetId,
            String runId,
            String startedAt,
            String queryPlanner,
            int topK,
            int windowRadius
    ) {
        public Options(Path casesPath,
                       Path chunksPath,
                       Path retrievedPath,
                       Path runsRoot,
                       Path registryPath,
                       Path cheatsheetPath,
                       String harnessId,
                       String datasetId,
                       String runId,
                       String startedAt,
                       int topK) {
            this(casesPath,
                    chunksPath,
                    null,
                    retrievedPath,
                    runsRoot,
                    registryPath,
                    cheatsheetPath,
                    harnessId,
                    datasetId,
                    runId,
                    startedAt,
                    "none",
                    topK,
                    0);
        }

        public Options(Path casesPath,
                       Path chunksPath,
                       Path retrievedPath,
                       Path runsRoot,
                       Path registryPath,
                       Path cheatsheetPath,
                       String harnessId,
                       String datasetId,
                       String runId,
                       String startedAt,
                       String queryPlanner,
                       int topK) {
            this(casesPath,
                    chunksPath,
                    null,
                    retrievedPath,
                    runsRoot,
                    registryPath,
                    cheatsheetPath,
                    harnessId,
                    datasetId,
                    runId,
                    startedAt,
                    queryPlanner,
                    topK,
                    0);
        }

        public Options(Path casesPath,
                       Path chunksPath,
                       Path retrievedPath,
                       Path runsRoot,
                       Path registryPath,
                       Path cheatsheetPath,
                       String harnessId,
                       String datasetId,
                       String runId,
                       String startedAt,
                       String queryPlanner,
                       int topK,
                       int windowRadius) {
            this(casesPath,
                    chunksPath,
                    null,
                    retrievedPath,
                    runsRoot,
                    registryPath,
                    cheatsheetPath,
                    harnessId,
                    datasetId,
                    runId,
                    startedAt,
                    queryPlanner,
                    topK,
                    windowRadius);
        }

        public Options {
            queryPlanner = queryPlanner == null || queryPlanner.isBlank() ? "none" : queryPlanner;
            windowRadius = Math.max(0, windowRadius);
            ragCasesPath = ragCasesPath == null || ragCasesPath.toString().isBlank() ? null : ragCasesPath;
        }

        private static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                values.put(arg.substring(2), args[++i]);
            }
            String startedAt = values.getOrDefault("started-at", Instant.now().toString());
            String harnessId = values.getOrDefault("harness-id", "page-index-offline");
            String datasetId = values.getOrDefault("dataset-id", "page-location-mini");
            return new Options(
                    Path.of(required(values, "cases")),
                    Path.of(required(values, "chunks")),
                    optionalPath(values.get("rag-cases")),
                    Path.of(required(values, "retrieved")),
                    Path.of(values.getOrDefault("runs-root", "eval/rag/runs")),
                    Path.of(values.getOrDefault("registry", "eval/rag/harnesses.yaml")),
                    Path.of(values.getOrDefault("cheatsheet", "eval/rag/CHEATSHEET.md")),
                    harnessId,
                    datasetId,
                    values.getOrDefault("run-id", defaultRunId(startedAt, harnessId, datasetId)),
                    startedAt,
                    values.getOrDefault("query-planner", "none"),
                    Integer.parseInt(values.getOrDefault("top-k", "3")),
                    Integer.parseInt(values.getOrDefault("window-radius", "0"))
            );
        }

        private static Path optionalPath(String value) {
            return value == null || value.isBlank() ? null : Path.of(value);
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing --" + key);
            }
            return value;
        }

        private static String defaultRunId(String startedAt, String harnessId, String datasetId) {
            return startedAt
                    .replace(":", "")
                    .replace(".", "")
                    .replace("Z", "Z")
                    + "-" + harnessId + "-" + datasetId;
        }
    }
}
