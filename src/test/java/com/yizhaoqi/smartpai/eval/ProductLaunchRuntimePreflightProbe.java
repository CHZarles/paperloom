package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProductLaunchRuntimePreflightProbe implements ProductLaunchRuntimePreflightRunner.RuntimeProbe {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final Duration timeout;

    public ProductLaunchRuntimePreflightProbe(Duration timeout) {
        this(HttpClient.newHttpClient(), timeout);
    }

    public ProductLaunchRuntimePreflightProbe(HttpClient httpClient, Duration timeout) {
        this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
        this.timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }

    @Override
    public ProductLaunchRuntimePreflightRunner.ProbeResult check(
            ProductLaunchRuntimePreflightRunner.ProbeRequest request) {
        return switch (request.kind()) {
            case "TCP" -> tcp(request);
            case "HTTP" -> http(request);
            case "LOGIN" -> login(request);
            case "NONBLANK" -> nonBlank(request);
            case "LLM_API_SMOKE" -> llmApiSmoke(request);
            case "EMBEDDING_API_SMOKE" -> embeddingApiSmoke(request);
            case "TRACE_CONFIG" -> traceConfig(request);
            case "READING_FLAG" -> readingFlag(request);
            case "INVALID_CONFIG" -> ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                    List.of("config_invalid(" + request.params().get("key") + ")"),
                    List.of("CONFIG_INVALID"),
                    request.params()
            );
            default -> ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                    List.of("unknown_probe_kind(" + request.kind() + ")"),
                    List.of("CONFIG_INVALID"),
                    Map.of("kind", request.kind())
            );
        };
    }

    private ProductLaunchRuntimePreflightRunner.ProbeResult tcp(
            ProductLaunchRuntimePreflightRunner.ProbeRequest request) {
        String host = String.valueOf(request.params().getOrDefault("host", ""));
        int port = integerValue(request.params().get("port"), -1);
        Map<String, Object> diagnostics = new LinkedHashMap<>(request.params());
        diagnostics.put("timeoutMillis", timeout.toMillis());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Math.toIntExact(Math.min(Integer.MAX_VALUE, timeout.toMillis())));
            diagnostics.put("reachable", true);
            return ProductLaunchRuntimePreflightRunner.ProbeResult.pass(diagnostics);
        } catch (Exception exception) {
            diagnostics.put("reachable", false);
            diagnostics.put("error", exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                    List.of("tcp_unreachable(" + host + ":" + port + ")"),
                    List.of("RUNTIME_UNAVAILABLE"),
                    diagnostics
            );
        }
    }

    private ProductLaunchRuntimePreflightRunner.ProbeResult http(
            ProductLaunchRuntimePreflightRunner.ProbeRequest request) {
        String url = String.valueOf(request.params().getOrDefault("url", request.target()));
        List<Integer> acceptedStatuses = acceptedStatuses(request.params().get("acceptedStatuses"));
        Map<String, Object> diagnostics = new LinkedHashMap<>(request.params());
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .version(HttpClient.Version.HTTP_1_1)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            diagnostics.put("status", response.statusCode());
            if (acceptedStatuses.contains(response.statusCode())) {
                String requiredBodyContains = String.valueOf(request.params().getOrDefault("requiredBodyContains", ""));
                if (!requiredBodyContains.isBlank()) {
                    boolean bodyMarkerPresent = response.body() != null && response.body().contains(requiredBodyContains);
                    diagnostics.put("bodyMarkerPresent", bodyMarkerPresent);
                    if (!bodyMarkerPresent) {
                        return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                                List.of("http_body_marker_missing(" + url + ")"),
                                List.of("RUNTIME_UNAVAILABLE"),
                                diagnostics
                        );
                    }
                }
                return ProductLaunchRuntimePreflightRunner.ProbeResult.pass(diagnostics);
            }
            return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                    List.of("http_status_unaccepted(" + response.statusCode() + ")"),
                    List.of("RUNTIME_UNAVAILABLE"),
                    diagnostics
            );
        } catch (Exception exception) {
            diagnostics.put("error", exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                    List.of("http_unreachable(" + url + ")"),
                    List.of("RUNTIME_UNAVAILABLE"),
                    diagnostics
            );
        }
    }

    private ProductLaunchRuntimePreflightRunner.ProbeResult login(
            ProductLaunchRuntimePreflightRunner.ProbeRequest request) {
        String apiBase = String.valueOf(request.params().getOrDefault("apiBase", request.target()));
        String username = String.valueOf(request.params().getOrDefault("username", ""));
        Map<String, Object> diagnostics = new LinkedHashMap<>(request.params());
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("username", username);
            body.put("password", request.secret());
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(apiBase) + "/users/login"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            diagnostics.put("status", response.statusCode());
            JsonNode json = OBJECT_MAPPER.readTree(response.body());
            String token = json.path("data").path("token").asText("");
            diagnostics.put("tokenPresent", !token.isBlank());
            if (response.statusCode() < 300 && !token.isBlank()) {
                return ProductLaunchRuntimePreflightRunner.ProbeResult.pass(diagnostics);
            }
            return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                    List.of("backend_login_failed(status=" + response.statusCode() + ")"),
                    List.of("RUNTIME_UNAVAILABLE"),
                    diagnostics
            );
        } catch (Exception exception) {
            diagnostics.put("error", exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                    List.of("backend_login_unreachable(" + trimTrailingSlash(apiBase) + "/users/login)"),
                    List.of("RUNTIME_UNAVAILABLE"),
                    diagnostics
            );
        }
    }

    private ProductLaunchRuntimePreflightRunner.ProbeResult nonBlank(
            ProductLaunchRuntimePreflightRunner.ProbeRequest request) {
        String key = String.valueOf(request.params().getOrDefault("key", request.target()));
        String value = String.valueOf(request.params().getOrDefault("value", ""));
        Map<String, Object> diagnostics = new LinkedHashMap<>(request.params());
        diagnostics.remove("value");
        diagnostics.put("present", !value.isBlank());
        if (!value.isBlank()) {
            return ProductLaunchRuntimePreflightRunner.ProbeResult.pass(diagnostics);
        }
        return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                List.of("config_missing(" + key + ")"),
                List.of("CONFIG_MISSING"),
                diagnostics
        );
    }

    private ProductLaunchRuntimePreflightRunner.ProbeResult llmApiSmoke(
            ProductLaunchRuntimePreflightRunner.ProbeRequest request) {
        Map<String, Object> diagnostics = new LinkedHashMap<>(request.params());
        String apiBaseUrl = String.valueOf(request.params().getOrDefault("apiBaseUrl", request.target()));
        String model = String.valueOf(request.params().getOrDefault("model", ""));
        if (request.secret().isBlank()) {
            diagnostics.put("keyPresent", false);
            return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                    List.of("llm_api_smoke_missing_key"),
                    List.of("CONFIG_MISSING"),
                    diagnostics
            );
        }
        diagnostics.put("keyPresent", true);
        try {
            Map<String, Object> userMessage = new LinkedHashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", "Respond with the single word OK.");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(userMessage));
            body.put("temperature", 0);
            body.put("max_tokens", 4);

            HttpResponse<String> response = sendProviderPost(
                    providerEndpoint(apiBaseUrl, "/chat/completions"),
                    request.secret(),
                    body
            );
            diagnostics.put("status", response.statusCode());
            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                return providerFailure("llm_api_smoke_rejected(status=" + response.statusCode() + ")",
                        "CONFIG_INVALID", diagnostics);
            }
            if (response.statusCode() >= 500) {
                return providerFailure("llm_api_smoke_unavailable(status=" + response.statusCode() + ")",
                        "PROVIDER_UNAVAILABLE", diagnostics);
            }
            JsonNode json = OBJECT_MAPPER.readTree(response.body());
            String content = json.path("choices").path(0).path("message").path("content").asText("");
            diagnostics.put("assistantMessagePresent", !content.isBlank());
            if (response.statusCode() < 300 && !content.isBlank()) {
                return ProductLaunchRuntimePreflightRunner.ProbeResult.pass(diagnostics);
            }
            return providerFailure("llm_api_smoke_malformed_response", "PROVIDER_UNAVAILABLE", diagnostics);
        } catch (Exception exception) {
            diagnostics.put("error", exception.getClass().getSimpleName());
            return providerFailure("llm_api_smoke_unreachable", "PROVIDER_UNAVAILABLE", diagnostics);
        }
    }

    private ProductLaunchRuntimePreflightRunner.ProbeResult embeddingApiSmoke(
            ProductLaunchRuntimePreflightRunner.ProbeRequest request) {
        Map<String, Object> diagnostics = new LinkedHashMap<>(request.params());
        String apiBaseUrl = String.valueOf(request.params().getOrDefault("apiBaseUrl", request.target()));
        String model = String.valueOf(request.params().getOrDefault("model", ""));
        if (request.secret().isBlank()) {
            diagnostics.put("keyPresent", false);
            return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                    List.of("embedding_api_smoke_missing_key"),
                    List.of("CONFIG_MISSING"),
                    diagnostics
            );
        }
        diagnostics.put("keyPresent", true);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", List.of("PaperLoom launch readiness smoke"));
            body.put("encoding_format", "float");
            Integer dimension = integerValue(request.params().get("dimension"), null);
            if (dimension != null && dimension > 0) {
                body.put("dimension", dimension);
            }

            HttpResponse<String> response = sendProviderPost(
                    providerEndpoint(apiBaseUrl, "/embeddings"),
                    request.secret(),
                    body
            );
            diagnostics.put("status", response.statusCode());
            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                return providerFailure("embedding_api_smoke_rejected(status=" + response.statusCode() + ")",
                        "CONFIG_INVALID", diagnostics);
            }
            if (response.statusCode() >= 500) {
                return providerFailure("embedding_api_smoke_unavailable(status=" + response.statusCode() + ")",
                        "PROVIDER_UNAVAILABLE", diagnostics);
            }
            JsonNode data = OBJECT_MAPPER.readTree(response.body()).path("data");
            boolean embeddingPresent = data.isArray()
                    && !data.isEmpty()
                    && data.path(0).path("embedding").isArray()
                    && !data.path(0).path("embedding").isEmpty();
            diagnostics.put("embeddingPresent", embeddingPresent);
            if (response.statusCode() < 300 && embeddingPresent) {
                return ProductLaunchRuntimePreflightRunner.ProbeResult.pass(diagnostics);
            }
            return providerFailure("embedding_api_smoke_malformed_response", "PROVIDER_UNAVAILABLE", diagnostics);
        } catch (Exception exception) {
            diagnostics.put("error", exception.getClass().getSimpleName());
            return providerFailure("embedding_api_smoke_unreachable", "PROVIDER_UNAVAILABLE", diagnostics);
        }
    }

    private HttpResponse<String> sendProviderPost(String url, String apiKey, Map<String, Object> body) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    private ProductLaunchRuntimePreflightRunner.ProbeResult providerFailure(
            String failure,
            String failureClass,
            Map<String, Object> diagnostics) {
        return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                List.of(failure),
                List.of(failureClass),
                diagnostics
        );
    }

    private ProductLaunchRuntimePreflightRunner.ProbeResult traceConfig(
            ProductLaunchRuntimePreflightRunner.ProbeRequest request) {
        String enabled = String.valueOf(request.params().getOrDefault("enabled", "true"));
        String root = String.valueOf(request.params().getOrDefault("root", ""));
        Map<String, Object> diagnostics = new LinkedHashMap<>(request.params());
        if ("false".equalsIgnoreCase(enabled.trim())) {
            return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                    List.of("trace_disabled"),
                    List.of("CONFIG_MISSING"),
                    diagnostics
            );
        }
        if (root.isBlank()) {
            return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                    List.of("trace_root_missing"),
                    List.of("CONFIG_MISSING"),
                    diagnostics
            );
        }
        return ProductLaunchRuntimePreflightRunner.ProbeResult.pass(diagnostics);
    }

    private ProductLaunchRuntimePreflightRunner.ProbeResult readingFlag(
            ProductLaunchRuntimePreflightRunner.ProbeRequest request) {
        String enabled = String.valueOf(request.params().getOrDefault("enabled", ""));
        Map<String, Object> diagnostics = new LinkedHashMap<>(request.params());
        diagnostics.put("enabledForLaunch", Boolean.parseBoolean(enabled.trim()));
        if (Boolean.parseBoolean(enabled.trim())) {
            return ProductLaunchRuntimePreflightRunner.ProbeResult.pass(diagnostics);
        }
        return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                List.of("reading_phase_flag_disabled"),
                List.of("CONFIG_MISSING"),
                diagnostics
        );
    }

    private static List<Integer> acceptedStatuses(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of(200);
        }
        return list.stream()
                .map(item -> integerValue(item, null))
                .filter(item -> item != null)
                .toList();
    }

    private static Integer integerValue(Object value, Integer fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String providerEndpoint(String apiBaseUrl, String path) {
        String base = trimTrailingSlash(apiBaseUrl);
        if (base.endsWith(path)) {
            return base;
        }
        return base + path;
    }
}
