package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.service.discovery.DiscoveryChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 智能探索聊天（PRD v3）：单例 thread 的消息入口、快照轮询、取消，以及长期记忆 CRUD。
 *
 * <p>助理只做三件事：发现论文、感知库内论文、帮入库；入库仍走现有逐篇链路与每小时预算。
 */
@RestController
@RequestMapping("/api/v1/discovery/agent")
public class DiscoveryChatController {

    private final DiscoveryChatService chatService;

    public DiscoveryChatController(DiscoveryChatService chatService) {
        this.chatService = chatService;
    }

    public record MessageRequest(String text) {
    }

    public record CancelRequest(String turnId) {
    }

    public record MemoryUpsertRequest(String kind, String content, Boolean active) {
    }

    @PostMapping("/chat/messages")
    public ResponseEntity<Map<String, Object>> postMessage(@RequestBody MessageRequest request,
                                                           @RequestAttribute("userId") String userId) {
        if (request == null || request.text() == null) {
            return error(HttpStatus.BAD_REQUEST, "消息不能为空");
        }
        return ResponseEntity.ok(success(chatService.postMessage(userId, request.text())));
    }

    @GetMapping("/chat")
    public ResponseEntity<Map<String, Object>> getThread(@RequestAttribute("userId") String userId,
                                                         @RequestParam(value = "after", required = false) Long after) {
        return ResponseEntity.ok(success(chatService.getThread(userId, after)));
    }

    @PostMapping("/chat/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@RequestBody CancelRequest request,
                                                      @RequestAttribute("userId") String userId) {
        return ResponseEntity.ok(success(chatService.cancel(userId, request == null ? null : request.turnId())));
    }

    /** 清除全部对话（message rows + 候选池 + 活跃 turn），一键回白板。 */
    @DeleteMapping("/chat")
    public ResponseEntity<Map<String, Object>> clearConversation(@RequestAttribute("userId") String userId) {
        chatService.clearConversation(userId);
        return ResponseEntity.ok(success(Map.of("ok", true)));
    }

    /** 从候选栏移除一篇候选（服务端从消息行 cards 里摘除；不动库内论文） */
    @DeleteMapping("/chat/cards/{arxivId}")
    public ResponseEntity<Map<String, Object>> removeCandidate(
            @RequestAttribute("userId") String userId,
            @PathVariable("arxivId") String arxivId) {
        return ResponseEntity.ok(success(chatService.removeCandidateCard(userId, arxivId)));
    }

    public record ImportRequest(List<String> arxivIds) {
    }

    /** 候选栏一键/单篇入库：用户自决路径，不经模型，门在服务端候选池。 */
    @PostMapping("/chat/import")
    public ResponseEntity<Map<String, Object>> importPapers(@RequestBody ImportRequest request,
                                                             @RequestAttribute("userId") String userId) {
        if (request == null || request.arxivIds() == null) {
            return error(HttpStatus.BAD_REQUEST, "请先勾选要入库的论文");
        }
        return ResponseEntity.ok(success(Map.of("items", chatService.userImport(userId, request.arxivIds()))));
    }

    @GetMapping("/memory")
    public ResponseEntity<Map<String, Object>> listMemory(@RequestAttribute("userId") String userId) {
        return ResponseEntity.ok(success(Map.of("items", chatService.listMemory(userId))));
    }

    @PostMapping("/memory")
    public ResponseEntity<Map<String, Object>> createMemory(@RequestBody MemoryUpsertRequest request,
                                                            @RequestAttribute("userId") String userId) {
        if (request == null) {
            return error(HttpStatus.BAD_REQUEST, "参数不能为空");
        }
        return ResponseEntity.ok(success(chatService.createMemory(userId, request.kind(), request.content())));
    }

    @PutMapping("/memory/{id}")
    public ResponseEntity<Map<String, Object>> updateMemory(@PathVariable("id") Long id,
                                                             @RequestBody MemoryUpsertRequest request,
                                                             @RequestAttribute("userId") String userId) {
        if (request == null) {
            return error(HttpStatus.BAD_REQUEST, "参数不能为空");
        }
        return ResponseEntity.ok(success(
                chatService.updateMemory(userId, id, request.content(), request.active())));
    }

    @DeleteMapping("/memory/{id}")
    public ResponseEntity<Map<String, Object>> deleteMemory(@PathVariable("id") Long id,
                                                             @RequestAttribute("userId") String userId) {
        chatService.deleteMemory(userId, id);
        return ResponseEntity.ok(success(Map.of("ok", true)));
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("code", HttpStatus.OK.value());
        response.put("message", "success");
        response.put("data", data);
        return response;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("code", status.value());
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }
}
