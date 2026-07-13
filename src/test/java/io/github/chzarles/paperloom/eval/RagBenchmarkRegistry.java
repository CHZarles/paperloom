package io.github.chzarles.paperloom.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RagBenchmarkRegistry(
        List<HarnessDefinition> harnesses,
        List<BenchmarkDefinition> benchmarks
) {
    public RagBenchmarkRegistry {
        harnesses = harnesses == null ? List.of() : List.copyOf(harnesses);
        benchmarks = benchmarks == null ? List.of() : List.copyOf(benchmarks);
    }

    public static RagBenchmarkRegistry load(Path path) throws IOException {
        List<Map<String, String>> harnessRows = new ArrayList<>();
        List<Map<String, String>> benchmarkRows = new ArrayList<>();
        List<Map<String, String>> currentRows = List.of();
        Map<String, String> current = null;
        for (String rawLine : Files.readAllLines(path)) {
            String line = rawLine.strip();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if ("harnesses:".equals(line)) {
                currentRows = harnessRows;
                current = null;
                continue;
            }
            if ("benchmarks:".equals(line)) {
                currentRows = benchmarkRows;
                current = null;
                continue;
            }
            if (line.startsWith("- ")) {
                current = new LinkedHashMap<>();
                currentRows.add(current);
                putKeyValue(current, line.substring(2));
                continue;
            }
            if (current != null) {
                putKeyValue(current, line);
            }
        }
        return new RagBenchmarkRegistry(
                harnessRows.stream().map(RagBenchmarkRegistry::harness).toList(),
                benchmarkRows.stream().map(RagBenchmarkRegistry::benchmark).toList()
        );
    }

    public BenchmarkDefinition benchmark(String id) {
        return benchmarks.stream()
                .filter(benchmark -> benchmark.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown benchmark: " + id));
    }

    private static void putKeyValue(Map<String, String> row, String line) {
        int separator = line.indexOf(':');
        if (separator < 0) {
            return;
        }
        String key = line.substring(0, separator).trim();
        String value = line.substring(separator + 1).trim();
        row.put(key, unquote(value));
    }

    private static String unquote(String value) {
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static HarnessDefinition harness(Map<String, String> row) {
        return new HarnessDefinition(
                row.getOrDefault("id", ""),
                row.getOrDefault("name", ""),
                row.getOrDefault("description", ""),
                row.getOrDefault("retrieval", ""),
                row.getOrDefault("planner", ""),
                row.getOrDefault("verifier", ""),
                row.getOrDefault("status", ""),
                commaList(row.getOrDefault("benchmarkIds", ""))
        );
    }

    private static BenchmarkDefinition benchmark(Map<String, String> row) {
        return new BenchmarkDefinition(
                row.getOrDefault("id", ""),
                row.getOrDefault("name", ""),
                row.getOrDefault("tier", ""),
                row.getOrDefault("task", ""),
                row.getOrDefault("status", ""),
                row.getOrDefault("path", ""),
                row.getOrDefault("source", ""),
                row.getOrDefault("primaryMetric", "passRate"),
                row.getOrDefault("cases", "")
        );
    }

    public record HarnessDefinition(
            String id,
            String name,
            String description,
            String retrieval,
            String planner,
            String verifier,
            String status,
            List<String> benchmarkIds
    ) {
        public HarnessDefinition {
            benchmarkIds = benchmarkIds == null ? List.of() : List.copyOf(benchmarkIds);
        }
    }

    public record BenchmarkDefinition(
            String id,
            String name,
            String tier,
            String task,
            String status,
            String path,
            String source,
            String primaryMetric,
            String cases
    ) {
    }

    private static List<String> commaList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String value : raw.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }
}
