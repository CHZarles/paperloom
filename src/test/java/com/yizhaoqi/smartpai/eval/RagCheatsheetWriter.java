package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RagCheatsheetWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RagCheatsheetWriter() {
    }

    public static void write(Path output, Path registryYaml, Path runsRoot, String updatedAt) throws IOException {
        RagBenchmarkRegistry registry = RagBenchmarkRegistry.load(registryYaml);
        Scorecards scorecards = readScorecards(runsRoot);
        StringBuilder builder = new StringBuilder()
                .append("# PaperLoom RAG Eval Cheatsheet\n\n")
                .append("Last updated: ").append(updatedAt).append("\n\n")
                .append("| Harness | Benchmark | Tier | Cases | Primary | Quality | Run |\n")
                .append("|---|---|---|---:|---:|---|---|\n");
        for (RagBenchmarkRegistry.HarnessDefinition harness : registry.harnesses()) {
            for (RagBenchmarkRegistry.BenchmarkDefinition benchmark : registry.benchmarks()) {
                if (!appliesTo(harness, benchmark)) {
                    continue;
                }
                JsonNode scorecard = scorecards.latestByHarnessAndDataset()
                        .get(key(harness.id(), benchmark.id()));
                builder.append("| ")
                        .append(harness.id()).append(" | ")
                        .append(benchmark.name()).append(" | ")
                        .append(benchmark.tier()).append(" | ")
                        .append(cases(benchmark, scorecard)).append(" | ")
                        .append(primary(benchmark, scorecard)).append(" | ")
                        .append(quality(scorecard)).append(" | ")
                        .append(runLink(scorecard)).append(" |\n");
            }
        }
        if (!scorecards.warnings().isEmpty()) {
            builder.append("\n## Warnings\n\n");
            for (String warning : scorecards.warnings()) {
                builder.append("- skipped malformed scorecard: ").append(warning).append("\n");
            }
        }
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, builder.toString());
    }

    private static Scorecards readScorecards(Path runsRoot) throws IOException {
        Map<String, JsonNode> latest = new LinkedHashMap<>();
        List<String> warnings = new java.util.ArrayList<>();
        if (!Files.exists(runsRoot)) {
            return new Scorecards(latest, warnings);
        }
        try (var paths = Files.walk(runsRoot)) {
            for (Path path : paths.filter(candidate -> candidate.getFileName().toString().equals("scorecard.json")).toList()) {
                try {
                    JsonNode scorecard = OBJECT_MAPPER.readTree(path.toFile());
                    String key = key(scorecard.path("harnessId").asText(), scorecard.path("datasetId").asText());
                    JsonNode previous = latest.get(key);
                    if (previous == null || scorecard.path("startedAt").asText().compareTo(previous.path("startedAt").asText()) >= 0) {
                        latest.put(key, scorecard);
                    }
                } catch (IOException parseError) {
                    warnings.add(runsRoot.relativize(path).toString());
                }
            }
        }
        return new Scorecards(latest, warnings);
    }

    private static String key(String harnessId, String datasetId) {
        return harnessId + "::" + datasetId;
    }

    private static String cases(RagBenchmarkRegistry.BenchmarkDefinition benchmark, JsonNode scorecard) {
        if (scorecard == null) {
            return benchmark.cases();
        }
        return String.valueOf(scorecard.path("caseCount").asInt());
    }

    private static String primary(RagBenchmarkRegistry.BenchmarkDefinition benchmark, JsonNode scorecard) {
        String label = metricLabel(benchmark.primaryMetric());
        if (scorecard == null) {
            return "pending " + label;
        }
        Double value = metricValue(scorecard, benchmark.primaryMetric());
        if (value == null) {
            return "n/a " + label;
        }
        return label + " " + percent(value);
    }

    private static String quality(JsonNode scorecard) {
        if (scorecard == null) {
            return "-";
        }
        Double evidence = metricValue(scorecard, "evidenceRequiredHitRate");
        Double citation = metricValue(scorecard, "citationMappingRate");
        Double bad = metricValue(scorecard, "badEvidenceRate");
        if (evidence == null || citation == null || bad == null) {
            return "-";
        }
        return "Ev " + percent(evidence) + ", Cite " + percent(citation) + ", Bad " + percent(bad);
    }

    private static String runLink(JsonNode scorecard) {
        if (scorecard == null) {
            return "-";
        }
        return "`runs/" + scorecard.path("runId").asText() + "/`";
    }

    private static boolean appliesTo(RagBenchmarkRegistry.HarnessDefinition harness,
                                     RagBenchmarkRegistry.BenchmarkDefinition benchmark) {
        return harness.benchmarkIds().isEmpty() || harness.benchmarkIds().contains(benchmark.id());
    }

    private static Double metricValue(JsonNode scorecard, String metric) {
        JsonNode direct = scorecard.get(metric);
        if (direct != null && direct.isNumber()) {
            return direct.asDouble();
        }
        JsonNode nested = scorecard.path("metrics").get(metric);
        if (nested != null && nested.isNumber()) {
            return nested.asDouble();
        }
        return null;
    }

    private static String metricLabel(String metric) {
        if (metric != null && metric.startsWith("positivePageRecallAt")) {
            return "PosPage@" + metric.substring("positivePageRecallAt".length());
        }
        if (metric != null && metric.startsWith("pageRecallAt")) {
            return "Page@" + metric.substring("pageRecallAt".length());
        }
        if (metric != null && metric.startsWith("chunkEvidenceHitAt")) {
            return "ChunkEv@" + metric.substring("chunkEvidenceHitAt".length());
        }
        if (metric != null && metric.startsWith("windowEvidenceHitAt")) {
            return "WindowEv@" + metric.substring("windowEvidenceHitAt".length());
        }
        return switch (metric) {
            case "passRate" -> "Pass";
            case "recallAt5" -> "Recall@5";
            case "recallAt20" -> "Recall@20";
            case "evidenceRequiredHitRate" -> "Evidence";
            case "citationMappingRate" -> "Citation";
            case "pageMrr" -> "Page MRR";
            default -> metric;
        };
    }

    private static String percent(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", value * 100.0d);
    }

    private record Scorecards(
            Map<String, JsonNode> latestByHarnessAndDataset,
            List<String> warnings
    ) {
    }
}
