package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.config.AppAuthProperties;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.RegistrationMode;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.utils.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * UserService 类用于处理用户注册和认证相关的业务逻辑。
 */
@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[A-Za-z])(?=.*\\d).{6,18}$"
    );

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AppAuthProperties appAuthProperties;

    @Autowired
    private InviteCodeService inviteCodeService;

    @Autowired
    private UsageQuotaService usageQuotaService;

    /**
     * 注册新用户。
     *
     * @param username 要注册的用户名
     * @param password 要注册的用户密码
     * @throws CustomException 如果用户名已存在，则抛出异常
     */
    @Transactional
    public void registerUser(String username, String password) {
        registerUser(username, password, null);
    }

    @Transactional
    public void registerUser(String username, String password, String inviteCode) {
        validateRegistrationPolicy(username, inviteCode);
        validatePassword(password);

        // 检查数据库中是否已存在该用户名
        if (userRepository.findByUsername(username).isPresent()) {
            // 若用户名已存在，抛出自定义异常，状态码为 400 Bad Request
            throw new CustomException("Username already exists", HttpStatus.BAD_REQUEST);
        }

        User user = new User();
        user.setUsername(username);
        // 对密码进行加密处理并设置到 User 对象中
        user.setPassword(PasswordUtil.encode(password));
        // 设置用户角色为普通用户
        user.setRole(User.Role.USER);

        userRepository.save(user);

        logger.info("User registered successfully: {}", username);
    }

    private void validateRegistrationPolicy(String username, String inviteCode) {
        RegistrationMode mode = appAuthProperties.getRegistration().getMode();
        boolean inviteRequired = appAuthProperties.getRegistration().isInviteRequired() || mode == RegistrationMode.INVITE_ONLY;

        if (mode == RegistrationMode.CLOSED) {
            logger.warn("Registration blocked because registration mode is CLOSED, username: {}", username);
            throw new CustomException("REGISTRATION_CLOSED", HttpStatus.FORBIDDEN);
        }

        if (inviteRequired) {
            inviteCodeService.consume(inviteCode, username);
        }
    }

    /**
     * 创建管理员用户。
     *
     * @param username 要注册的管理员用户名
     * @param password 要注册的管理员密码
     * @param creatorUsername 创建者的用户名（必须是已存在的管理员）
     * @throws CustomException 如果用户名已存在或创建者不是管理员，则抛出异常
     */
    public void createAdminUser(String username, String password, String creatorUsername) {
        // 验证创建者是否为管理员
        User creator = userRepository.findByUsername(creatorUsername)
                .orElseThrow(() -> new CustomException("Creator not found", HttpStatus.NOT_FOUND));

        if (creator.getRole() != User.Role.ADMIN) {
            throw new CustomException("Only administrators can create admin accounts", HttpStatus.FORBIDDEN);
        }

        // 检查数据库中是否已存在该用户名
        if (userRepository.findByUsername(username).isPresent()) {
            throw new CustomException("Username already exists", HttpStatus.BAD_REQUEST);
        }

        validatePassword(password);

        User adminUser = new User();
        adminUser.setUsername(username);
        adminUser.setPassword(PasswordUtil.encode(password));
        adminUser.setRole(User.Role.ADMIN);
        userRepository.save(adminUser);
    }

    private void validatePassword(String password) {
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new CustomException("密码格式不正确，6-18位字符，必须包含字母和数字", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 对用户进行认证。
     *
     * @param username 要认证的用户名
     * @param password 要认证的用户密码
     * @return 认证成功后返回用户的用户名
     * @throws CustomException 如果用户名或密码无效，则抛出异常
     */
    public String authenticateUser(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("Invalid username or password", HttpStatus.UNAUTHORIZED));
        // 比较输入的密码和数据库中存储的加密密码是否匹配
        if (!PasswordUtil.matches(password, user.getPassword())) {
            // 若不匹配，抛出自定义异常，状态码为 401 Unauthorized
            throw new CustomException("Invalid username or password", HttpStatus.UNAUTHORIZED);
        }
        // 认证成功，返回用户的用户名
        return user.getUsername();
    }

    public boolean isAdminUser(String userId) {
        return resolveUser(userId).getRole() == User.Role.ADMIN;
    }

    /**
     * 获取用户列表，支持分页和过滤
     */
    public Map<String, Object> getUserList(String keyword, Integer status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = size > 0 ? size : 10;
        int pageIndex = safePage - 1;
        Pageable pageable = PageRequest.of(pageIndex, safeSize, Sort.by("createdAt").descending());

        List<User> filteredUsers = userRepository.findAll(Sort.by("createdAt").descending()).stream()
                .filter(user -> matchesUserListFilters(user, keyword, status))
                .toList();

        int start = Math.min((int) pageable.getOffset(), filteredUsers.size());
        int end = Math.min(start + pageable.getPageSize(), filteredUsers.size());
        List<User> pageContent = start < end ? filteredUsers.subList(start, end) : Collections.emptyList();
        Page<User> userPage = new PageImpl<>(pageContent, pageable, filteredUsers.size());

        Map<String, UsageQuotaService.UserUsageSnapshot> usageSnapshots = usageQuotaService.getSnapshots(
                userPage.getContent().stream()
                        .map(user -> String.valueOf(user.getId()))
                        .toList()
        );

        List<Map<String, Object>> userList = userPage.getContent().stream()
                .map(user -> {
                    Map<String, Object> userMap = new HashMap<>();
                    userMap.put("userId", user.getId());
                    userMap.put("username", user.getUsername());
                    userMap.put("role", user.getRole());
                    userMap.put("status", 1);
                    userMap.put("createdAt", user.getCreatedAt());
                    userMap.put("usage", usageSnapshots.getOrDefault(
                            String.valueOf(user.getId()),
                            usageQuotaService.getSnapshot(String.valueOf(user.getId()))
                    ));
                    return userMap;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("content", userList);
        result.put("totalElements", userPage.getTotalElements());
        result.put("totalPages", userPage.getTotalPages());
        result.put("size", userPage.getSize());
        result.put("number", userPage.getNumber() + 1);

        return result;
    }

    private boolean matchesUserListFilters(User user, String keyword, Integer status) {

        if (keyword != null && !keyword.isEmpty() && !user.getUsername().contains(keyword)) {
            return false;
        }

        if (status != null && status != 1) {
            return false;
        }

        return true;
    }

    private User resolveUser(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);
            return userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
        }
    }

}
