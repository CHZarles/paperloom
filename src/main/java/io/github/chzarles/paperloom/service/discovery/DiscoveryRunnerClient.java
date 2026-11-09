package io.github.chzarles.paperloom.service.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Java → Discovery Runner 的薄 HTTP 客户端（v3 turns 协议）。
 *
 * <p>Runner 为每个 turn 重建 in-memory pi 会话；Java 提前做好窗口截断、记忆渲染、候选池收集。
 * 4xx 一律按「runner 没有这个 turn」返回 null；网络/5xx 抛出，由轮询失败计数兜底。
 */
@Service
public class DiscoveryRunnerClient {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryRunnerClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String internalToken;

    public DiscoveryRunnerClient(
            ObjectMapper objectMapper,
            @Value("${discovery.runner.base-url:http://127.0.0.1:8092}") String baseUrl,
            @Value("${discovery.runner.internal-token:}") String internalToken) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    /** 受理 turn；4xx/网络失败抛出（调用方回写 FAILED）。 */
    public void startTurn(ObjectNode payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/discovery/turns"))
                    .header("Authorization", "Bearer " + internalToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            JsonNode response = exchange(request);
            if (response == null) {
                throw new IllegalStateException("Runner 4xx 拒绝了 turn 受理");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Discovery Runner 启动调用失败: " + e.getMessage(), e);
        }
    }

    /** 拉取 turn 快照；runner 里已没有这个 turn（4xx）返回 null。 */
    public JsonNode snapshot(String turnId) {
        try {
            HttpRequest request = authorizedBuilder(baseUrl + "/v1/discovery/turns/" + turnId).GET().build();
            return exchange(request);
        } catch (Exception e) {
            throw new IllegalStateException("Discovery Runner 快照调用失败: " + e.getMessage(), e);
        }
    }

    public CompletableFuture<Void> cancelAsync(String turnId) {
        HttpRequest request = authorizedBuilder(baseUrl + "/v1/discovery/turns/" + turnId + "/cancel")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    logger.info("取消 Runner turn => turnId: {}, status: {}", turnId, response.statusCode());
                    return (Void) null;
                })
                .exceptionally(e -> {
                    logger.warn("取消 Runner turn 失败 => turnId: {}, error: {}", turnId, e.getMessage());
                    return null;
                });
    }

    /** 终态后回收 Runner 侧会话；失败只记日志（Runner 有 2 小时孤儿回收兜底）。 */
    public void dispose(String turnId) {
        try {
            exchange(authorizedBuilder(baseUrl + "/v1/discovery/turns/" + turnId).DELETE().build());
        } catch (Exception e) {
            logger.warn("回收 Runner turn 失败（Runner 会自动清理） => turnId: {}, error: {}", turnId, e.getMessage());
        }
    }

    /** turn 受理 payload（字段与 runner.mjs newState 对齐）。 */
    public ObjectNode buildTurnPayload(String turnId, String userId, String kind, String lastMessage,
                                       List<HistoryMessage> history, List<MemoryItem> memory,
                                       List<Map<String, Object>> historyCards) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("turnId", turnId);
        node.put("userId", userId);
        node.put("kind", kind);
        node.put("lastMessage", lastMessage);
        ArrayNode historyNode = node.putArray("history");
        for (HistoryMessage m : history) {
            ObjectNode h = historyNode.addObject();
            h.put("role", m.role());
            h.put("text", m.text());
        }
        ArrayNode memoryNode = node.putArray("memory");
        for (MemoryItem item : memory) {
            ObjectNode h = memoryNode.addObject();
            h.put("kind", item.kind());
            h.put("content", item.content());
        }
        // 窗口内全部候选卡片：runner 用它允许模型跨轮重新 shortlist（档三的「保留仍贴合的」）
        ArrayNode cardsNode = node.putArray("historyCards");
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> card : historyCards) {
            String id = String.valueOf(card.getOrDefault("arxivId", ""));
            if (id.isEmpty() || !seen.add(id)) {
                continue;
            }
            ObjectNode c = cardsNode.addObject();
            c.put("arxivId", id);
            c.put("title", String.valueOf(card.getOrDefault("title", "")));
            c.put("publishedYear", card.get("publishedYear") == null ? -1 : ((Number) card.get("publishedYear")).intValue());
            c.put("libraryStatus", String.valueOf(card.getOrDefault("libraryStatus", "NONE")));
            c.put("summary", String.valueOf(card.getOrDefault("summary", "")));
            ArrayNode authors = c.putArray("authors");
            if (card.get("authors") instanceof List<?> list) {
                list.stream().limit(6).forEach(a -> authors.add(String.valueOf(a)));
            }
            ArrayNode categories = c.putArray("categories");
            if (card.get("categories") instanceof List<?> list) {
                list.stream().limit(6).forEach(a -> categories.add(String.valueOf(a)));
            }
        }
        ArrayNode cardIds = node.putArray("historyCardIds");
        seen.forEach(cardIds::add);
        return node;
    }

    public record HistoryMessage(String role, String text) {
    }

    public record MemoryItem(String kind, String content) {
    }

    private HttpRequest.Builder authorizedBuilder(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + internalToken);
    }

    /** 4xx 记日志后返回 null；5xx 抛出；2xx 返回解析后的 JSON。 */
    private JsonNode exchange(HttpRequest request) throws java.io.IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            logger.warn("Discovery Runner {} => {}: {}", request.uri().getPath(), response.statusCode(),
                    response.body().length() > 200 ? response.body().substring(0, 200) : response.body());
            return null;
        }
        return objectMapper.readTree(response.body());
    }

    /** 供日志/统计使用：runner 的 turn 常量不受 Java 控制，此处不缓存任何运行态。 */
    public Map<String, Object> describe() {
        return Map.of("baseUrl", baseUrl, "tokenConfigured", !internalToken.isBlank());
    }
}
