package com.yizhaoqi.smartpai.eval;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProductLaunchReadinessCli {

    private ProductLaunchReadinessCli() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            Path runDir = runCommand(args);
            System.out.println("runDir=" + runDir);
            exitCode = RagEvalGateStatus.printFailureAndExitCode(runDir);
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    static Path runCommand(String[] args) throws Exception {
        Options options = Options.parse(args);
        return run(options, defaultGates(options));
    }

    static Path run(Options options, List<ProductLaunchReadinessRunner.LaunchGate> gates) throws Exception {
        Options safeOptions = options == null ? Options.defaults() : options;
        ProductLaunchReadinessRunner runner = new ProductLaunchReadinessRunner(gates);
        return runner.run(new ProductLaunchReadinessRunner.Options(
                safeOptions.runsRoot(),
                safeOptions.runId(),
                safeOptions.startedAt(),
                safeOptions.harnessId(),
                safeOptions.datasetId()
        ));
    }

    static List<ProductLaunchReadinessRunner.LaunchGate> defaultGates(Options options) {
        Options safeOptions = options == null ? Options.defaults() : options;
        List<ProductLaunchReadinessRunner.LaunchGate> gates = new ArrayList<>();
        gates.add(new ProductLaunchReadinessRunner.LaunchGate(
                "product-launch-runtime-preflight",
                context -> ProductLaunchRuntimePreflightCli.runCommand(new String[]{
                        "--runs-root", safeOptions.runsRoot().toString(),
                        "--run-id", context.childRunId(),
                        "--started-at", safeOptions.startedAt(),
                        "--env", safeOptions.envPath().toString(),
                        "--timeout-seconds", String.valueOf(safeOptions.preflightTimeoutSeconds()),
                        "--api-base", safeOptions.apiBase()
                })
        ));
        gates.add(new ProductLaunchReadinessRunner.LaunchGate(
                "product-pdf-launch-data-seed",
                context -> ProductPdfLaunchDataSeedCli.runCommand(new String[]{
                        "--runs-root", safeOptions.runsRoot().toString(),
                        "--run-id", context.childRunId(),
                        "--started-at", safeOptions.startedAt(),
                        "--manifest", safeOptions.manifestPath().toString(),
                        "--api-base", safeOptions.apiBase()
                })
        ));
        gates.add(new ProductLaunchReadinessRunner.LaunchGate(
                "product-reading-live-launch-smoke",
                context -> ProductReadingLiveLaunchSmokeCli.runCommand(new String[]{
                        "--runs-root", safeOptions.runsRoot().toString(),
                        "--run-id", context.childRunId(),
                        "--started-at", safeOptions.startedAt(),
                        "--cases", safeOptions.liveSmokeCasesPath().toString(),
                        "--api-base", safeOptions.apiBase(),
                        "--ws-base", safeOptions.wsBase()
                })
        ));
        gates.add(new ProductLaunchReadinessRunner.LaunchGate(
                "product-reading-launch-trace-eval",
                context -> ProductReadingLaunchTraceEvalCli.run(new ProductReadingLaunchTraceEvalCli.Options(
                        safeOptions.traceRoot(),
                        safeOptions.traceCasesPath(),
                        safeOptions.runsRoot(),
                        context.childRunId(),
                        safeOptions.startedAt(),
                        "product-reading-launch-trace-eval",
                        "product-reading-launch-trace"
                ))
        ));
        gates.add(new ProductLaunchReadinessRunner.LaunchGate(
                "product-pdf-parser-smoke",
                context -> ProductPdfParserSmokeCli.runCommand(new String[]{
                        "--runs-root", safeOptions.runsRoot().toString(),
                        "--run-id", context.childRunId(),
                        "--started-at", safeOptions.startedAt(),
                        "--manifest", safeOptions.manifestPath().toString(),
                        "--harness-id", "product-pdf-parser-smoke",
                        "--dataset-id", "product-pdf-launch-30"
                })
        ));
        return List.copyOf(gates);
    }

    public record Options(
            Path runsRoot,
            String runId,
            String startedAt,
            String harnessId,
            String datasetId,
            Path envPath,
            String apiBase,
            String wsBase,
            int preflightTimeoutSeconds,
            Path manifestPath,
            Path liveSmokeCasesPath,
            Path traceRoot,
            Path traceCasesPath
    ) {
        public Options {
            runsRoot = runsRoot == null ? Path.of("eval/rag/runs") : runsRoot;
            startedAt = blankToDefault(startedAt, Instant.now().toString());
            harnessId = blankToDefault(harnessId, "product-launch-readiness");
            datasetId = blankToDefault(datasetId, "product-launch-readiness");
            runId = blankToDefault(runId, ProductLaunchReadinessRunner.defaultRunId(startedAt, harnessId, datasetId));
            envPath = envPath == null ? Path.of(".env") : envPath;
            apiBase = blankToDefault(apiBase, "http://127.0.0.1:8081/api/v1");
            wsBase = blankToDefault(wsBase, "ws://127.0.0.1:8081/chat");
            preflightTimeoutSeconds = Math.max(1, preflightTimeoutSeconds);
            manifestPath = manifestPath == null
                    ? Path.of("eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl")
                    : manifestPath;
            liveSmokeCasesPath = liveSmokeCasesPath == null
                    ? Path.of("eval/rag/product-reading-live-launch-smoke-cases.jsonl")
                    : liveSmokeCasesPath;
            traceRoot = traceRoot == null ? Path.of("data/traces/product-react") : traceRoot;
            traceCasesPath = traceCasesPath == null
                    ? Path.of("eval/rag/product-reading-launch-trace-cases.jsonl")
                    : traceCasesPath;
        }

        static Options defaults() {
            return new Options(
                    Path.of("eval/rag/runs"),
                    null,
                    Instant.now().toString(),
                    "product-launch-readiness",
                    "product-launch-readiness",
                    Path.of(".env"),
                    "http://127.0.0.1:8081/api/v1",
                    "ws://127.0.0.1:8081/chat",
                    5,
                    Path.of("eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl"),
                    Path.of("eval/rag/product-reading-live-launch-smoke-cases.jsonl"),
                    Path.of("data/traces/product-react"),
                    Path.of("eval/rag/product-reading-launch-trace-cases.jsonl")
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
            String harnessId = values.getOrDefault("harness-id", "product-launch-readiness");
            String datasetId = values.getOrDefault("dataset-id", "product-launch-readiness");
            return new Options(
                    Path.of(values.getOrDefault("runs-root", "eval/rag/runs")),
                    values.getOrDefault("run-id",
                            ProductLaunchReadinessRunner.defaultRunId(startedAt, harnessId, datasetId)),
                    startedAt,
                    harnessId,
                    datasetId,
                    Path.of(values.getOrDefault("env", ".env")),
                    values.getOrDefault("api-base", "http://127.0.0.1:8081/api/v1"),
                    values.getOrDefault("ws-base", "ws://127.0.0.1:8081/chat"),
                    Integer.parseInt(values.getOrDefault("timeout-seconds", "5")),
                    Path.of(values.getOrDefault("manifest",
                            "eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl")),
                    Path.of(values.getOrDefault("live-cases",
                            "eval/rag/product-reading-live-launch-smoke-cases.jsonl")),
                    Path.of(values.getOrDefault("trace-root", "data/traces/product-react")),
                    Path.of(values.getOrDefault("trace-cases",
                            "eval/rag/product-reading-launch-trace-cases.jsonl"))
            );
        }
    }

    private static String blankToDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
