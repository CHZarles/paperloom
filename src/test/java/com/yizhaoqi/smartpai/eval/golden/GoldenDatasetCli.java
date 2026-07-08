package com.yizhaoqi.smartpai.eval.golden;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class GoldenDatasetCli {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private GoldenDatasetCli() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Missing command: validate, export-rag, or score-trace");
        }
        String command = args[0];
        Map<String, String> options = parseOptions(args);
        Path manifest = Path.of(options.getOrDefault("manifest", "research/golden-data/manifest.yaml"));
        GoldenDatasetSchema.GoldenDataset dataset = new GoldenDatasetLoader().load(manifest);

        if ("validate".equals(command)) {
            new GoldenDatasetValidator().requireValid(dataset);
            return 0;
        }
        if ("export-rag".equals(command)) {
            new GoldenDatasetValidator().requireValid(dataset);
            Path output = Path.of(required(options, "output"));
            new GoldenRagBenchmarkProjector().writeJsonl(dataset.cases(), output);
            return 0;
        }
        if ("score-trace".equals(command)) {
            new GoldenDatasetValidator().requireValid(dataset);
            Path tracePath = Path.of(required(options, "trace"));
            Path output = Path.of(required(options, "output"));
            GoldenDatasetSchema.RunTrace trace = new GoldenRunTraceLoader().load(tracePath);
            GoldenDatasetSchema.GoldenCase testCase = casesById(dataset).get(trace.case_id());
            GoldenDatasetSchema.CaseScore score = new GoldenTraceScorer().score(testCase, dataset, trace);
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), score);
            return score.passed() ? 0 : 2;
        }
        throw new IllegalArgumentException("Unknown command: " + command);
    }

    private static Map<String, GoldenDatasetSchema.GoldenCase> casesById(GoldenDatasetSchema.GoldenDataset dataset) {
        return dataset.cases().stream()
                .collect(Collectors.toMap(GoldenDatasetSchema.GoldenCase::id, Function.identity()));
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + arg);
            }
            values.put(arg.substring(2), args[++i]);
        }
        return values;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option: --" + key);
        }
        return value;
    }
}
