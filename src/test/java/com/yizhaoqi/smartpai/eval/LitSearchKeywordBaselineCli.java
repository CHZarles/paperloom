package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class LitSearchKeywordBaselineCli {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "any", "are", "as", "at", "be", "both", "by", "can", "could",
            "for", "from", "has", "have", "in", "is", "it", "me", "methods", "of", "on",
            "or", "paper", "papers", "research", "studies", "study", "that", "the", "there",
            "this", "to", "using", "with"
    );

    private LitSearchKeywordBaselineCli() {
    }

    public static void main(String[] args) throws Exception {
        run(Options.parse(args));
    }

    public static Path run(Options options) throws Exception {
        LitSearchDatasetIdGuard.rejectObviousPartialCorpusAsFull(options.datasetId(), options.corpusPath());
        List<LitSearchBenchmarkCase> cases = LitSearchBenchmarkDataset.load(options.goldPath());
        Set<String> queryVocabulary = queryVocabulary(cases);
        List<WeightedPaper> weightedPapers = loadWeightedPapers(options.corpusPath(), queryVocabulary);
        LitSearchDatasetIdGuard.rejectPartialCorpusSizeAsFull(
                options.datasetId(),
                weightedPapers.size(),
                options.corpusPath().toString()
        );
        Map<String, List<String>> retrieved = retrieveWeighted(cases, weightedPapers, options.topK());
        writeRetrieved(options.retrievedPath(), retrieved);
        return LitSearchRetrievalScoreCli.run(new LitSearchRetrievalScoreCli.Options(
                options.goldPath(),
                options.retrievedPath(),
                options.runsRoot(),
                options.registryPath(),
                options.cheatsheetPath(),
                options.harnessId(),
                options.datasetId(),
                options.runId(),
                options.startedAt()
        ));
    }

    private static List<WeightedPaper> loadWeightedPapers(Path path, Set<String> queryVocabulary) throws Exception {
        List<WeightedPaper> papers = new ArrayList<>();
        if (path.getFileName().toString().endsWith(".jsonl")) {
            LitSearchPaperDocumentDataset.forEach(path, paper ->
                    papers.add(WeightedPaper.from(paper, queryVocabulary)));
            return papers;
        }
        for (LitSearchPaperDocument paper : new LitSearchBenchmarkConverter().convertCorpus(path, 0)) {
            papers.add(WeightedPaper.from(paper, queryVocabulary));
        }
        return papers;
    }

    static Map<String, List<String>> retrieve(List<LitSearchBenchmarkCase> cases,
                                              List<LitSearchPaperDocument> papers,
                                              int topK) {
        Set<String> queryVocabulary = queryVocabulary(cases);
        List<WeightedPaper> weightedPapers = papers.stream()
                .map(paper -> WeightedPaper.from(paper, queryVocabulary))
                .toList();
        return retrieveWeighted(cases, weightedPapers, topK);
    }

    private static Map<String, List<String>> retrieveWeighted(List<LitSearchBenchmarkCase> cases,
                                                              List<WeightedPaper> weightedPapers,
                                                              int topK) {
        Map<String, Long> documentFrequency = documentFrequency(weightedPapers);
        Map<String, List<String>> retrieved = new LinkedHashMap<>();
        for (LitSearchBenchmarkCase testCase : cases) {
            Set<String> queryTokens = tokenize(testCase.query()).keySet();
            List<String> paperIds = weightedPapers.stream()
                    .map(paper -> new ScoredPaper(
                            paper.paperId(),
                            score(queryTokens, paper, documentFrequency, weightedPapers.size())
                    ))
                    .sorted(Comparator.comparingDouble(ScoredPaper::score).reversed()
                            .thenComparing(ScoredPaper::paperId))
                    .limit(Math.max(topK, 0))
                    .map(ScoredPaper::paperId)
                    .toList();
            retrieved.put(testCase.id(), paperIds);
        }
        return retrieved;
    }

    private static Set<String> queryVocabulary(List<LitSearchBenchmarkCase> cases) {
        Set<String> vocabulary = new HashSet<>();
        for (LitSearchBenchmarkCase testCase : cases) {
            vocabulary.addAll(tokenize(testCase.query()).keySet());
        }
        return vocabulary;
    }

    private static void writeRetrieved(Path output, Map<String, List<String>> retrieved) throws Exception {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : retrieved.entrySet()) {
            lines.add(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "caseId", entry.getKey(),
                    "retrievedCorpusIds", entry.getValue()
            )));
        }
        Files.write(output, lines);
    }

    private static double score(Set<String> queryTokens,
                                WeightedPaper paper,
                                Map<String, Long> documentFrequency,
                                int paperCount) {
        double score = 0.0d;
        for (String token : queryTokens) {
            int weightedTermFrequency = paper.weightedTermFrequency().getOrDefault(token, 0);
            if (weightedTermFrequency == 0) {
                continue;
            }
            long documentsWithTerm = documentFrequency.getOrDefault(token, 0L);
            double idf = Math.log(1.0d + (double) paperCount / (1.0d + documentsWithTerm)) + 1.0d;
            score += weightedTermFrequency * idf;
        }
        return score;
    }

    private static Map<String, Long> documentFrequency(List<WeightedPaper> papers) {
        return papers.stream()
                .flatMap(paper -> paper.weightedTermFrequency().keySet().stream())
                .collect(Collectors.groupingBy(token -> token, Collectors.counting()));
    }

    private static Map<String, Integer> tokenize(String text) {
        return tokenize(text, null);
    }

    private static Map<String, Integer> tokenize(String text, Set<String> allowedTokens) {
        Map<String, Integer> counts = new HashMap<>();
        String[] tokens = (text == null ? "" : text.toLowerCase(Locale.ROOT))
                .split("[^a-z0-9]+");
        for (String token : tokens) {
            if (token.length() < 3 || STOPWORDS.contains(token)) {
                continue;
            }
            if (allowedTokens != null && !allowedTokens.contains(token)) {
                continue;
            }
            counts.merge(token, 1, Integer::sum);
        }
        return counts;
    }

    private static Map<String, Integer> weightedTokens(LitSearchPaperDocument paper, Set<String> queryVocabulary) {
        Map<String, Integer> counts = new HashMap<>();
        addWeighted(counts, paper.title(), 4, queryVocabulary);
        addWeighted(counts, paper.abstractText(), 2, queryVocabulary);
        addWeighted(counts, paper.fullPaperText(), 1, queryVocabulary);
        return counts;
    }

    private static void addWeighted(Map<String, Integer> counts,
                                    String text,
                                    int weight,
                                    Set<String> queryVocabulary) {
        for (Map.Entry<String, Integer> entry : tokenize(text, queryVocabulary).entrySet()) {
            counts.merge(entry.getKey(), entry.getValue() * weight, Integer::sum);
        }
    }

    private record WeightedPaper(
            String paperId,
            Map<String, Integer> weightedTermFrequency
    ) {
        private static WeightedPaper from(LitSearchPaperDocument paper, Set<String> queryVocabulary) {
            return new WeightedPaper(paper.paperId(), weightedTokens(paper, queryVocabulary));
        }
    }

    private record ScoredPaper(
            String paperId,
            double score
    ) {
    }

    public record Options(
            Path goldPath,
            Path corpusPath,
            Path retrievedPath,
            Path runsRoot,
            Path registryPath,
            Path cheatsheetPath,
            String harnessId,
            String datasetId,
            String runId,
            String startedAt,
            int topK
    ) {
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
            String harnessId = values.getOrDefault("harness-id", "keyword-only-baseline");
            String datasetId = values.getOrDefault("dataset-id", "litsearch-full");
            return new Options(
                    Path.of(required(values, "gold")),
                    Path.of(required(values, "corpus")),
                    Path.of(required(values, "retrieved")),
                    Path.of(values.getOrDefault("runs-root", "eval/rag/runs")),
                    Path.of(values.getOrDefault("registry", "eval/rag/harnesses.yaml")),
                    Path.of(values.getOrDefault("cheatsheet", "eval/rag/CHEATSHEET.md")),
                    harnessId,
                    datasetId,
                    values.getOrDefault("run-id", defaultRunId(startedAt, harnessId, datasetId)),
                    startedAt,
                    Integer.parseInt(values.getOrDefault("top-k", "20"))
            );
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
