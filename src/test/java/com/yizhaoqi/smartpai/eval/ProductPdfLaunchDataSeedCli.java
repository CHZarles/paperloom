package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProductPdfLaunchDataSeedCli {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProductPdfLaunchDataSeedCli() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            Path runDir = runCommand(args);
            System.out.println("runDir=" + runDir);
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    static Path runCommand(String[] args) throws Exception {
        LiveOptions options = LiveOptions.parse(args);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(options.timeout())
                .build();
        String token;
        try {
            token = login(httpClient, options);
        } catch (Exception exception) {
            ProductPdfLaunchDataSeedRunner runner = new ProductPdfLaunchDataSeedRunner(new StartupFailureSeedClient(
                    exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                    exception
            ));
            return runner.run(new ProductPdfLaunchDataSeedRunner.Options(
                    options.manifestPath(),
                    options.runsRoot(),
                    options.runId(),
                    options.startedAt(),
                    options.harnessId(),
                    options.datasetId(),
                    options.chunkSizeBytes(),
                    options.pollAttempts(),
                    options.pollIntervalMillis(),
                    options.waitForSearchable()
            ));
        }
        ProductPdfLaunchDataSeedHttpClient client = new ProductPdfLaunchDataSeedHttpClient(
                httpClient,
                options.apiBase(),
                token,
                options.timeout()
        );
        ProductPdfLaunchDataSeedRunner runner = new ProductPdfLaunchDataSeedRunner(client);
        return runner.run(new ProductPdfLaunchDataSeedRunner.Options(
                options.manifestPath(),
                options.runsRoot(),
                options.runId(),
                options.startedAt(),
                options.harnessId(),
                options.datasetId(),
                options.chunkSizeBytes(),
                options.pollAttempts(),
                options.pollIntervalMillis(),
                options.waitForSearchable()
        ));
    }

    private static String login(HttpClient httpClient, LiveOptions options) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", options.username());
        body.put("password", options.password());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(options.apiBase()) + "/users/login"))
                .timeout(options.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = OBJECT_MAPPER.readTree(response.body());
        String token = json.path("data").path("token").asText("");
        if (response.statusCode() >= 300 || token.isBlank()) {
            throw new IllegalStateException("login failed: status=" + response.statusCode() + ", body=" + response.body());
        }
        return token;
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private record StartupFailureSeedClient(String message, Throwable cause)
            implements ProductPdfLaunchDataSeedRunner.LaunchDataSeedClient {
        @Override
        public void uploadChunk(ProductPdfLaunchDataSeedRunner.UploadChunkRequest request) {
            throw new ProductPdfLaunchDataSeedRunner.RuntimeUnavailableException(message, cause);
        }

        @Override
        public void merge(ProductPdfLaunchDataSeedRunner.MergeRequest request) {
            throw new ProductPdfLaunchDataSeedRunner.RuntimeUnavailableException(message, cause);
        }

        @Override
        public java.util.List<ProductPdfLaunchDataSeedRunner.PaperStatus> listUploadedPapers() {
            throw new ProductPdfLaunchDataSeedRunner.RuntimeUnavailableException(message, cause);
        }
    }

    private record LiveOptions(
            Path manifestPath,
            Path runsRoot,
            String harnessId,
            String datasetId,
            String runId,
            String startedAt,
            String apiBase,
            String username,
            String password,
            Duration timeout,
            Integer chunkSizeBytes,
            Integer pollAttempts,
            Long pollIntervalMillis,
            Boolean waitForSearchable
    ) {
        private static LiveOptions parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
                if (booleanFlag(arg)) {
                    values.put(arg.substring(2), "true");
                    continue;
                }
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                values.put(arg.substring(2), args[++i]);
            }
            String startedAt = values.getOrDefault("started-at", Instant.now().toString());
            String harnessId = values.getOrDefault("harness-id", "product-pdf-launch-data-seed");
            String datasetId = values.getOrDefault("dataset-id", "product-pdf-launch-30");
            return new LiveOptions(
                    Path.of(values.getOrDefault("manifest", "eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl")),
                    Path.of(values.getOrDefault("runs-root", "eval/rag/runs")),
                    harnessId,
                    datasetId,
                    values.getOrDefault("run-id", defaultRunId(startedAt, harnessId, datasetId)),
                    startedAt,
                    values.getOrDefault("api-base", "http://127.0.0.1:8081/api/v1"),
                    values.getOrDefault("username", System.getenv().getOrDefault("RAG_EVAL_USERNAME", "admin")),
                    values.getOrDefault("password", System.getenv().getOrDefault("RAG_EVAL_PASSWORD", "PaismartAdmin2025!")),
                    Duration.ofSeconds(Long.parseLong(values.getOrDefault("timeout-seconds", "180"))),
                    Integer.parseInt(values.getOrDefault("chunk-size-bytes", String.valueOf(5 * 1024 * 1024))),
                    Integer.parseInt(values.getOrDefault("poll-attempts", "180")),
                    Long.parseLong(values.getOrDefault("poll-interval-millis",
                            String.valueOf(Long.parseLong(values.getOrDefault("poll-interval-seconds", "10")) * 1000L))),
                    !values.containsKey("no-wait-for-searchable")
            );
        }

        private static boolean booleanFlag(String arg) {
            return "--no-wait-for-searchable".equals(arg);
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
