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
import java.util.List;
import java.util.Map;

public final class RagLiveBenchmarkCli {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RagLiveBenchmarkCli() {
    }

    public static void main(String[] args) throws Exception {
        LiveOptions liveOptions = LiveOptions.parse(args);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(liveOptions.timeout())
                .build();
        String token = login(httpClient, liveOptions);
        RagLiveWebSocketChatClient client = new RagLiveWebSocketChatClient(
                httpClient,
                liveOptions.wsBase(),
                token,
                liveOptions.timeout()
        );
        run(liveOptions.toOptions(), client);
    }

    public static Path run(Options options, RagLiveBenchmarkRunner.LiveChatClient client) throws Exception {
        List<RagBenchmarkCase> cases = RagBenchmarkDataset.load(options.datasetPath());
        RagBenchmarkRun run = new RagLiveBenchmarkRunner(client, new RagBenchmarkEvaluator()).run(cases);
        Path runDir = RagEvalRunWriter.write(
                options.runsRoot(),
                options.runId(),
                options.startedAt(),
                options.harnessId(),
                options.datasetId(),
                options.datasetPath().toString(),
                run,
                Map.of()
        );
        RagCheatsheetWriter.write(
                options.cheatsheetPath(),
                options.registryPath(),
                options.runsRoot(),
                options.startedAt()
        );
        return runDir;
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

    public record Options(
            Path datasetPath,
            Path runsRoot,
            Path registryPath,
            Path cheatsheetPath,
            String harnessId,
            String datasetId,
            String runId,
            String startedAt
    ) {
    }

    private record LiveOptions(
            Path datasetPath,
            Path runsRoot,
            Path registryPath,
            Path cheatsheetPath,
            String harnessId,
            String datasetId,
            String runId,
            String startedAt,
            String apiBase,
            String wsBase,
            String username,
            String password,
            Duration timeout
    ) {
        private static LiveOptions parse(String[] args) {
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
            String harnessId = values.getOrDefault("harness-id", "current-evidence-ledger");
            String datasetId = values.getOrDefault("dataset-id", "product-rescue-smoke");
            return new LiveOptions(
                    Path.of(values.getOrDefault("dataset", "eval/rag/product-rescue-smoke.jsonl")),
                    Path.of(values.getOrDefault("runs-root", "eval/rag/runs")),
                    Path.of(values.getOrDefault("registry", "eval/rag/harnesses.yaml")),
                    Path.of(values.getOrDefault("cheatsheet", "eval/rag/CHEATSHEET.md")),
                    harnessId,
                    datasetId,
                    values.getOrDefault("run-id", defaultRunId(startedAt, harnessId, datasetId)),
                    startedAt,
                    values.getOrDefault("api-base", "http://127.0.0.1:8081/api/v1"),
                    values.getOrDefault("ws-base", "ws://127.0.0.1:8081/chat"),
                    values.getOrDefault("username", System.getenv().getOrDefault("RAG_EVAL_USERNAME", "admin")),
                    values.getOrDefault("password", System.getenv().getOrDefault("RAG_EVAL_PASSWORD", "PaismartAdmin2025!")),
                    Duration.ofSeconds(Long.parseLong(values.getOrDefault("timeout-seconds", "120")))
            );
        }

        private Options toOptions() {
            return new Options(datasetPath, runsRoot, registryPath, cheatsheetPath, harnessId, datasetId, runId, startedAt);
        }

        private static String defaultRunId(String startedAt, String harnessId, String datasetId) {
            String timestamp = startedAt
                    .replace(":", "")
                    .replace(".", "")
                    .replace("Z", "Z");
            return timestamp + "-" + harnessId + "-" + datasetId;
        }
    }
}
