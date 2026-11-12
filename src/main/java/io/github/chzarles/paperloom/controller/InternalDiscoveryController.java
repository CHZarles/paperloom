package io.github.chzarles.paperloom.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.service.discovery.DiscoveryChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Discovery Runner → Java 内部数据面（v3）：检索转发、批量入库、沉淀记忆。
 *
 * <p>三件事全部有服务端校验：检索绑定运行中的 turn 并记录候选池；入库门在 Java 侧复核；
 * 沉淀通道只收 INSIGHT/PREF。鉴权样式对齐 InternalCorpusController（Bearer + 常量时间比较）。
 */
@RestController
@RequestMapping("/internal/v1/discovery")
public class InternalDiscoveryController {

    private static final Logger logger = LoggerFactory.getLogger(InternalDiscoveryController.class);

    private final DiscoveryChatService chatService;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public InternalDiscoveryController(DiscoveryChatService chatService,
                                       ObjectMapper objectMapper,
                                       @Value("${discovery.runner.internal-token:}") String internalToken) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    public record SearchRequest(String turnId, String userId, String query, Integer start, Integer max,
                                String sort, Integer yearFrom, Integer yearTo) {
    }

    public record ImportRequest(String turnId, String userId, List<String> arxivIds) {
    }

    public record RemoveCandidatesRequest(String turnId, String userId, List<String> arxivIds) {
    }

    public record MemoryItemRequest(String kind, String content) {
    }

    public record MemoryRequest(String userId, List<MemoryItemRequest> items) {
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody SearchRequest request) {
        authorize(authorization);
        requireFields(request.turnId(), request.userId(), request.query());
        return chatService.delegatedSearch(request.turnId(), request.userId(), request.query(),
                request.start(), request.max(), request.sort(), request.yearFrom(), request.yearTo());
    }

    @PostMapping("/import")
    public Map<String, Object> importPapers(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody ImportRequest request) {
        authorize(authorization);
        requireFields(request.turnId(), request.userId());
        if (request.arxivIds() == null || request.arxivIds().isEmpty()) {
            throw new IllegalArgumentException("arxivIds 不能为空");
        }
        return Map.of("items", chatService.delegatedImport(request.turnId(), request.userId(), request.arxivIds()));
    }

    /** 模型侧移除候选：ids 必须落在当前 turn 候选池或窗口历史卡片里，门在服务端。 */
    @PostMapping("/candidates/remove")
    public Map<String, Object> removeCandidates(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody RemoveCandidatesRequest request) {
        authorize(authorization);
        requireFields(request.turnId(), request.userId());
        if (request.arxivIds() == null || request.arxivIds().isEmpty()) {
            throw new IllegalArgumentException("arxivIds 不能为空");
        }
        return chatService.removeCandidateCards(request.turnId(), request.userId(), request.arxivIds());
    }

    @PostMapping("/memory")
    public Map<String, Object> saveMemory(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody MemoryRequest request) {
        authorize(authorization);
        requireFields(request.userId());
        List<Map<String, String>> items = new ArrayList<>();
        for (MemoryItemRequest item : request.items() == null ? List.<MemoryItemRequest>of() : request.items()) {
            items.add(Map.of("kind", item.kind() == null ? "" : item.kind(),
                    "content", item.content() == null ? "" : item.content()));
        }
        return Map.of("items", chatService.distilledMemory(request.userId(), items));
    }

    private void requireFields(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("必填字段缺失");
            }
        }
    }

    private void authorize(String authorization) {
        if (internalToken.isBlank()) {
            throw new UnauthorizedDiscoveryRequest();
        }
        String supplied = authorization == null ? "" : authorization.trim();
        String expected = "Bearer " + internalToken;
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            logger.warn("内部 discovery 请求被拒绝：token 校验失败");
            throw new UnauthorizedDiscoveryRequest();
        }
    }

    @ExceptionHandler(UnauthorizedDiscoveryRequest.class)
    public ResponseEntity<Map<String, Object>> unauthorized(UnauthorizedDiscoveryRequest error) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("code", HttpStatus.UNAUTHORIZED.value(), "message", "unauthorized"));
    }

    @ExceptionHandler({IllegalArgumentException.class, CustomException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", HttpStatus.CONFLICT.value(),
                        "message", error.getMessage() == null ? "invalid request" : error.getMessage()));
    }

    private static final class UnauthorizedDiscoveryRequest extends RuntimeException {
    }
}
