package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.handler.ChatWebSocketHandler;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.service.ChatHandler;
import io.github.chzarles.paperloom.service.ChatGenerationStateService;
import io.github.chzarles.paperloom.service.ConversationService;
import io.github.chzarles.paperloom.utils.JwtUtils;
import io.github.chzarles.paperloom.utils.LogUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final JwtUtils jwtUtils;
    private final ChatGenerationStateService chatGenerationStateService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ChatHandler chatHandler;
    private final ConversationService conversationService;

    public ChatController(JwtUtils jwtUtils,
                          ChatGenerationStateService chatGenerationStateService,
                          StringRedisTemplate stringRedisTemplate,
                          ChatHandler chatHandler,
                          ConversationService conversationService) {
        this.jwtUtils = jwtUtils;
        this.chatGenerationStateService = chatGenerationStateService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.chatHandler = chatHandler;
        this.conversationService = conversationService;
    }
    
    /**
     * 获取WebSocket停止指令Token
     */
    @GetMapping("/websocket-token")
    public ResponseEntity<?> getWebSocketToken(@RequestHeader("Authorization") String token) {
        try {
            if (token == null || !token.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
            }
            String jwtToken = token.replace("Bearer ", "");
            if (!jwtUtils.validateToken(jwtToken)) {
                return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
            }

            String cmdToken = ChatWebSocketHandler.getInternalCmdToken();
            
            // 检查token是否有效
            if (cmdToken == null || cmdToken.trim().isEmpty()) {
                return ResponseEntity.status(500).body(responseBody(500, "Token生成失败", null));
            }
            
            return ResponseEntity.ok(responseBody(200, "获取WebSocket停止指令Token成功", Map.of("cmdToken", cmdToken)));
            
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_WEBSOCKET_TOKEN", "system", "获取WebSocket Token失败", e);
            return ResponseEntity.status(500).body(responseBody(500, "服务器内部错误：" + e.getMessage(), null));
        }
    }

    @GetMapping("/generation/{generationId}")
    public ResponseEntity<?> getGeneration(
            @PathVariable String generationId,
            @RequestHeader("Authorization") String token) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }

        return ResponseEntity.ok(responseBody(
                200,
                "获取生成状态成功",
                chatGenerationStateService.getGenerationForUser(generationId, userId).orElse(null)
        ));
    }

    @GetMapping("/active-generation")
    public ResponseEntity<?> getActiveGeneration(@RequestHeader("Authorization") String token,
                                                 @RequestParam(value = "clientId", required = false) String clientId) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }
        String normalizedClientId = clientId == null || clientId.isBlank() ? null : clientId.trim();

        return ResponseEntity.ok(responseBody(
                200,
                "获取当前活动生成状态成功",
                normalizedClientId == null
                        ? chatGenerationStateService.getActiveGenerationForUser(userId).orElse(null)
                        : chatGenerationStateService.getActiveGenerationForUserAndClient(userId, normalizedClientId).orElse(null)
        ));
    }

    @PostMapping("/feedback")
    public ResponseEntity<?> submitFeedback(@RequestHeader("Authorization") String token,
                                            @RequestBody FeedbackRequest request) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }

        if (request == null || request.rating() == null || request.rating().isBlank()) {
            return ResponseEntity.badRequest().body(responseBody(400, "rating 不能为空", null));
        }

        String rating = request.rating().trim().toLowerCase(Locale.ROOT);
        if (!"good".equals(rating) && !"bad".equals(rating)) {
            return ResponseEntity.badRequest().body(responseBody(400, "rating 只允许 good 或 bad", null));
        }
        String reason = buildFeedbackReason(request);
        String key = "feedback:" + userId;
        String field = String.valueOf(System.currentTimeMillis());
        String value = reason.isBlank() ? "rating=" + rating : "rating=" + rating + "; reason=" + reason;
        stringRedisTemplate.opsForHash().put(key, field, value);
        return ResponseEntity.ok(responseBody(200, "反馈已记录", Map.of(
                "key", key,
                "field", field,
                "rating", rating,
                "reason", reason
        )));
    }

    @PostMapping("/generation/{generationId}/retry")
    public ResponseEntity<?> retryGeneration(@PathVariable String generationId,
                                             @RequestHeader("Authorization") String token,
                                             @RequestBody(required = false) RetryGenerationRequest request) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }
        try {
            Map<String, Object> started = chatHandler.retryGeneration(
                    userId,
                    generationId,
                    request == null ? null : request.clientId(),
                    request == null ? null : request.reason()
            );
            return ResponseEntity.ok(responseBody(200, "重新生成已开始", started));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(responseBody(400, error.getMessage(), null));
        } catch (IllegalStateException error) {
            return ResponseEntity.status(409).body(responseBody(409, error.getMessage(), null));
        } catch (CustomException error) {
            return ResponseEntity.status(error.getStatus()).body(responseBody(error.getStatus().value(), error.getMessage(), null));
        } catch (Exception error) {
            return ResponseEntity.status(500).body(responseBody(500, "重新生成失败：" + error.getMessage(), null));
        }
    }

    @GetMapping("/answer-slots/{answerSlotId}/revisions")
    public ResponseEntity<?> getAnswerRevisions(@PathVariable Long answerSlotId,
                                                @RequestHeader("Authorization") String token) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }
        return ResponseEntity.ok(responseBody(
                200,
                "获取回答版本成功",
                conversationService.getAnswerRevisions(Long.parseLong(userId), answerSlotId)
        ));
    }

    private String buildFeedbackReason(FeedbackRequest request) {
        StringBuilder reason = new StringBuilder();
        if (request.reason() != null && !request.reason().isBlank()) {
            reason.append(request.reason().trim());
        }
        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            appendReasonPart(reason, "conversationId=" + request.conversationId().trim());
        }
        if (request.generationId() != null && !request.generationId().isBlank()) {
            appendReasonPart(reason, "generationId=" + request.generationId().trim());
        }
        return reason.toString();
    }

    private void appendReasonPart(StringBuilder reason, String part) {
        if (!reason.isEmpty()) {
            reason.append("; ");
        }
        reason.append(part);
    }

    private String extractValidatedUserId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }

        String jwtToken = authorization.replace("Bearer ", "");
        if (!jwtUtils.validateToken(jwtToken)) {
            return null;
        }
        return jwtUtils.extractUserIdFromToken(jwtToken);
    }

    private Map<String, Object> responseBody(int code, String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code);
        response.put("message", message);
        response.put("data", data);
        return response;
    }

    public record FeedbackRequest(
            String rating,
            String reason,
            String conversationId,
            String generationId
    ) {
    }

    public record RetryGenerationRequest(
            String reason,
            String clientId
    ) {
    }

}
