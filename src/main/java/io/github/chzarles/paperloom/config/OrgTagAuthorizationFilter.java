package io.github.chzarles.paperloom.config;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.repository.PaperPublicationRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * 提取请求身份，并对带 paper_id 的读取请求执行个人空间或全局发布授权。
 * 类名暂时保留，避免扩大本轮配置改动。
 */
@Component
public class OrgTagAuthorizationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(OrgTagAuthorizationFilter.class);
    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private PaperRepository paperRepository;

    @Autowired
    private PaperPublicationRepository paperPublicationRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String path = request.getRequestURI();
            String requestToken = extractToken(request);
            setRequestAuthAttributes(request, requestToken, "资源访问");

            // 需要用户ID但不需要资源权限检查的API路径
            // 这些API只需要用户身份验证，不需要对特定资源进行权限检查
            // 控制器方法通过@RequestAttribute("userId")获取用户ID
            if (path.matches(".*/papers/?$") ||
                path.matches(".*/papers/upload/chunk.*") ||
                path.matches(".*/papers/upload/merge.*") ||
                path.matches(".*/papers/upload/status.*") ||
                path.matches(".*/papers/uploads.*") ||
                path.matches(".*/papers/accessible.*") ||
                (path.matches(".*/papers/[a-fA-F0-9]{32}.*") &&
                        ("DELETE".equals(request.getMethod()) || "POST".equals(request.getMethod()) || "PATCH".equals(request.getMethod())))) {

                String operation = "未知操作";
                if (path.matches(".*/papers/?$")) {
                    operation = "获取论文列表";
                } else if (path.contains("/chunk")) {
                    operation = "分片上传";
                } else if (path.contains("/merge")) {
                    operation = "合并分片";
                } else if (path.contains("/status")) {
                    operation = "获取上传状态";
                } else if (path.contains("/uploads")) {
                    operation = "获取用户论文";
                } else if (path.contains("/accessible")) {
                    operation = "获取可访问论文";
                } else if ("DELETE".equals(request.getMethod()) && path.matches(".*/papers/[a-fA-F0-9]{32}.*")) {
                    operation = "删除论文";
                } else if ("POST".equals(request.getMethod()) && path.matches(".*/papers/[a-fA-F0-9]{32}/processing/retry.*")) {
                    operation = "重试论文处理";
                }

                logger.info("处理{}请求: {}", operation, path);

                // 将用户ID和角色设置为请求属性，供控制器方法使用
                if (requestToken != null) {
                    setRequestAuthAttributes(request, requestToken, operation);
                } else {
                    logger.warn("{}请求中未找到有效token", operation);
                }

                filterChain.doFilter(request, response);
                return;
            }

            // 获取路径中的资源ID
            String resourceId = extractResourceIdFromPath(request);

            // 如果URL不含资源ID，直接放行
            if (resourceId == null) {
                logger.debug("未找到资源ID，直接放行");
                filterChain.doFilter(request, response);
                return;
            }

            Optional<Paper> paperResource = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(resourceId);
            if (paperResource.isPresent()) {
                if (paperPublicationRepository.existsByPaperId(resourceId)) {
                    filterChain.doFilter(request, response);
                    return;
                }
                if (requestToken == null) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                String role = jwtUtils.extractRoleFromToken(requestToken);
                if ("ADMIN".equals(role) && path.startsWith("/api/v1/admin/")) {
                    filterChain.doFilter(request, response);
                    return;
                }
                Object requesterId = request.getAttribute("userId");
                if (requesterId != null
                        && paperRepository.countByPaperIdAndUserId(resourceId, requesterId.toString()) > 0) {
                    filterChain.doFilter(request, response);
                    return;
                }
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            logger.debug("论文资源未找到，返回404: {}", resourceId);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        } catch (Exception e) {
            logger.error("组织标签授权过滤器发生错误: {}", e.getMessage(), e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void setRequestAuthAttributes(HttpServletRequest request, String token, String operation) {
        if (token == null || request.getAttribute("userId") != null) {
            return;
        }

        String userId = jwtUtils.extractUserIdFromToken(token);
        String role = jwtUtils.extractRoleFromToken(token);
        String orgTags = jwtUtils.extractOrgTagsFromToken(token);
        if (userId != null) {
            request.setAttribute("userId", userId);
            request.setAttribute("role", role);
            request.setAttribute("orgTags", orgTags);
            logger.debug("为{}请求设置userId属性: {}, role: {}, orgTags: {}", operation, userId, role, orgTags);
        } else {
            logger.warn("{}请求中无法从token提取userId", operation);
        }
    }

    /**
     * 从路径中提取资源ID
     */
    private String extractResourceIdFromPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        logger.debug("提取资源ID，请求路径: {}", path);

        // 提取不同类型资源的ID
        // 1. legacy file resource: /api/v1/files/{fileMd5}
        if (path.matches(".*/files/[^/]+.*")) {
            String fileId = path.replaceAll(".*/files/([^/]+).*", "$1");
            logger.debug("检测到 legacy 文件资源请求，提取ID: {}", fileId);
            return fileId;
        }

        // 2. 论文资源: /api/v1/papers/{paperId}
        if (path.matches(".*/papers/[a-fA-F0-9]{32}.*")) {
            String paperId = path.replaceAll(".*/papers/([a-fA-F0-9]{32}).*", "$1");
            logger.debug("检测到论文资源请求，提取 paperId: {}", paperId);
            return paperId;
        }

        logger.debug("未匹配到任何资源类型，返回null");
        return null;
    }

    /**
     * 从请求头中提取 JWT Token
     */
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

}
