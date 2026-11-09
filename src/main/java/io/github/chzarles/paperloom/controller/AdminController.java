package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.service.ConversationService;
import io.github.chzarles.paperloom.service.InviteCodeService;
import io.github.chzarles.paperloom.service.UsageDashboardService;
import io.github.chzarles.paperloom.service.UsageQuotaService;
import io.github.chzarles.paperloom.service.UserService;
import io.github.chzarles.paperloom.service.UserTokenService;
import io.github.chzarles.paperloom.utils.JwtUtils;
import io.github.chzarles.paperloom.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import static io.github.chzarles.paperloom.utils.DateTimeUtils.parseEndDate;
import static io.github.chzarles.paperloom.utils.DateTimeUtils.parseStartDate;

/**
 * 管理员控制器，提供系统状态、用户活动、用量和配置管理接口。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserService userService;

    @Autowired
    private UserTokenService userTokenService;
    

    @Autowired
    private InviteCodeService inviteCodeService;

    @Autowired
    private UsageDashboardService usageDashboardService;

    @Autowired
    private UsageQuotaService usageQuotaService;

    @Autowired
    private ConversationService conversationService;

    /**
     * 获取所有用户列表
     */
    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers(@RequestHeader("Authorization") String token) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("ADMIN_GET_ALL_USERS");
        String adminUsername = null;
        try {
            adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            User admin = validateAdmin(adminUsername);
            
            LogUtils.logBusiness("ADMIN_GET_ALL_USERS", adminUsername, "管理员开始获取所有用户列表");
            
            List<User> users = userRepository.findAll();
            // 移除敏感信息
            users.forEach(user -> user.setPassword(null));
            
            LogUtils.logUserOperation(adminUsername, "ADMIN_GET_ALL_USERS", "user_list", "SUCCESS");
            LogUtils.logBusiness("ADMIN_GET_ALL_USERS", adminUsername, "成功获取用户列表，用户数量: %d", users.size());
            monitor.end("获取用户列表成功");
            
            return ResponseEntity.ok(Map.of("code", 200, "message", "Get all users successful", "data", users));
        } catch (Exception e) {
            LogUtils.logBusinessError("ADMIN_GET_ALL_USERS", adminUsername, "获取所有用户失败", e);
            monitor.end("获取用户列表失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "Failed to get users: " + e.getMessage()));
        }
    }

    @GetMapping("/usage/overview")
    public ResponseEntity<?> getUsageOverview(
            @RequestHeader("Authorization") String token,
            @RequestParam(defaultValue = "7") int days) {
        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        validateAdmin(adminUsername);

        try {
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "获取用量总览成功",
                    "data", usageDashboardService.buildOverview(days)
            ));
        } catch (Exception e) {
            LogUtils.logBusinessError("ADMIN_GET_USAGE_OVERVIEW", adminUsername, "获取用量总览失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "获取用量总览失败: " + e.getMessage()));
        }
    }

    /**
     * 创建管理员用户
     */
    /**
     * 创建邀请码
     */
    @PostMapping("/invite-codes")
    public ResponseEntity<?> createInviteCode(
            @RequestHeader("Authorization") String token,
            @RequestBody CreateInviteCodeRequest request) {
        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        validateAdmin(adminUsername);

        try {
            var created = inviteCodeService.createInviteCodes(
                    adminUsername,
                    request.code(),
                    request.maxUses(),
                    null,
                    request.count()
            );
            return ResponseEntity.ok(Map.of("code", 200, "message", "邀请码创建成功", "data", created));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "创建邀请码失败: " + e.getMessage()));
        }
    }

    /**
     * 分页查询邀请码
     */
    @GetMapping("/invite-codes")
    public ResponseEntity<?> listInviteCodes(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        validateAdmin(adminUsername);
        return ResponseEntity.ok(Map.of("code", 200, "message", "获取邀请码成功", "data", inviteCodeService.list(enabled, page, size)));
    }

    /**
     * 禁用邀请码
     */
    @PatchMapping("/invite-codes/{id}/disable")
    public ResponseEntity<?> disableInviteCode(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        validateAdmin(adminUsername);

        try {
            inviteCodeService.disable(id, adminUsername);
            return ResponseEntity.ok(Map.of("code", 200, "message", "邀请码已禁用"));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "禁用邀请码失败: " + e.getMessage()));
        }
    }

    /**
     * 删除邀请码
     */
    @DeleteMapping("/invite-codes/{id}")
    public ResponseEntity<?> deleteInviteCode(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        validateAdmin(adminUsername);

        try {
            inviteCodeService.delete(id, adminUsername);
            return ResponseEntity.ok(Map.of("code", 200, "message", "邀请码已删除"));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "删除邀请码失败: " + e.getMessage()));
        }
    }

    /**
     * 编辑邀请码
     */
    @PutMapping("/invite-codes/{id}")
    public ResponseEntity<?> updateInviteCode(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id,
            @RequestBody UpdateInviteCodeRequest request) {
        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        validateAdmin(adminUsername);

        try {
            var updated = inviteCodeService.update(id, adminUsername, request.code(), request.maxUses(), null);
            return ResponseEntity.ok(Map.of("code", 200, "message", "邀请码已更新", "data", updated));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "编辑邀请码失败: " + e.getMessage()));
        }
    }
    
    /**
     * 获取用户列表
     */
    @GetMapping("/users/list")
    public ResponseEntity<?> getUserList(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        validateAdmin(adminUsername);

        try {
            Map<String, Object> usersData = userService.getUserList(keyword, status, page, size);
            return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "获取用户列表成功",
                "data", usersData
            ));
        } catch (CustomException e) {
            LogUtils.logBusinessError("ADMIN_GET_USER_LIST", adminUsername, "获取用户列表失败: %s", e, e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("ADMIN_GET_USER_LIST", adminUsername, "获取用户列表异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "获取用户列表失败: " + e.getMessage()));
        }
    }

    /**
     * 管理员手动追加用户 Token 额度。
     */
    @PostMapping("/users/{userId}/tokens/add")
    public ResponseEntity<?> addUserTokens(
            @RequestHeader("Authorization") String token,
            @PathVariable Long userId,
            @RequestBody AddUserTokenRequest request) {

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        validateAdmin(adminUsername);

        try {
            User targetUser = userRepository.findById(userId)
                    .orElseThrow(() -> new CustomException("目标用户不存在", HttpStatus.NOT_FOUND));

            long llmToken = request.llmToken() == null ? 0L : request.llmToken();
            long embeddingToken = request.embeddingToken() == null ? 0L : request.embeddingToken();
            if (llmToken < 0 || embeddingToken < 0) {
                throw new CustomException("追加 Token 数量不能为负数", HttpStatus.BAD_REQUEST);
            }
            if (llmToken == 0 && embeddingToken == 0) {
                throw new CustomException("请至少追加一种 Token 额度", HttpStatus.BAD_REQUEST);
            }

            String userIdText = String.valueOf(userId);
            String reason = normalizeManualTokenReason(request.reason());
            String remark = "admin=" + adminUsername;
            if (llmToken > 0) {
                userTokenService.addLlmTokens(userIdText, llmToken, reason, remark);
            }
            if (embeddingToken > 0) {
                userTokenService.addEmbeddingTokens(userIdText, embeddingToken, reason, remark);
            }

            LogUtils.logBusiness("ADMIN_ADD_USER_TOKENS", adminUsername,
                    "管理员为用户追加 Token：userId=%d, username=%s, llm=%d, embedding=%d",
                    userId, targetUser.getUsername(), llmToken, embeddingToken);

            Map<String, Object> data = new HashMap<>();
            data.put("userId", userId);
            data.put("username", targetUser.getUsername());
            data.put("usage", usageQuotaService.getSnapshot(userIdText));

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "追加 Token 额度成功",
                    "data", data
            ));
        } catch (CustomException e) {
            LogUtils.logBusinessError("ADMIN_ADD_USER_TOKENS", adminUsername, "追加 Token 额度失败: %s", e, e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("ADMIN_ADD_USER_TOKENS", adminUsername, "追加 Token 额度异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "追加 Token 额度失败: " + e.getMessage()));
        }
    }

    private String normalizeManualTokenReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "管理员手动追加";
        }
        String trimmed = reason.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }
    
    /**
     * 管理员查询所有对话历史
     */
    @GetMapping("/conversation")
    public ResponseEntity<?> getAllConversations(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String userid,
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("ADMIN_GET_ALL_CONVERSATIONS");
        String adminUsername = null;
        try {
            adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            validateAdmin(adminUsername);
            
            LogUtils.logBusiness("ADMIN_GET_ALL_CONVERSATIONS", adminUsername, "管理员开始查询持久化对话历史，目标用户ID: %s, 时间范围: %s 到 %s", userid, start_date, end_date);

            String targetUsername = null;
            if (userid != null && !userid.isEmpty()) {
                try {
                    Long userIdLong = Long.parseLong(userid);
                    Optional<User> targetUser = userRepository.findById(userIdLong);
                    if (targetUser.isPresent()) {
                        targetUsername = targetUser.get().getUsername();
                        LogUtils.logBusiness("ADMIN_GET_ALL_CONVERSATIONS", adminUsername, "找到目标用户: ID=%s, 用户名=%s", userid, targetUsername);
                    } else {
                        LogUtils.logBusiness("ADMIN_GET_ALL_CONVERSATIONS", adminUsername, "目标用户ID不存在: %s", userid);
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(Map.of("code", 404, "message", "目标用户不存在"));
                    }
                } catch (NumberFormatException e) {
                    LogUtils.logBusiness("ADMIN_GET_ALL_CONVERSATIONS", adminUsername, "无效的用户ID格式: %s", userid);
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("code", 400, "message", "无效的用户ID格式"));
                }
            }

            LocalDateTime startDateTime = parseStartDate(start_date);
            LocalDateTime endDateTime = parseEndDate(end_date);
            List<Map<String, Object>> allConversations = conversationService.toMessageHistory(
                    conversationService.getAllConversations(adminUsername, targetUsername, startDateTime, endDateTime),
                    true
            );

            LogUtils.logBusiness("ADMIN_GET_ALL_CONVERSATIONS", adminUsername, "管理员查询完成，共获取到 %d 条历史消息", allConversations.size());
            LogUtils.logUserOperation(adminUsername, "ADMIN_GET_ALL_CONVERSATIONS", "conversation_history", "SUCCESS");
            monitor.end("管理员查询对话历史成功");
            
            // 构建统一响应格式
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取对话历史成功");  
            response.put("data", allConversations);
            return ResponseEntity.ok().body(response);
            
        } catch (CustomException e) {
            LogUtils.logBusinessError("ADMIN_GET_ALL_CONVERSATIONS", adminUsername, "管理员获取对话历史失败: %s", e, e.getMessage());
            monitor.end("管理员获取对话历史失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("ADMIN_GET_ALL_CONVERSATIONS", adminUsername, "管理员获取对话历史异常: %s", e, e.getMessage());
            monitor.end("管理员获取对话历史异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "服务器内部错误: " + e.getMessage()));
        }
    }


    
    /**
     * 验证用户是否为管理员
     */
    private User validateAdmin(String username) {
        if (username == null || username.isEmpty()) {
            throw new CustomException("Invalid token", HttpStatus.UNAUTHORIZED);
        }
        
        User admin = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
        
        if (admin.getRole() != User.Role.ADMIN) {
            throw new CustomException("Unauthorized access: Admin role required", HttpStatus.FORBIDDEN);
        }

        return admin;
    }

}

record AddUserTokenRequest(Long llmToken, Long embeddingToken, String reason) {}

record CreateInviteCodeRequest(String code, Integer maxUses, Integer count) {}

record UpdateInviteCodeRequest(String code, Integer maxUses) {}
