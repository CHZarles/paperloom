package io.github.chzarles.paperloom.eval;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProductLaunchRuntimePreflightCli {

    private ProductLaunchRuntimePreflightCli() {
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
        LiveOptions options = LiveOptions.parse(args);
        ProductLaunchRuntimePreflightProbe probe = new ProductLaunchRuntimePreflightProbe(
                Duration.ofSeconds(options.timeoutSeconds())
        );
        ProductLaunchRuntimePreflightRunner runner = new ProductLaunchRuntimePreflightRunner(probe);
        return runner.run(new ProductLaunchRuntimePreflightRunner.Options(
                options.envPath(),
                options.runsRoot(),
                options.runId(),
                options.startedAt(),
                options.harnessId(),
                options.datasetId(),
                options.apiBase(),
                options.username(),
                options.password(),
                options.timeoutSeconds()
        ));
    }

    private record LiveOptions(
            Path envPath,
            Path runsRoot,
            String harnessId,
            String datasetId,
            String runId,
            String startedAt,
            String apiBase,
            String username,
            String password,
            Integer timeoutSeconds
    ) {
        private static LiveOptions parse(String[] args) throws Exception {
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
            Path envPath = Path.of(values.getOrDefault("env", ".env"));
            Map<String, String> env = ProductLaunchRuntimePreflightRunner.loadEnv(envPath);
            String startedAt = values.getOrDefault("started-at", Instant.now().toString());
            String harnessId = values.getOrDefault("harness-id", "product-launch-runtime-preflight");
            String datasetId = values.getOrDefault("dataset-id", "product-launch-runtime-preflight");
            return new LiveOptions(
                    envPath,
                    Path.of(values.getOrDefault("runs-root", "eval/rag/runs")),
                    harnessId,
                    datasetId,
                    values.getOrDefault("run-id", defaultRunId(startedAt, harnessId, datasetId)),
                    startedAt,
                    values.getOrDefault("api-base", "http://127.0.0.1:8081/api/v1"),
                    values.getOrDefault("username", firstNonBlank(
                            System.getenv("RAG_EVAL_USERNAME"),
                            env.getOrDefault("ADMIN_BOOTSTRAP_USERNAME", "admin")
                    )),
                    values.getOrDefault("password", firstNonBlank(
                            System.getenv("RAG_EVAL_PASSWORD"),
                            env.getOrDefault("ADMIN_BOOTSTRAP_PASSWORD", "")
                    )),
                    Integer.parseInt(values.getOrDefault("timeout-seconds", "5"))
            );
        }

        private static String defaultRunId(String startedAt, String harnessId, String datasetId) {
            return startedAt
                    .replace(":", "")
                    .replace(".", "")
                    .replace("Z", "Z")
                    + "-" + harnessId + "-" + datasetId;
        }

        private static String firstNonBlank(String first, String fallback) {
            return first == null || first.isBlank() ? fallback : first;
        }
    }
}
