package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.ChatHandler;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class ProductReadingLiveWebSocketChatClient
        implements ProductReadingLiveLaunchSmokeRunner.LiveReadingChatClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> PRODUCT_STATE_ITEMS_TYPE = new TypeReference<>() {};

    private final HttpClient httpClient;
    private final String wsBase;
    private final String token;
    private final Duration timeout;

    public ProductReadingLiveWebSocketChatClient(HttpClient httpClient,
                                                String wsBase,
                                                String token,
                                                Duration timeout) {
        this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
        this.wsBase = trimTrailingSlash(wsBase == null || wsBase.isBlank() ? "ws://127.0.0.1:8081/chat" : wsBase);
        this.token = token == null ? "" : token;
        this.timeout = timeout == null ? Duration.ofSeconds(180) : timeout;
    }

    @Override
    public ProductReadingLiveLaunchSmokeRunner.LiveReadingChatResponse ask(
            ProductReadingLiveLaunchSmokeRunner.LiveReadingChatRequest request) {
        try {
            LiveReadingListener listener = new LiveReadingListener();
            WebSocket socket = httpClient.newWebSocketBuilder()
                    .connectTimeout(timeout)
                    .buildAsync(wsUri(), listener)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            socket.sendText(payloadFor(request), true).join();
            ProductReadingLiveLaunchSmokeRunner.LiveReadingChatResponse response = listener.response()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "product-reading-launch-smoke-complete").join();
            return response;
        } catch (Exception exception) {
            throw new IllegalStateException("live Product Reading chat case failed: " + request.caseId(), exception);
        }
    }

    static String payloadFor(ProductReadingLiveLaunchSmokeRunner.LiveReadingChatRequest request) {
        try {
            ProductReadingLiveLaunchSmokeRunner.LiveReadingChatRequest safeRequest = request == null
                    ? new ProductReadingLiveLaunchSmokeRunner.LiveReadingChatRequest("", "", "", Map.of())
                    : request;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "chat");
            payload.put("message", safeRequest.message());
            payload.put("conversationId", safeRequest.conversationId());
            if (!safeRequest.referenceFocus().isEmpty()) {
                payload.put("referenceFocus", safeRequest.referenceFocus());
            }
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalArgumentException("cannot build Product Reading live chat payload", exception);
        }
    }

    private URI wsUri() {
        String encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8);
        String clientId = "product-reading-launch-" + UUID.randomUUID();
        return URI.create(wsBase + "/" + encodedToken + "?clientId=" + clientId);
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static final class LiveReadingListener implements WebSocket.Listener {

        private final CompletableFuture<ProductReadingLiveLaunchSmokeRunner.LiveReadingChatResponse> response =
                new CompletableFuture<>();
        private final StringBuilder frame = new StringBuilder();
        private final StringBuilder markdown = new StringBuilder();
        private final List<String> toolNames = new ArrayList<>();

        CompletableFuture<ProductReadingLiveLaunchSmokeRunner.LiveReadingChatResponse> response() {
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
                if ("calling_tool".equals(message.path("type").asText(""))) {
                    String toolName = message.path("toolName").asText("");
                    if (!toolName.isBlank()) {
                        toolNames.add(toolName);
                    }
                    return;
                }
                if ("completion".equals(message.path("type").asText(""))) {
                    Map<String, Map<String, Object>> rawReferences = message.has("referenceMappings")
                            ? OBJECT_MAPPER.convertValue(
                            message.get("referenceMappings"),
                            new TypeReference<Map<String, Map<String, Object>>>() {}
                    )
                            : Map.of();
                    Map<Integer, ChatHandler.ReferenceInfo> references = toReferenceMappings(rawReferences);
                    Map<String, Object> diagnostics = message.has("diagnostics")
                            ? OBJECT_MAPPER.convertValue(message.get("diagnostics"), new TypeReference<Map<String, Object>>() {})
                            : Map.of();
                    List<Map<String, Object>> productStateItems = message.has("productStateItems")
                            ? OBJECT_MAPPER.convertValue(message.get("productStateItems"), PRODUCT_STATE_ITEMS_TYPE)
                            : List.of();
                    response.complete(new ProductReadingLiveLaunchSmokeRunner.LiveReadingChatResponse(
                            markdown.toString(),
                            references,
                            diagnostics,
                            productStateItems,
                            toolNames
                    ));
                    return;
                }
                if (message.has("error") || "error".equals(message.path("type").asText(""))) {
                    response.completeExceptionally(new IllegalStateException(rawMessage));
                }
            } catch (Exception exception) {
                response.completeExceptionally(exception);
            }
        }
    }

    static Map<Integer, ChatHandler.ReferenceInfo> toReferenceMappings(
            Map<String, ? extends Map<String, ?>> rawMappings) {
        if (rawMappings == null || rawMappings.isEmpty()) {
            return Map.of();
        }
        Map<Integer, ChatHandler.ReferenceInfo> references = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Map<String, ?>> entry : rawMappings.entrySet()) {
            Integer referenceNumber = integerValue(entry.getKey());
            if (referenceNumber == null) {
                continue;
            }
            Map<String, ?> item = entry.getValue() == null ? Map.of() : entry.getValue();
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
                    Boolean.TRUE.equals(item.get("tableScreenshotAvailable")),
                    stringValue(item.get("sourceType")),
                    stringValue(item.get("evidenceAssetLevel")),
                    Boolean.TRUE.equals(item.get("pdfEvidenceAvailable")),
                    Boolean.TRUE.equals(item.get("pageScreenshotAvailable")),
                    Boolean.TRUE.equals(item.get("figureScreenshotAvailable")),
                    stringValue(item.get("citationRef")),
                    stringValue(item.get("evidenceRef")),
                    stringValue(item.get("sourceQuoteRef")),
                    stringList(item.get("assetWarnings"))
            ));
        }
        return Map.copyOf(references);
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

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(String::valueOf)
                .toList();
    }
}
