package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LitSearchRetrievalScoreCli {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int LITSEARCH_FULL_QUERY_COUNT = 597;

    private LitSearchRetrievalScoreCli() {
    }

    public static void main(String[] args) throws Exception {
        run(Options.parse(args));
    }

    public static Path run(Options options) throws Exception {
        List<LitSearchBenchmarkCase> cases = LitSearchBenchmarkDataset.load(options.goldPath());
        rejectPartialGoldAsFull(options.datasetId(), cases.size(), options.goldPath());
        Map<String, List<String>> retrieved = loadRetrieved(options.retrievedPath());
        RagBenchmarkRun run = runFrom(cases, retrieved);
        Path runDir = RagEvalRunWriter.write(
                options.runsRoot(),
                options.runId(),
                options.startedAt(),
                options.harnessId(),
                options.datasetId(),
                options.goldPath().toString(),
                run,
                LitSearchRetrievalScorer.score(cases, retrieved)
        );
        RagCheatsheetWriter.write(
                options.cheatsheetPath(),
                options.registryPath(),
                options.runsRoot(),
                options.startedAt()
        );
        return runDir;
    }

    private static void rejectPartialGoldAsFull(String datasetId, int caseCount, Path goldPath) {
        if (!"litsearch-full".equals(datasetId)) {
            return;
        }
        if (caseCount != LITSEARCH_FULL_QUERY_COUNT) {
            throw new IllegalArgumentException(
                    "Refusing to report " + caseCount + " LitSearch gold cases from " + goldPath
                            + " as litsearch-full; expected " + LITSEARCH_FULL_QUERY_COUNT
                            + " cases. Use a sample-specific dataset id for mini/dev slices."
            );
        }
    }

    private static RagBenchmarkRun runFrom(List<LitSearchBenchmarkCase> cases,
                                           Map<String, List<String>> retrievedByCaseId) {
        List<RagBenchmarkCase> ragCases = new ArrayList<>();
        List<RagBenchmarkActual> actuals = new ArrayList<>();
        List<RagBenchmarkVerdict> verdicts = new ArrayList<>();
        for (LitSearchBenchmarkCase testCase : cases) {
            List<String> retrieved = retrievedByCaseId.getOrDefault(testCase.id(), List.of());
            double recallAt20 = recallAt(testCase.goldCorpusIds(), retrieved, 20);
            double reciprocalRank = reciprocalRank(testCase.goldCorpusIds(), retrieved);
            boolean passed = recallAt20 > 0.0d;
            ragCases.add(new RagBenchmarkCase(
                    testCase.id(),
                    testCase.query(),
                    "en",
                    testCase.taskType(),
                    "AUTO_SOURCE",
                    new RagBenchmarkCase.Scope(List.of(), List.of()),
                    "LITSEARCH_RETRIEVAL",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    testCase.goldCorpusIds(),
                    false
            ));
            actuals.add(new RagBenchmarkActual(
                    "LITSEARCH_RETRIEVAL",
                    String.join(", ", retrieved),
                    Map.of(),
                    Map.of(
                            "retrievedCount", retrieved.size(),
                            "hitCountAt20", hitCount(testCase.goldCorpusIds(), retrieved, 20),
                            "recallAt20", recallAt20,
                            "reciprocalRank", reciprocalRank
                    )
            ));
            verdicts.add(new RagBenchmarkVerdict(
                    testCase.id(),
                    passed,
                    passed ? List.of() : List.of("GOLD_CORPUS_MISSING_AT20:" + String.join(",", testCase.goldCorpusIds())),
                    passed ? List.of() : List.of("RETRIEVAL_MISS")
            ));
        }
        return new RagBenchmarkRun(ragCases, actuals, verdicts);
    }

    private static Map<String, List<String>> loadRetrieved(Path path) throws Exception {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (String rawLine : Files.readAllLines(path)) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            JsonNode row = OBJECT_MAPPER.readTree(line);
            String caseId = firstText(row, "caseId", "id");
            if (caseId.isBlank()) {
                continue;
            }
            rows.put(caseId, stringList(row.path("retrievedCorpusIds")));
        }
        return rows;
    }

    private static String firstText(JsonNode row, String... keys) {
        for (String key : keys) {
            String value = row.path(key).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static List<String> stringList(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            values.add(value.asText());
        }
        return values;
    }

    private static double recallAt(List<String> goldCorpusIds, List<String> retrievedCorpusIds, int k) {
        List<String> safeGold = goldCorpusIds == null ? List.of() : goldCorpusIds;
        if (safeGold.isEmpty()) {
            return 0.0d;
        }
        return (double) hitCount(safeGold, retrievedCorpusIds, k) / safeGold.size();
    }

    private static long hitCount(List<String> goldCorpusIds, List<String> retrievedCorpusIds, int k) {
        List<String> safeGold = goldCorpusIds == null ? List.of() : goldCorpusIds;
        List<String> safeRetrieved = retrievedCorpusIds == null ? List.of() : retrievedCorpusIds;
        return safeGold.stream()
                .filter(gold -> safeRetrieved.stream().limit(k).anyMatch(gold::equals))
                .count();
    }

    private static double reciprocalRank(List<String> goldCorpusIds, List<String> retrievedCorpusIds) {
        List<String> safeGold = goldCorpusIds == null ? List.of() : goldCorpusIds;
        List<String> safeRetrieved = retrievedCorpusIds == null ? List.of() : retrievedCorpusIds;
        for (int index = 0; index < safeRetrieved.size(); index++) {
            if (safeGold.contains(safeRetrieved.get(index))) {
                return 1.0d / (index + 1);
            }
        }
        return 0.0d;
    }

    public record Options(
            Path goldPath,
            Path retrievedPath,
            Path runsRoot,
            Path registryPath,
            Path cheatsheetPath,
            String harnessId,
            String datasetId,
            String runId,
            String startedAt
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
            String harnessId = values.getOrDefault("harness-id", "current-evidence-ledger");
            String datasetId = values.getOrDefault("dataset-id", "litsearch-full");
            return new Options(
                    Path.of(required(values, "gold")),
                    Path.of(required(values, "retrieved")),
                    Path.of(values.getOrDefault("runs-root", "eval/rag/runs")),
                    Path.of(values.getOrDefault("registry", "eval/rag/harnesses.yaml")),
                    Path.of(values.getOrDefault("cheatsheet", "eval/rag/CHEATSHEET.md")),
                    harnessId,
                    datasetId,
                    values.getOrDefault("run-id", defaultRunId(startedAt, harnessId, datasetId)),
                    startedAt
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
