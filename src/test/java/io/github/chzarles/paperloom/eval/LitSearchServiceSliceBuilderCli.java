package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LitSearchServiceSliceBuilderCli {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LitSearchServiceSliceBuilderCli() {
    }

    public static void main(String[] args) throws Exception {
        Summary summary = build(Options.parse(args));
        System.out.println("selectedCorpusIds=" + summary.selectedCorpusIds());
        System.out.println("writtenPapers=" + summary.writtenPapers());
        System.out.println("missingCorpusIds=" + summary.missingCorpusIds());
    }

    public static Summary build(Options options) throws Exception {
        Set<String> selectedCorpusIds = selectedCorpusIds(options);
        Set<String> writtenCorpusIds = new LinkedHashSet<>();
        Path parent = options.outputPath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(options.outputPath())) {
            LitSearchPaperDocumentDataset.forEach(options.corpusPath(), paper -> {
                if (!selectedCorpusIds.contains(paper.paperId())) {
                    return;
                }
                writer.write(OBJECT_MAPPER.writeValueAsString(paper));
                writer.newLine();
                writtenCorpusIds.add(paper.paperId());
            });
        }
        return new Summary(
                selectedCorpusIds.size(),
                writtenCorpusIds.size(),
                selectedCorpusIds.size() - writtenCorpusIds.size()
        );
    }

    private static Set<String> selectedCorpusIds(Options options) throws Exception {
        Set<String> selected = new LinkedHashSet<>();
        for (LitSearchBenchmarkCase testCase : LitSearchBenchmarkDataset.load(options.goldPath())) {
            for (String corpusId : testCase.goldCorpusIds()) {
                addCorpusId(selected, corpusId);
            }
        }
        for (Path retrievedPath : options.retrievedPaths()) {
            addRetrieved(selected, retrievedPath, options.maxRetrievedPerCase());
        }
        return selected;
    }

    private static void addRetrieved(Set<String> selected, Path path, int maxRetrievedPerCase) throws Exception {
        int limit = Math.max(maxRetrievedPerCase, 0);
        for (String rawLine : Files.readAllLines(path)) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            JsonNode row = OBJECT_MAPPER.readTree(line);
            JsonNode ids = row.path("retrievedCorpusIds");
            if (!ids.isArray()) {
                continue;
            }
            int addedForCase = 0;
            for (JsonNode id : ids) {
                if (limit > 0 && addedForCase >= limit) {
                    break;
                }
                if (addCorpusId(selected, id.asText())) {
                    addedForCase++;
                }
            }
        }
    }

    private static boolean addCorpusId(Set<String> selected, String corpusId) {
        String normalized = corpusId == null ? "" : corpusId.trim();
        if (normalized.isBlank()) {
            return false;
        }
        return selected.add(normalized);
    }

    public record Options(
            Path goldPath,
            List<Path> retrievedPaths,
            Path corpusPath,
            Path outputPath,
            int maxRetrievedPerCase
    ) {
        public Options {
            retrievedPaths = retrievedPaths == null ? List.of() : List.copyOf(retrievedPaths);
            maxRetrievedPerCase = maxRetrievedPerCase < 0 ? 0 : maxRetrievedPerCase;
        }

        static Options parse(String[] args) {
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
            return new Options(
                    Path.of(required(values, "gold")),
                    paths(required(values, "retrieved")),
                    Path.of(required(values, "corpus")),
                    Path.of(required(values, "output")),
                    Integer.parseInt(values.getOrDefault("max-retrieved-per-case", "20"))
            );
        }

        private static List<Path> paths(String value) {
            List<Path> paths = new ArrayList<>();
            for (String token : value.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isBlank()) {
                    paths.add(Path.of(trimmed));
                }
            }
            return paths;
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing --" + key);
            }
            return value;
        }
    }

    public record Summary(
            int selectedCorpusIds,
            int writtenPapers,
            int missingCorpusIds
    ) {
    }
}
