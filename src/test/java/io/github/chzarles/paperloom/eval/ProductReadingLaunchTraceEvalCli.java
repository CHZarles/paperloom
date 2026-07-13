package io.github.chzarles.paperloom.eval;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProductReadingLaunchTraceEvalCli {

    private ProductReadingLaunchTraceEvalCli() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            Path runDir = run(Options.parse(args));
            System.out.println("runDir=" + runDir);
            exitCode = RagEvalGateStatus.printFailureAndExitCode(runDir);
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    public static Path run(Options options) throws Exception {
        Options safeOptions = options == null ? Options.defaults() : options;
        ProductReadingLaunchTraceEvalRunner runner = new ProductReadingLaunchTraceEvalRunner();
        return runner.run(new ProductReadingLaunchTraceEvalRunner.Options(
                safeOptions.traceRoot(),
                safeOptions.casesPath(),
                safeOptions.runsRoot(),
                safeOptions.runId(),
                safeOptions.startedAt(),
                safeOptions.harnessId(),
                safeOptions.datasetId()
        ));
    }

    public record Options(
            Path traceRoot,
            Path casesPath,
            Path runsRoot,
            String runId,
            String startedAt,
            String harnessId,
            String datasetId
    ) {
        public Options {
            traceRoot = traceRoot == null ? Path.of("data", "traces", "product-react") : traceRoot;
            casesPath = casesPath == null ? Path.of("eval/rag/product-reading-launch-trace-cases.jsonl") : casesPath;
            runsRoot = runsRoot == null ? Path.of("eval/rag/runs") : runsRoot;
            startedAt = blankToDefault(startedAt, Instant.now().toString());
            harnessId = blankToDefault(harnessId, "product-reading-launch-trace-eval");
            datasetId = blankToDefault(datasetId, "product-reading-launch-trace");
            runId = blankToDefault(runId, defaultRunId(startedAt, harnessId, datasetId));
        }

        static Options defaults() {
            return new Options(
                    Path.of("data/traces/product-react"),
                    Path.of("eval/rag/product-reading-launch-trace-cases.jsonl"),
                    Path.of("eval/rag/runs"),
                    null,
                    Instant.now().toString(),
                    "product-reading-launch-trace-eval",
                    "product-reading-launch-trace"
            );
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
            String startedAt = values.getOrDefault("started-at", Instant.now().toString());
            String harnessId = values.getOrDefault("harness-id", "product-reading-launch-trace-eval");
            String datasetId = values.getOrDefault("dataset-id", "product-reading-launch-trace");
            return new Options(
                    Path.of(values.getOrDefault("trace-root", "data/traces/product-react")),
                    Path.of(values.getOrDefault("cases", "eval/rag/product-reading-launch-trace-cases.jsonl")),
                    Path.of(values.getOrDefault("runs-root", "eval/rag/runs")),
                    values.getOrDefault("run-id", defaultRunId(startedAt, harnessId, datasetId)),
                    startedAt,
                    harnessId,
                    datasetId
            );
        }

        private static String defaultRunId(String startedAt, String harnessId, String datasetId) {
            return startedAt
                    .replace(":", "")
                    .replace(".", "")
                    .replace("Z", "Z")
                    + "-" + harnessId + "-" + datasetId;
        }
    }

    private static String blankToDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
