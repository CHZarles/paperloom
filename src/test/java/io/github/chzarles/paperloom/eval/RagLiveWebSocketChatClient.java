package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.service.ChatHandler;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class RagLiveWebSocketChatClient implements RagLiveBenchmarkRunner.LiveChatClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Map<String, Object>>> REFERENCE_MAP_TYPE = new TypeReference<>() {};
    private final HttpClient httpClient;
    private final String wsBase;
    private final String token;
    private final Duration timeout;

    public RagLiveWebSocketChatClient(HttpClient httpClient, String wsBase, String token, Duration timeout) {
        this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
        this.wsBase = trimTrailingSlash(wsBase == null || wsBase.isBlank() ? "ws://127.0.0.1:8081/chat" : wsBase);
        this.token = token;
        this.timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
    }

    @Override
    public RagLiveBenchmarkRunner.LiveChatResponse ask(RagBenchmarkCase testCase) {
        try {
            LiveChatListener listener = new LiveChatListener();
            WebSocket socket = httpClient.newWebSocketBuilder()
                    .connectTimeout(timeout)
                    .buildAsync(wsUri(), listener)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            socket.sendText(payloadFor(testCase), true).join();
            RagLiveBenchmarkRunner.LiveChatResponse response = listener.response()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "benchmark-complete").join();
            return response;
        } catch (Exception exception) {
            throw new IllegalStateException("live WebSocket benchmark case failed: " + testCase.id(), exception);
        }
    }

    static String payloadFor(RagBenchmarkCase testCase) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "chat");
            payload.put("message", testCase.query());
            Map<String, Object> scope = scopeFor(testCase);
            if (!scope.isEmpty()) {
                payload.put("scope", scope);
            }
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalArgumentException("cannot build chat payload for case: " + testCase.id(), exception);
        }
    }

    static Map<Integer, ChatHandler.ReferenceInfo> toReferenceMappings(Map<String, Map<String, Object>> rawMappings) {
        if (rawMappings == null || rawMappings.isEmpty()) {
            return Map.of();
        }
        Map<Integer, ChatHandler.ReferenceInfo> references = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : rawMappings.entrySet()) {
            Integer referenceNumber = integerValue(entry.getKey());
            if (referenceNumber == null) {
                continue;
            }
            Map<String, Object> item = entry.getValue() == null ? Map.of() : entry.getValue();
            references.put(referenceNumber, new ChatHandler.ReferenceInfo(
                    stringValue(item.get("paperId")),
                    stringValue(item.get("paperTitle")),
                    stringValue(item.get("originalFilename")),
                    integerValue(item.get("pageNumber")),
                    stringValue(item.get("anchorText")),
                    stringValue(item.get("retrievalMode")),
                    stringValue(item.get("retrievalLabel")),
                    stringValue(item.get("retrievalQuery")),
                    stringValue(item.get("matchedChunkText")),
                    stringValue(item.get("evidenceSnippet")),
                    doubleValue(item.get("score")),
                    integerValue(item.get("chunkId")),
                    stringValue(item.get("elementType")),
                    stringValue(item.get("sectionTitle")),
                    integerValue(item.get("sectionLevel")),
                    stringValue(item.get("bboxJson")),
                    stringValue(item.get("parserName")),
                    stringValue(item.get("parserVersion")),
                    stringValue(item.get("sourceKind")),
                    stringValue(item.get("tableId")),
                    stringValue(item.get("figureId")),
                    stringValue(item.get("formulaId")),
                    stringValue(item.get("evidenceRole")),
                    stringValue(item.get("retrievalRoute")),
                    stringValue(item.get("intent")),
                    stringValue(item.get("rankReason")),
                    stringValue(item.get("tableText")),
                    stringValue(item.get("tableMarkdown")),
                    Boolean.TRUE.equals(item.get("tableScreenshotAvailable"))
            ));
        }
        return references;
    }

    private URI wsUri() {
        String encodedToken = URLEncoder.encode(token == null ? "" : token, StandardCharsets.UTF_8);
        String clientId = "rag-eval-" + UUID.randomUUID();
        return URI.create(wsBase + "/" + encodedToken + "?clientId=" + clientId);
    }

    private static Map<String, Object> scopeFor(RagBenchmarkCase testCase) {
        if (testCase == null || testCase.scope() == null) {
            return Map.of();
        }
        Map<String, Object> scope = new LinkedHashMap<>();
        if (!testCase.scope().paperIds().isEmpty()) {
            scope.put("paperIds", testCase.scope().paperIds());
        }
        if (!testCase.scope().paperTitles().isEmpty()) {
            scope.put("paperTitles", testCase.scope().paperTitles());
        }
        if ("REFERENCE_SOURCE".equals(testCase.scopeMode())) {
            Integer referenceNumber = referenceNumberFrom(testCase.query());
            if (referenceNumber != null) {
                scope.put("referenceNumber", referenceNumber);
            }
        }
        return scope;
    }

    private static Integer referenceNumberFrom(String query) {
        if (query == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[(\\d+)]").matcher(query);
        if (!matcher.find()) {
            return null;
        }
        return integerValue(matcher.group(1));
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            String text = stringValue(value);
            return text == null || text.isBlank() ? null : Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            String text = stringValue(value);
            return text == null || text.isBlank() ? null : Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static class LiveChatListener implements WebSocket.Listener {

        private final CompletableFuture<RagLiveBenchmarkRunner.LiveChatResponse> response = new CompletableFuture<>();
        private final StringBuilder frame = new StringBuilder();
        private final StringBuilder markdown = new StringBuilder();

        CompletableFuture<RagLiveBenchmarkRunner.LiveChatResponse> response() {
            return response;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            frame.append(data);
            if (last) {
                handleMessage(frame.toString());
                frame.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            response.completeExceptionally(error);
        }

        private void handleMessage(String rawMessage) {
            try {
                JsonNode message = OBJECT_MAPPER.readTree(rawMessage);
                if (message.has("chunk")) {
                    markdown.append(message.path("chunk").asText(""));
                    return;
                }
                if (message.path("type").asText("").equals("completion")) {
                    Map<String, Map<String, Object>> rawReferences = message.has("referenceMappings")
                            ? OBJECT_MAPPER.convertValue(message.get("referenceMappings"), REFERENCE_MAP_TYPE)
                            : Map.of();
                    Map<String, Object> diagnostics = message.has("diagnostics")
                            ? OBJECT_MAPPER.convertValue(message.get("diagnostics"), new TypeReference<Map<String, Object>>() {})
                            : Map.of();
                    response.complete(new RagLiveBenchmarkRunner.LiveChatResponse(
                            markdown.toString(),
                            toReferenceMappings(rawReferences),
                            diagnostics
                    ));
                    return;
                }
                if (message.has("error") || message.path("type").asText("").equals("error")) {
                    response.completeExceptionally(new IllegalStateException(rawMessage));
                }
            } catch (Exception exception) {
                response.completeExceptionally(exception);
            }
        }
    }
}
