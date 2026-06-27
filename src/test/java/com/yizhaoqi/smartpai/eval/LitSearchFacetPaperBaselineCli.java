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

public final class LitSearchFacetPaperBaselineCli {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "any", "are", "as", "at", "be", "both", "by", "can", "could",
            "for", "from", "has", "have", "in", "is", "it", "me", "method", "methods", "of", "on",
            "or", "paper", "papers", "research", "studies", "study", "that", "the", "there",
            "this", "to", "using", "with"
    );

    private LitSearchFacetPaperBaselineCli() {
    }

    public static void main(String[] args) throws Exception {
        run(Options.parse(args));
    }

    public static Path run(Options options) throws Exception {
        LitSearchDatasetIdGuard.rejectObviousPartialCorpusAsFull(options.datasetId(), options.corpusPath());
        List<LitSearchBenchmarkCase> cases = LitSearchBenchmarkDataset.load(options.goldPath());
        Set<String> queryVocabulary = queryVocabulary(cases);
        List<PaperProfile> profiles = loadProfiles(options.corpusPath(), queryVocabulary);
        LitSearchDatasetIdGuard.rejectPartialCorpusSizeAsFull(
                options.datasetId(),
                profiles.size(),
                options.corpusPath().toString()
        );
        Map<String, List<String>> retrieved = retrieveProfiles(cases, profiles, options.topK());
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

    private static List<PaperProfile> loadProfiles(Path path, Set<String> queryVocabulary) throws Exception {
        List<PaperProfile> profiles = new ArrayList<>();
        if (path.getFileName().toString().endsWith(".jsonl")) {
            LitSearchPaperDocumentDataset.forEach(path, paper ->
                    profiles.add(PaperProfile.from(paper, queryVocabulary)));
            return profiles;
        }
        for (LitSearchPaperDocument paper : new LitSearchBenchmarkConverter().convertCorpus(path, 0)) {
            profiles.add(PaperProfile.from(paper, queryVocabulary));
        }
        return profiles;
    }

    static Map<String, List<String>> retrieve(List<LitSearchBenchmarkCase> cases,
                                              List<LitSearchPaperDocument> papers,
                                              int topK) {
        Set<String> queryVocabulary = queryVocabulary(cases);
        List<PaperProfile> profiles = papers.stream()
                .map(paper -> PaperProfile.from(paper, queryVocabulary))
                .toList();
        return retrieveProfiles(cases, profiles, topK);
    }

    private static Map<String, List<String>> retrieveProfiles(List<LitSearchBenchmarkCase> cases,
                                                              List<PaperProfile> profiles,
                                                              int topK) {
        Map<String, Long> documentFrequency = documentFrequency(profiles);
        Map<String, List<String>> retrieved = new LinkedHashMap<>();
        for (LitSearchBenchmarkCase testCase : cases) {
            QueryProfile query = QueryProfile.from(testCase.query());
            List<String> paperIds = profiles.stream()
                    .map(paper -> new ScoredPaper(
                            paper.paperId(),
                            score(query, paper, documentFrequency, Math.max(profiles.size(), 1))
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

    private static double score(QueryProfile query,
                                PaperProfile paper,
                                Map<String, Long> documentFrequency,
                                int paperCount) {
        if (query.tokens().isEmpty()) {
            return 0.0d;
        }
        double score = 0.0d;
        int titleAbstractHits = 0;
        int anyFieldHits = 0;
        for (String token : query.tokens()) {
            int titleCount = paper.titleTokens().getOrDefault(token, 0);
            int abstractCount = paper.abstractTokens().getOrDefault(token, 0);
            int bodyCount = paper.bodyTokens().getOrDefault(token, 0);
            if (titleCount + abstractCount > 0) {
                titleAbstractHits++;
            }
            if (titleCount + abstractCount + bodyCount > 0) {
                anyFieldHits++;
            }
            if (titleCount + abstractCount + bodyCount == 0) {
                continue;
            }
            long documentsWithTerm = documentFrequency.getOrDefault(token, 0L);
            double idf = Math.log(1.0d + (double) paperCount / (1.0d + documentsWithTerm)) + 1.0d;
            score += Math.min(titleCount, 3) * 7.0d * idf;
            score += Math.min(abstractCount, 4) * 3.5d * idf;
            score += Math.min(bodyCount, 6) * 0.7d * idf;
        }
        score += 28.0d * ((double) anyFieldHits / query.tokens().size());
        score += 22.0d * ((double) titleAbstractHits / query.tokens().size());
        for (String phrase : query.phrases()) {
            if (paper.titleText().contains(phrase)) {
                score += 12.0d;
            } else if (paper.abstractText().contains(phrase)) {
                score += 7.0d;
            }
        }
        return score;
    }

    private static Map<String, Long> documentFrequency(List<PaperProfile> papers) {
        Map<String, Long> documentFrequency = new HashMap<>();
        for (PaperProfile paper : papers) {
            Set<String> tokens = new java.util.HashSet<>();
            tokens.addAll(paper.titleTokens().keySet());
            tokens.addAll(paper.abstractTokens().keySet());
            tokens.addAll(paper.bodyTokens().keySet());
            for (String token : tokens) {
                documentFrequency.merge(token, 1L, Long::sum);
            }
        }
        return documentFrequency;
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

    private static Map<String, Integer> tokenize(String text) {
        return tokenize(text, null);
    }

    private static Map<String, Integer> tokenize(String text, Set<String> allowedTokens) {
        Map<String, Integer> counts = new HashMap<>();
        String[] tokens = normalize(text).split("[^a-z0-9]+");
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

    private static List<String> phraseTokens(String text) {
        List<String> tokens = new ArrayList<>();
        String[] rawTokens = normalize(text).split("[^a-z0-9]+");
        for (String token : rawTokens) {
            if (token.length() < 3 || STOPWORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private static List<String> queryPhrases(String text) {
        List<String> tokens = phraseTokens(text);
        List<String> phrases = new ArrayList<>();
        for (int index = 0; index + 1 < tokens.size(); index++) {
            phrases.add(tokens.get(index) + " " + tokens.get(index + 1));
        }
        return phrases;
    }

    private static String normalize(String text) {
        return (text == null ? "" : text.toLowerCase(Locale.ROOT));
    }

    private record QueryProfile(
            List<String> tokens,
            List<String> phrases
    ) {
        private static QueryProfile from(String query) {
            return new QueryProfile(List.copyOf(tokenize(query).keySet()), queryPhrases(query));
        }
    }

    private record PaperProfile(
            String paperId,
            String titleText,
            String abstractText,
            Map<String, Integer> titleTokens,
            Map<String, Integer> abstractTokens,
            Map<String, Integer> bodyTokens
    ) {
        private static PaperProfile from(LitSearchPaperDocument paper, Set<String> queryVocabulary) {
            Map<String, Integer> titleTokens = tokenize(paper.title(), queryVocabulary);
            Map<String, Integer> abstractTokens = tokenize(paper.abstractText(), queryVocabulary);
            Map<String, Integer> bodyTokens = tokenize(paper.fullPaperText(), queryVocabulary);
            return new PaperProfile(
                    paper.paperId(),
                    normalize(paper.title()),
                    normalize(paper.abstractText()),
                    titleTokens,
                    abstractTokens,
                    bodyTokens
            );
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
            String harnessId = values.getOrDefault("harness-id", "facet-paper-baseline");
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
