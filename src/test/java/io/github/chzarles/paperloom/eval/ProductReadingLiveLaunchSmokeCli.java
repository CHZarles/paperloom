package io.github.chzarles.paperloom.eval;

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

public final class ProductReadingLiveLaunchSmokeCli {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProductReadingLiveLaunchSmokeCli() {
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
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(options.timeout())
                .build();
        String token;
        String conversationId;
        try {
            token = login(httpClient, options);
            conversationId = options.conversationId() == null || options.conversationId().isBlank()
                    ? createConversation(httpClient, options, token)
                    : options.conversationId();
        } catch (Exception exception) {
            ProductReadingLiveLaunchSmokeRunner runner = new ProductReadingLiveLaunchSmokeRunner(request -> {
                throw new IllegalStateException("startup failure client should not be called");
            });
            return runner.runStartupFailure(new ProductReadingLiveLaunchSmokeRunner.Options(
                    options.casesPath(),
                    options.runsRoot(),
                    options.runId(),
                    options.startedAt(),
                    options.harnessId(),
                    options.datasetId(),
                    ""
            ), exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        ProductReadingLiveWebSocketChatClient client = new ProductReadingLiveWebSocketChatClient(
                httpClient,
                options.wsBase(),
                token,
                options.timeout()
        );
        ProductReadingLiveLaunchSmokeRunner runner = new ProductReadingLiveLaunchSmokeRunner(client);
        return runner.run(new ProductReadingLiveLaunchSmokeRunner.Options(
                options.casesPath(),
                options.runsRoot(),
                options.runId(),
                options.startedAt(),
                options.harnessId(),
                options.datasetId(),
                conversationId
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

    private static String createConversation(HttpClient httpClient, LiveOptions options, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(options.apiBase()) + "/users/conversations"))
                .timeout(options.timeout())
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = OBJECT_MAPPER.readTree(response.body());
        String conversationId = json.path("data").path("conversationId").asText("");
        if (response.statusCode() >= 300 || conversationId.isBlank()) {
            throw new IllegalStateException("create conversation failed: status="
                    + response.statusCode() + ", body=" + response.body());
        }
        return conversationId;
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private record LiveOptions(
            Path casesPath,
            Path runsRoot,
            String harnessId,
            String datasetId,
            String runId,
            String startedAt,
            String apiBase,
            String wsBase,
            String username,
            String password,
            String conversationId,
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
            String harnessId = values.getOrDefault("harness-id", "product-reading-live-launch-smoke");
            String datasetId = values.getOrDefault("dataset-id", "product-reading-live-launch-smoke");
            return new LiveOptions(
                    Path.of(values.getOrDefault("cases", "eval/rag/product-reading-live-launch-smoke-cases.jsonl")),
                    Path.of(values.getOrDefault("runs-root", "eval/rag/runs")),
                    harnessId,
                    datasetId,
                    values.getOrDefault("run-id", defaultRunId(startedAt, harnessId, datasetId)),
                    startedAt,
                    values.getOrDefault("api-base", "http://127.0.0.1:8081/api/v1"),
                    values.getOrDefault("ws-base", "ws://127.0.0.1:8081/chat"),
                    values.getOrDefault("username", System.getenv().getOrDefault("RAG_EVAL_USERNAME", "admin")),
                    values.getOrDefault("password", System.getenv().getOrDefault("RAG_EVAL_PASSWORD", "")),
                    values.getOrDefault("conversation-id", ""),
                    Duration.ofSeconds(Long.parseLong(values.getOrDefault("timeout-seconds", "180")))
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
}
