package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.service.UserService;
import io.github.chzarles.paperloom.utils.JwtUtils;
import io.github.chzarles.paperloom.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;



    // 用户注册接口
    // 接收用户请求体中的用户名和密码，并调用用户服务进行注册
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody UserRequest request) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("USER_REGISTER");
        try {
            if (request.username() == null || request.username().isEmpty() ||
                    request.password() == null || request.password().isEmpty()) {
                LogUtils.logUserOperation("anonymous", "REGISTER", "validation", "FAILED_EMPTY_PARAMS");
                monitor.end("注册失败：参数为空");
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Username and password cannot be empty"));
            }
            
            userService.registerUser(request.username(), request.password(), request.inviteCode());
            LogUtils.logUserOperation(request.username(), "REGISTER", "user_creation", "SUCCESS");
            monitor.end("注册成功");
            
            return ResponseEntity.ok(Map.of("code", 200, "message", "User registered successfully"));
        } catch (CustomException e) {
            LogUtils.logBusinessError("USER_REGISTER", request.username(), "用户注册失败: %s", e, e.getMessage());
            monitor.end("注册失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("USER_REGISTER", request.username(), "用户注册异常: %s", e, e.getMessage());
            monitor.end("注册异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "Internal server error"));
        }
    }

    // 用户登录接口
    // 验证用户身份并生成JWT令牌
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody UserRequest request) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("USER_LOGIN");
        try {
            if (request.username() == null || request.username().isEmpty() ||
                    request.password() == null || request.password().isEmpty()) {
                LogUtils.logUserOperation("anonymous", "LOGIN", "validation", "FAILED_EMPTY_PARAMS");
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Username and password cannot be empty"));
            }
            
            String username = userService.authenticateUser(request.username(), request.password());
            if (username == null) {
                LogUtils.logUserOperation(request.username(), "LOGIN", "authentication", "FAILED_INVALID_CREDENTIALS");
                return ResponseEntity.status(401).body(Map.of("code", 401, "message", "Invalid credentials"));
            }
            
            String token = jwtUtils.generateToken(username);
            String refreshToken = jwtUtils.generateRefreshToken(username);
            LogUtils.logUserOperation(username, "LOGIN", "token_generation", "SUCCESS");
            monitor.end("登录成功");
            
            return ResponseEntity.ok(Map.of("code", 200, "message", "Login successful", "data", Map.of(
                "token", token,
                "refreshToken", refreshToken
            )));
        } catch (CustomException e) {
            LogUtils.logBusinessError("USER_LOGIN", request.username(), "登录失败: %s", e, e.getMessage());
            monitor.end("登录失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("USER_LOGIN", request.username(), "登录异常: %s", e, e.getMessage());
            monitor.end("登录异常: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", "Internal server error"));
        }
    }

    @PostMapping("/guest-login")
    public ResponseEntity<?> guestLogin(
            @CookieValue(name = "paperloom_guest_session", required = false) String guestSession) {
        try {
            User guest = null;
            String refreshToken = guestSession;
            if (guestSession != null && jwtUtils.validateRefreshToken(guestSession)) {
                String username = jwtUtils.extractUsernameFromToken(guestSession);
                guest = userRepository.findByUsername(username)
                        .filter(user -> user.getRole() == User.Role.GUEST)
                        .orElse(null);
            }

            if (guest == null) {
                guest = userService.createGuestUser();
                refreshToken = jwtUtils.generateRefreshToken(guest.getUsername());
            }

            String token = jwtUtils.generateToken(guest.getUsername());
            ResponseCookie sessionCookie = ResponseCookie.from("paperloom_guest_session", refreshToken)
                    .httpOnly(true)
                    .secure(true)
                    .sameSite("Lax")
                    .path("/api/v1/users/guest-login")
                    .maxAge(Duration.ofDays(7))
                    .build();

            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, sessionCookie.toString())
                    .body(Map.of("code", 200, "message", "Login successful", "data", Map.of(
                            "token", token,
                            "refreshToken", refreshToken
                    )));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("GUEST_LOGIN", "guest", "游客登录异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "Internal server error"));
        }
    }

    // 获取当前用户信息
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@RequestHeader("Authorization") String token) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_USER_INFO");
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "GET_USER_INFO", "token_validation", "FAILED_INVALID_TOKEN");
                monitor.end("获取用户信息失败：无效token");
                throw new CustomException("Invalid token", HttpStatus.UNAUTHORIZED);
            }

            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

            // 手动构建返回对象，不包含 password 字段
            Map<String, Object> displayUserData = new LinkedHashMap<>();
            displayUserData.put("id", user.getId());
            displayUserData.put("username", user.getUsername());
            displayUserData.put("role", user.getRole());
            displayUserData.put("createdAt", user.getCreatedAt());
            displayUserData.put("updatedAt", user.getUpdatedAt());

            LogUtils.logUserOperation(username, "GET_USER_INFO", "user_profile", "SUCCESS");
            monitor.end("获取用户信息成功");

            // 返回响应
            return ResponseEntity.ok(Map.of("code", 200, "message", "Get user detail successful", "data", displayUserData));
        } catch (CustomException e) {
            LogUtils.logBusinessError("GET_USER_INFO", username, "获取用户信息失败: %s", e, e.getMessage());
            monitor.end("获取用户信息失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_USER_INFO", username, "获取用户信息异常: %s", e, e.getMessage());
            monitor.end("获取用户信息异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "Internal server error"));
        }
    }
    
    // 用户登出接口
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String token) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("USER_LOGOUT");
        String username = null;
        try {
            if (token == null || !token.startsWith("Bearer ")) {
                LogUtils.logUserOperation("anonymous", "LOGOUT", "validation", "FAILED_INVALID_TOKEN");
                monitor.end("登出失败：token格式无效");
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Invalid token format"));
            }

            String jwtToken = token.replace("Bearer ", "");
            username = jwtUtils.extractUsernameFromToken(jwtToken);
            
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "LOGOUT", "token_extraction", "FAILED_NO_USERNAME");
                monitor.end("登出失败：无法提取用户名");
                return ResponseEntity.status(401).body(Map.of("code", 401, "message", "Invalid token"));
            }

            // 使当前token失效
            jwtUtils.invalidateToken(jwtToken);
            
            LogUtils.logUserOperation(username, "LOGOUT", "token_invalidation", "SUCCESS");
            monitor.end("登出成功");

            return ResponseEntity.ok(Map.of("code", 200, "message", "Logout successful"));
        } catch (Exception e) {
            LogUtils.logBusinessError("USER_LOGOUT", username, "登出异常: %s", e, e.getMessage());
            monitor.end("登出异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "Internal server error"));
        }
    }

}

// 用户请求记录类
record UserRequest(String username, String password, String inviteCode) {}

