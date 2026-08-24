package io.github.chzarles.paperloom.config;

import io.github.chzarles.paperloom.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class ReadingQueryAudit {

    private static final Logger logger = LoggerFactory.getLogger(ReadingQueryAudit.class);
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,96}");
    private static final Map<String, String> TOOLS_BY_PATH = Map.of(
            "/api/v1/reading/papers/search", "search_papers",
            "/api/v1/reading/papers/get", "get_paper",
            "/api/v1/reading/structure", "get_paper_structure",
            "/api/v1/reading/content/search", "search_paper_content",
            "/api/v1/reading/content/read", "read_paper_content"
    );

    private final UserRepository userRepository;

    public ReadingQueryAudit(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean supports(HttpServletRequest request) {
        return TOOLS_BY_PATH.containsKey(request.getRequestURI());
    }

    public void success(Long principalId, String toolName, String rawRequestId) {
        logger.info("Reading query completed: principal_id={}, tool_name={}, request_id={}, outcome=SUCCESS",
                principal(principalId), toolName, requestId(rawRequestId));
    }

    public void failure(Long principalId, String toolName, String rawRequestId, String outcome) {
        logger.warn("Reading query failed: principal_id={}, tool_name={}, request_id={}, outcome={}",
                principal(principalId), toolName, requestId(rawRequestId), outcome);
    }

    public void rejection(HttpServletRequest request, Authentication authentication, String outcome) {
        String toolName = TOOLS_BY_PATH.get(request.getRequestURI());
        if (toolName == null) {
            return;
        }
        failure(databaseUserId(authentication), toolName, request.getHeader("X-Request-ID"), outcome);
    }

    private Long databaseUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        try {
            return userRepository.findByUsername(authentication.getName())
                    .map(user -> user.getId())
                    .orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String principal(Long principalId) {
        return principalId == null ? "unknown" : String.valueOf(principalId);
    }

    private String requestId(String value) {
        return value != null && REQUEST_ID.matcher(value).matches() ? value : UUID.randomUUID().toString();
    }
}
