package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class LitSearchBenchmarkConverter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public List<LitSearchBenchmarkCase> convertQueries(Path input, int maxCases) throws IOException {
        List<JsonNode> rows = rows(input);
        List<LitSearchBenchmarkCase> cases = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            if (maxCases > 0 && cases.size() >= maxCases) {
                break;
            }
            JsonNode wrapper = rows.get(index);
            JsonNode row = wrapper.has("row") ? wrapper.path("row") : wrapper;
            String querySet = row.path("query_set").asText("");
            cases.add(new LitSearchBenchmarkCase(
                    caseId(querySet, wrapper.path("row_idx").asInt(index)),
                    "LITSEARCH_RETRIEVAL",
                    querySet,
                    row.path("query").asText(""),
                    row.path("specificity").asInt(0),
                    row.path("quality").asInt(0),
                    stringList(row.path("corpusids"))
            ));
        }
        return cases;
    }

    public List<LitSearchPaperDocument> convertCorpus(Path input, int maxPapers) throws IOException {
        List<JsonNode> rows = rows(input);
        List<LitSearchPaperDocument> papers = new ArrayList<>();
        for (JsonNode wrapper : rows) {
            if (maxPapers > 0 && papers.size() >= maxPapers) {
                break;
            }
            JsonNode row = wrapper.has("row") ? wrapper.path("row") : wrapper;
            papers.add(new LitSearchPaperDocument(
                    row.path("corpusid").asText(""),
                    row.path("title").asText(""),
                    row.path("abstract").asText(""),
                    row.path("full_paper").asText(""),
                    stringList(row.path("citations"))
            ));
        }
        return papers;
    }

    public void writeQueryJsonl(Path input, Path output, int maxCases) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        for (LitSearchBenchmarkCase testCase : convertQueries(input, maxCases)) {
            lines.add(OBJECT_MAPPER.writeValueAsString(testCase));
        }
        Files.write(output, lines);
    }

    private List<JsonNode> rows(Path input) throws IOException {
        String text = Files.readString(input).trim();
        if (text.isBlank()) {
            return List.of();
        }
        JsonNode root = OBJECT_MAPPER.readTree(text);
        if (root.isArray()) {
            List<JsonNode> values = new ArrayList<>();
            root.forEach(values::add);
            return values;
        }
        if (root.has("rows") && root.path("rows").isArray()) {
            List<JsonNode> values = new ArrayList<>();
            root.path("rows").forEach(values::add);
            return values;
        }
        return List.of(root);
    }

    private List<String> stringList(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            values.add(value.asText());
        }
        return values;
    }

    private String caseId(String querySet, int rowIndex) {
        String normalizedQuerySet = querySet == null || querySet.isBlank()
                ? "unknown"
                : querySet.replaceAll("[^A-Za-z0-9]+", "_");
        return "litsearch_" + normalizedQuerySet + "_" + String.format(java.util.Locale.ROOT, "%04d", rowIndex);
    }
}
