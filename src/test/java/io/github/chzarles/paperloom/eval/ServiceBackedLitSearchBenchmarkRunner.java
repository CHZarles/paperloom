package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.entity.SearchResult;
import io.github.chzarles.paperloom.service.PaperRetrievalService;
import io.github.chzarles.paperloom.service.RetrievalBudget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ServiceBackedLitSearchBenchmarkRunner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PaperRetrievalService retrievalService;

    public ServiceBackedLitSearchBenchmarkRunner(PaperRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    public Path run(Options options) throws Exception {
        List<LitSearchBenchmarkCase> cases = LitSearchBenchmarkDataset.load(options.goldPath());
        Map<String, List<String>> retrieved = retrieve(cases, options, loadReusableRetrieved(options.retrievedPath()));
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

    private Map<String, List<String>> retrieve(List<LitSearchBenchmarkCase> cases,
                                               Options options,
                                               Map<String, List<String>> reusableRetrieved) throws Exception {
        Map<String, List<String>> retrieved = new LinkedHashMap<>();
        for (LitSearchBenchmarkCase testCase : cases) {
            List<String> reusable = reusableRetrieved.get(testCase.id());
            if (reusable != null) {
                retrieved.put(testCase.id(), reusable);
                continue;
            }
            PaperRetrievalService.RetrievalResult result = options.scopePaperIds().isEmpty()
                    ? retrievalService.retrieve(testCase.query(), options.userId(), options.budget())
                    : retrievalService.retrieve(testCase.query(), options.userId(), options.budget(), options.scopePaperIds());
            retrieved.put(testCase.id(), corpusIds(result, options.topK()));
            writeRetrieved(options.retrievedPath(), retrieved);
        }
        return retrieved;
    }

    private List<String> corpusIds(PaperRetrievalService.RetrievalResult result, int topK) {
        if (result == null || result.results() == null || topK <= 0) {
            return List.of();
        }
        return result.results().stream()
                .map(SearchResult::getPaperId)
                .filter(paperId -> paperId != null && !paperId.isBlank())
                .map(ServiceBackedLitSearchBenchmarkRunner::toCorpusId)
                .distinct()
                .limit(topK)
                .toList();
    }

    private static String toCorpusId(String paperId) {
        String trimmed = paperId == null ? "" : paperId.trim();
        if (trimmed.startsWith("litsearch:")) {
            return trimmed.substring("litsearch:".length());
        }
        return trimmed;
    }

    private void writeRetrieved(Path output, Map<String, List<String>> retrieved) throws Exception {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : retrieved.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", entry.getKey());
            row.put("retrievedCorpusIds", entry.getValue());
            row.put("completed", true);
            lines.add(OBJECT_MAPPER.writeValueAsString(row));
        }
        Files.write(output, lines);
    }

    private Map<String, List<String>> loadReusableRetrieved(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return Map.of();
        }
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (String rawLine : Files.readAllLines(path)) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            JsonNode row = OBJECT_MAPPER.readTree(line);
            String caseId = row.path("caseId").asText(row.path("id").asText(""));
            if (caseId.isBlank()) {
                continue;
            }
            List<String> corpusIds = stringList(row.path("retrievedCorpusIds"));
            if (row.path("completed").asBoolean(false) || !corpusIds.isEmpty()) {
                rows.put(caseId, corpusIds);
            }
        }
        return rows;
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

    public record Options(
            Path goldPath,
            Path retrievedPath,
            Path runsRoot,
            Path registryPath,
            Path cheatsheetPath,
            String harnessId,
            String datasetId,
            String runId,
            String startedAt,
            String userId,
            RetrievalBudget budget,
            int topK,
            List<String> scopePaperIds
    ) {
        public Options(Path goldPath,
                       Path retrievedPath,
                       Path runsRoot,
                       Path registryPath,
                       Path cheatsheetPath,
                       String harnessId,
                       String datasetId,
                       String runId,
                       String startedAt,
                       String userId,
                       RetrievalBudget budget,
                       int topK) {
            this(goldPath, retrievedPath, runsRoot, registryPath, cheatsheetPath, harnessId, datasetId,
                    runId, startedAt, userId, budget, topK, List.of());
        }

        public Options {
            budget = budget == null ? RetrievalBudget.forLibrarySearch() : budget;
            topK = topK <= 0 ? 20 : topK;
            scopePaperIds = normalizeScopePaperIds(scopePaperIds);
        }
    }

    private static List<String> normalizeScopePaperIds(List<String> paperIds) {
        if (paperIds == null || paperIds.isEmpty()) {
            return List.of();
        }
        return paperIds.stream()
                .filter(paperId -> paperId != null && !paperId.isBlank())
                .distinct()
                .toList();
    }
}
