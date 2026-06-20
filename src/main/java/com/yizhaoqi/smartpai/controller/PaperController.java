package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.OrganizationTag;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.OrganizationTagRepository;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.ConversationService;
import com.yizhaoqi.smartpai.service.PaperService;
import com.yizhaoqi.smartpai.utils.LogUtils;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 论文控制器，处理论文库、预览和证据引用相关请求。
 */
@RestController
@RequestMapping("/api/v1/papers")
public class PaperController {

    @Autowired
    private PaperService paperService;

    @Autowired
    private PaperRepository paperRepository;

    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ChatHandler chatHandler;

    @Autowired
    private ConversationService conversationService;

    /**
     * 删除论文及其相关数据。
     *
     * @param paperId 论文标识，当前使用 PDF 内容哈希
     * @param userId 当前用户ID
     * @param role 用户角色
     * @return 删除结果
     */
    @DeleteMapping("/{paperId}")
    public ResponseEntity<?> deletePaper(
            @PathVariable String paperId,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("DELETE_PAPER");
        try {
            LogUtils.logBusiness("DELETE_PAPER", userId, "接收到删除论文请求: paperId=%s, role=%s", paperId, role);

            // 获取论文记录
            Optional<Paper> fileOpt = paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(paperId, userId);
            if (fileOpt.isEmpty()) {
                LogUtils.logUserOperation(userId, "DELETE_PAPER", paperId, "FAILED_NOT_FOUND");
                monitor.end("删除失败：论文不存在");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "论文不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            Paper file = fileOpt.get();

            // 权限检查：只有论文所有者或管理员可以删除
            if (!file.getUserId().equals(userId) && !"ADMIN".equals(role)) {
                LogUtils.logUserOperation(userId, "DELETE_PAPER", paperId, "FAILED_PERMISSION_DENIED");
                LogUtils.logBusiness("DELETE_PAPER", userId, "用户无权删除论文: paperId=%s, owner=%s", paperId, file.getUserId());
                monitor.end("删除失败：权限不足");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.FORBIDDEN.value());
                response.put("message", "没有权限删除此论文");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }

            // 执行删除操作
            paperService.deletePaper(paperId, userId);

            LogUtils.logFileOperation(userId, "DELETE", file.getOriginalFilename(), paperId, "SUCCESS");
            monitor.end("论文删除成功");
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "论文删除成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("DELETE_PAPER", userId, "删除论文失败: paperId=%s", e, paperId);
            monitor.end("删除失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "删除论文失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PostMapping("/{paperId}/reindex")
    public ResponseEntity<?> reindexPaper(
            @PathVariable String paperId,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("REINDEX_PAPER");
        try {
            LogUtils.logBusiness("REINDEX_PAPER", userId, "接收到重建论文索引请求: paperId=%s, role=%s", paperId, role);

            Optional<Paper> fileOpt = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId);
            if (fileOpt.isEmpty()) {
                monitor.end("重建失败：论文不存在");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "论文不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            Paper file = fileOpt.get();
            if (!file.getUserId().equals(userId) && !"ADMIN".equals(role)) {
                monitor.end("重建失败：权限不足");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.FORBIDDEN.value());
                response.put("message", "没有权限重建此论文索引");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }

            var result = paperService.reindexPaper(paperId, userId);
            monitor.end("论文索引重建成功");

            Map<String, Object> data = new HashMap<>();
            data.put("paperId", paperId);
            data.put("paperTitle", file.getPaperTitle());
            data.put("originalFilename", file.getOriginalFilename());
            data.put("actualEmbeddingTokens", result.actualEmbeddingTokens());
            data.put("actualChunkCount", result.actualChunkCount());
            data.put("modelVersion", result.modelVersion());

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "论文索引重建成功");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("REINDEX_PAPER", userId, "重建论文索引失败: paperId=%s", e, paperId);
            monitor.end("重建失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "重建论文索引失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PostMapping("/{paperId}/vectorization/retry")
    public ResponseEntity<?> retryVectorizationAsync(
            @PathVariable String paperId,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RETRY_VECTORIZATION_ASYNC");
        try {
            LogUtils.logBusiness("RETRY_VECTORIZATION_ASYNC", userId, "接收到异步论文向量化重试请求: paperId=%s, role=%s", paperId, role);

            Optional<Paper> fileOpt = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId);
            if (fileOpt.isEmpty()) {
                monitor.end("重试失败：论文不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "code", HttpStatus.NOT_FOUND.value(),
                        "message", "论文不存在"
                ));
            }

            Paper file = fileOpt.get();
            if (!file.getUserId().equals(userId) && !"ADMIN".equals(role)) {
                monitor.end("重试失败：权限不足");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "code", HttpStatus.FORBIDDEN.value(),
                        "message", "没有权限重试此论文向量化"
                ));
            }

            Paper queuedFile = paperService.enqueueAsyncVectorizationRetry(paperId, userId);
            monitor.end("异步向量化重试任务已提交");

            Map<String, Object> data = new HashMap<>();
            data.put("paperId", queuedFile.getPaperId());
            data.put("paperTitle", queuedFile.getPaperTitle());
            data.put("originalFilename", queuedFile.getOriginalFilename());
            data.put("processingStatus", normalizeProcessingStatus(queuedFile.getVectorizationStatus(), queuedFile.getStatus()));

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "已提交论文向量化重试任务",
                    "data", data
            ));
        } catch (Exception e) {
            LogUtils.logBusinessError("RETRY_VECTORIZATION_ASYNC", userId, "异步论文向量化重试失败: paperId=%s", e, paperId);
            monitor.end("异步向量化重试失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "message", "异步论文向量化重试失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 获取用户可访问的所有论文列表。
     *
     * @param userId 当前用户ID
     * @param orgTags 用户所属组织标签
     * @return 可访问的论文列表
     */
    @GetMapping("/accessible")
    public ResponseEntity<?> getAccessiblePapers(
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_ACCESSIBLE_PAPERS");
        try {
            LogUtils.logBusiness("GET_ACCESSIBLE_PAPERS", userId, "接收到获取可访问论文请求: orgTags=%s", orgTags);

            List<Paper> files = paperService.getAccessiblePapers(userId, orgTags);
            List<Map<String, Object>> paperData = convertPapersToResponse(files);
            Object data = (page != null || size != null) ? paginateList(paperData, page, size) : paperData;

            LogUtils.logUserOperation(userId, "GET_ACCESSIBLE_PAPERS", "paper_list", "SUCCESS");
            LogUtils.logBusiness("GET_ACCESSIBLE_PAPERS", userId, "成功获取可访问论文: paperCount=%d", files.size());
            monitor.end("获取可访问论文成功");

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取可访问论文列表成功");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_ACCESSIBLE_PAPERS", userId, "获取可访问论文失败", e);
            monitor.end("获取可访问论文失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "获取可访问论文列表失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping
    public ResponseEntity<?> getPapers(
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags,
            @RequestParam(defaultValue = "accessible") String scope,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        if ("mine".equalsIgnoreCase(scope)) {
            ResponseEntity<?> response = getUserUploadedPapers(userId);
            if (!(response.getBody() instanceof Map<?, ?> body) || page == null && size == null) {
                return response;
            }
            Object data = body.get("data");
            if (data instanceof List<?> list) {
                Map<String, Object> paged = new HashMap<>();
                paged.putAll((Map<String, Object>) body);
                paged.put("data", paginateList((List<Map<String, Object>>) list, page, size));
                return ResponseEntity.status(response.getStatusCode()).body(paged);
            }
            return response;
        }
        return getAccessiblePapers(userId, orgTags, page, size);
    }

    private Map<String, Object> paginateList(List<Map<String, Object>> records, Integer page, Integer size) {
        int pageNumber = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? 10 : size;
        int total = records.size();
        int fromIndex = Math.min((pageNumber - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<Map<String, Object>> pageData = records.subList(fromIndex, toIndex);

        Map<String, Object> result = new HashMap<>();
        result.put("data", pageData);
        result.put("content", pageData);
        result.put("number", pageNumber);
        result.put("size", pageSize);
        result.put("totalElements", total);
        return result;
    }

    /**
     * 获取用户上传的所有论文列表。
     *
     * @param userId 当前用户ID
     * @return 用户上传的论文列表
     */
    @GetMapping("/uploads")
    public ResponseEntity<?> getUserUploadedPapers(
            @RequestAttribute("userId") String userId) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_USER_UPLOADED_PAPERS");
        try {
            LogUtils.logBusiness("GET_USER_UPLOADED_PAPERS", userId, "接收到获取用户上传论文请求");

            List<Paper> files = paperService.getUserUploadedPapers(userId);

            LogUtils.logBusiness("GET_USER_UPLOADED_PAPERS", userId, "开始处理论文列表，总数: %d", files.size());
            for (int i = 0; i < files.size(); i++) {
                Paper file = files.get(i);
                LogUtils.logBusiness("GET_USER_UPLOADED_PAPERS", userId,
                    "论文[%d]: paperTitle=%s, paperId=%s, sourceFileSizeBytes=%d",
                    i, file.getPaperTitle(), file.getPaperId(), file.getTotalSize());
            }

            List<Map<String, Object>> paperData = convertPapersToResponse(files);

            LogUtils.logUserOperation(userId, "GET_USER_UPLOADED_PAPERS", "paper_list", "SUCCESS");
            LogUtils.logBusiness("GET_USER_UPLOADED_PAPERS", userId, "成功获取用户上传论文: paperCount=%d", files.size());
            monitor.end("获取用户上传论文成功");

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取用户上传论文列表成功");
            response.put("data", paperData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_USER_UPLOADED_PAPERS", userId, "获取用户上传论文失败", e);
            monitor.end("获取用户上传论文失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "获取用户上传论文列表失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private List<Map<String, Object>> convertPapersToResponse(List<Paper> files) {
        return files.stream().map(file -> {
            Map<String, Object> dto = new HashMap<>();
            dto.put("paperId", file.getPaperId());
            dto.put("paperTitle", file.getPaperTitle());
            dto.put("originalFilename", file.getOriginalFilename());
            dto.put("sourceFileSizeBytes", file.getTotalSize());
            dto.put("uploadStatus", file.getStatus());
            dto.put("processingStatus", normalizeProcessingStatus(file.getVectorizationStatus(), file.getStatus()));
            dto.put("processingErrorMessage", file.getVectorizationErrorMessage());
            dto.put("userId", file.getUserId());
            dto.put("orgTag", file.getOrgTag());
            dto.put("isPublic", file.isPublic());
            dto.put("createdAt", file.getCreatedAt());
            dto.put("mergedAt", file.getMergedAt());
            dto.put("estimatedEmbeddingTokens", file.getEstimatedEmbeddingTokens());
            dto.put("estimatedChunkCount", file.getEstimatedChunkCount());
            dto.put("actualEmbeddingTokens", file.getActualEmbeddingTokens());
            dto.put("actualChunkCount", file.getActualChunkCount());
            dto.put("authors", file.getAuthors());
            dto.put("publicationYear", file.getPublicationYear());
            dto.put("venue", file.getVenue());
            dto.put("abstractText", file.getAbstractText());
            dto.put("doi", file.getDoi());
            dto.put("arxivId", file.getArxivId());
            dto.put("orgTagName", getOrgTagName(file.getOrgTag()));
            return dto;
        }).collect(Collectors.toList());
    }

    @PatchMapping("/{paperId}/metadata")
    public ResponseEntity<?> updatePaperMetadata(
            @PathVariable String paperId,
            @RequestBody Map<String, Object> request,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("UPDATE_PAPER_METADATA");
        try {
            Paper paper = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId)
                    .orElseThrow(() -> new RuntimeException("论文不存在"));

            if (!paper.getUserId().equals(userId) && !"ADMIN".equals(role)) {
                monitor.end("更新失败：权限不足");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "code", HttpStatus.FORBIDDEN.value(),
                        "message", "没有权限更新此论文元数据"
                ));
            }

            if (request.containsKey("paperTitle")) {
                paper.setPaperTitle(readString(request.get("paperTitle")));
            }
            if (request.containsKey("authors")) {
                paper.setAuthors(readString(request.get("authors")));
            }
            if (request.containsKey("publicationYear")) {
                paper.setPublicationYear(readInteger(request.get("publicationYear")));
            }
            if (request.containsKey("venue")) {
                paper.setVenue(readString(request.get("venue")));
            }
            if (request.containsKey("abstractText")) {
                paper.setAbstractText(readString(request.get("abstractText")));
            }
            if (request.containsKey("doi")) {
                paper.setDoi(readString(request.get("doi")));
            }
            if (request.containsKey("arxivId")) {
                paper.setArxivId(readString(request.get("arxivId")));
            }

            paperRepository.save(paper);
            monitor.end("论文元数据更新成功");
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "论文元数据更新成功",
                    "data", convertPapersToResponse(List.of(paper)).get(0)
            ));
        } catch (Exception e) {
            LogUtils.logBusinessError("UPDATE_PAPER_METADATA", userId, "论文元数据更新失败: paperId=%s", e, paperId);
            monitor.end("论文元数据更新失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "message", "论文元数据更新失败: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/download")
    public ResponseEntity<?> downloadPaper(
            @RequestParam String paperId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String token) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("DOWNLOAD_PAPER");
        try {
            RequestAuthContext authContext = resolveRequestAuthContext(authorization, token);
            String userId = authContext.userId();
            String orgTags = authContext.orgTags();

            LogUtils.logBusiness("DOWNLOAD_PAPER", userId != null ? userId : "anonymous", "接收到论文下载请求: paperId=%s", paperId);

            if (userId == null) {
                Optional<Paper> publicFile = paperRepository.findFirstByPaperIdAndIsPublicTrueOrderByCreatedAtDesc(paperId);
                if (publicFile.isEmpty()) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("code", HttpStatus.NOT_FOUND.value());
                    response.put("message", "论文不存在或需要登录访问");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }

                Paper file = publicFile.get();
                String downloadUrl = paperService.generateDownloadUrl(file.getPaperId());

                if (downloadUrl == null) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                    response.put("message", "无法生成下载链接");
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
                }

                Map<String, Object> response = new HashMap<>();
                response.put("code", 200);
                response.put("message", "论文下载链接生成成功");
                response.put("data", Map.of(
                    "paperId", file.getPaperId(),
                    "paperTitle", file.getPaperTitle(),
                    "originalFilename", file.getOriginalFilename(),
                    "downloadUrl", downloadUrl,
                    "sourceFileSizeBytes", file.getTotalSize()
                ));
                return ResponseEntity.ok(response);
            }

            List<Paper> accessibleFiles = paperService.getAccessiblePapers(userId, orgTags);

            Optional<Paper> targetFile = accessibleFiles.stream()
                    .filter(file -> file.getPaperId().equals(paperId))
                    .findFirst();

            if (targetFile.isEmpty()) {
                LogUtils.logUserOperation(userId, "DOWNLOAD_PAPER", paperId, "FAILED_NOT_FOUND");
                monitor.end("下载失败：论文不存在或无权限访问");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "论文不存在或无权限访问");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            Paper file = targetFile.get();

            String downloadUrl = paperService.generateDownloadUrl(file.getPaperId());

            if (downloadUrl == null) {
                LogUtils.logUserOperation(userId, "DOWNLOAD_PAPER", paperId, "FAILED_GENERATE_URL");
                monitor.end("下载失败：无法生成下载链接");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                response.put("message", "无法生成下载链接");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            LogUtils.logFileOperation(userId, "DOWNLOAD", file.getOriginalFilename(), file.getPaperId(), "SUCCESS");
            LogUtils.logUserOperation(userId, "DOWNLOAD_PAPER", paperId, "SUCCESS");
            monitor.end("论文下载链接生成成功");

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "论文下载链接生成成功");
            response.put("data", Map.of(
                "paperId", file.getPaperId(),
                "paperTitle", file.getPaperTitle(),
                "originalFilename", file.getOriginalFilename(),
                "downloadUrl", downloadUrl,
                "sourceFileSizeBytes", file.getTotalSize()
            ));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            String userId = "unknown";
            try {
                if (token != null && !token.trim().isEmpty()) {
                    userId = jwtUtils.extractUsernameFromToken(token);
                }
            } catch (Exception ignored) {}

            LogUtils.logBusinessError("DOWNLOAD_PAPER", userId, "论文下载失败: paperId=%s", e, paperId);
            monitor.end("下载失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "论文下载失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/preview")
    public ResponseEntity<?> previewPaper(
            @RequestParam String paperId,
            @RequestParam(required = false) Integer pageNumber,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String token) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("PREVIEW_PAPER");
        try {
            RequestAuthContext authContext = resolveRequestAuthContext(authorization, token);
            String userId = authContext.userId();
            String orgTags = authContext.orgTags();

            LogUtils.logBusiness("PREVIEW_PAPER", userId != null ? userId : "anonymous",
                    "接收到论文预览请求: paperId=%s, pageNumber=%s", paperId, pageNumber);

            Paper file = null;

            if (userId == null) {
                file = paperRepository.findFirstByPaperIdAndIsPublicTrueOrderByCreatedAtDesc(paperId)
                        .orElse(null);

                if (file == null) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("code", HttpStatus.NOT_FOUND.value());
                    response.put("message", "论文不存在或需要登录访问");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }

                Map<String, Object> previewData = buildPreviewResponse(file, pageNumber, false);
                if (previewData == null) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                    response.put("message", "无法获取论文预览内容");
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
                }

                Map<String, Object> response = new HashMap<>();
                response.put("code", 200);
                response.put("message", "论文预览内容获取成功");
                response.put("data", previewData);
                LogUtils.logBusiness("PREVIEW_PAPER", "anonymous",
                        "论文预览响应已生成: paperId=%s, mode=%s, pageNumber=%s",
                        file.getPaperId(),
                        Boolean.TRUE.equals(previewData.get("singlePageMode")) ? "single-page" : "full-document",
                        pageNumber);
                return ResponseEntity.ok(response);
            }

            List<Paper> accessibleFiles = paperService.getAccessiblePapers(userId, orgTags);

            Optional<Paper> targetFile = accessibleFiles.stream()
                    .filter(f -> f.getPaperId().equals(paperId))
                    .findFirst();

            if (targetFile.isEmpty()) {
                LogUtils.logUserOperation(userId, "PREVIEW_PAPER", paperId, "FAILED_NOT_FOUND");
                monitor.end("预览失败：论文不存在或无权限访问");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "论文不存在或无权限访问");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            file = targetFile.get();

            Map<String, Object> previewData = buildPreviewResponse(file, pageNumber, true);
            if (previewData == null) {
                LogUtils.logUserOperation(userId, "PREVIEW_PAPER", paperId, "FAILED_GET_CONTENT");
                monitor.end("预览失败：无法获取论文内容");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                response.put("message", "无法获取论文预览内容");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            LogUtils.logFileOperation(userId, "PREVIEW", file.getOriginalFilename(), file.getPaperId(), "SUCCESS");
            LogUtils.logUserOperation(userId, "PREVIEW_PAPER", paperId, "SUCCESS");
            monitor.end("论文预览内容获取成功");

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "论文预览内容获取成功");
            response.put("data", previewData);
            LogUtils.logBusiness("PREVIEW_PAPER", userId,
                    "论文预览响应已生成: paperId=%s, mode=%s, pageNumber=%s",
                    file.getPaperId(),
                    Boolean.TRUE.equals(previewData.get("singlePageMode")) ? "single-page" : "full-document",
                    pageNumber);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            String userId = "unknown";
            try {
                if (token != null && !token.trim().isEmpty()) {
                    userId = jwtUtils.extractUsernameFromToken(token);
                }
            } catch (Exception ignored) {}

            LogUtils.logBusinessError("PREVIEW_PAPER", userId, "论文预览失败: paperId=%s", e, paperId);
            monitor.end("预览失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "论文预览失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/{paperId}/download")
    public ResponseEntity<?> downloadPaperByPath(
            @PathVariable String paperId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String token) {
        return downloadPaper(paperId, authorization, token);
    }

    @GetMapping("/{paperId}/preview")
    public ResponseEntity<?> previewPaperByPath(
            @PathVariable String paperId,
            @RequestParam(required = false) Integer pageNumber,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String token) {
        return previewPaper(paperId, pageNumber, authorization, token);
    }

    @GetMapping("/page-preview")
    public ResponseEntity<?> previewPdfPage(
            @RequestParam String paperId,
            @RequestParam Integer pageNumber,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("PREVIEW_PDF_PAGE");
        try {
            LogUtils.logBusiness("PREVIEW_PDF_PAGE", userId,
                    "接收到论文 PDF 单页预览请求: paperId=%s, pageNumber=%s", paperId, pageNumber);

            Paper file = paperService.getAccessiblePapers(userId, orgTags).stream()
                    .filter(item -> item.getPaperId().equals(paperId))
                    .findFirst()
                    .orElse(null);

            if (file == null) {
                monitor.end("预览失败：论文不存在或无权限访问");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "论文不存在或无权限访问");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            if (!"pdf".equalsIgnoreCase(getFileExtension(file.getOriginalFilename()))) {
                monitor.end("预览失败：仅支持 PDF 单页预览");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.BAD_REQUEST.value());
                response.put("message", "仅支持 PDF 单页预览");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            PaperService.PdfSinglePagePreview preview = paperService.getPdfSinglePagePreview(paperId, pageNumber);
            byte[] pdfBytes = preview.content();
            String cacheStatus = preview.cacheHit() ? "HIT" : "MISS";

            LogUtils.logBusiness("PREVIEW_PDF_PAGE", userId,
                    "论文 PDF 单页预览响应: paperId=%s, pageNumber=%s, cache=%s, contentLength=%s",
                    paperId, pageNumber, cacheStatus, pdfBytes.length);

            monitor.end("论文 PDF 单页预览生成成功");
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=1800")
                    .header(HttpHeaders.ETAG, "\"" + paperId + ":" + pageNumber + "\"")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(pdfBytes.length))
                    .header("X-Preview-Mode", "single-page")
                    .header("X-Preview-Cache", cacheStatus)
                    .header("X-Preview-Page", String.valueOf(pageNumber))
                    .body(pdfBytes);
        } catch (IllegalArgumentException e) {
            monitor.end("预览失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.BAD_REQUEST.value());
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("PREVIEW_PDF_PAGE", userId,
                    "论文 PDF 单页预览失败: paperId=%s, pageNumber=%s", e, paperId, pageNumber);
            monitor.end("预览失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "论文 PDF 单页预览失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/{paperId}/page-preview")
    public ResponseEntity<?> previewPdfPageByPath(
            @PathVariable String paperId,
            @RequestParam Integer pageNumber,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        return previewPdfPage(paperId, pageNumber, userId, orgTags);
    }

    @GetMapping("/reference-detail")
    public ResponseEntity<?> getReferenceDetail(
            @RequestParam Long conversationRecordId,
            @RequestParam Integer referenceNumber,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_REFERENCE_DETAIL");
        try {
            LogUtils.logBusiness("GET_REFERENCE_DETAIL", "system",
                    "接收到获取引用详情请求: conversationRecordId=%s, referenceNumber=%s", conversationRecordId, referenceNumber);

            RequestAuthContext authContext = resolveRequestAuthContext(authorization, null);
            if (authContext.userId() == null) {
                monitor.end("获取引用详情失败：未认证");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.UNAUTHORIZED.value());
                response.put("message", "未认证，无法获取引用详情");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            Long userId = Long.parseLong(authContext.userId());
            Optional<Map<String, Object>> detailOpt = conversationService.findReferenceDetail(userId, conversationRecordId, referenceNumber);
            if (detailOpt.isEmpty()) {
                monitor.end("未找到引用映射");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "未找到对应的论文引用");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            Map<String, Object> data = detailOpt.get();
            String paperId = String.valueOf(data.get("paperId"));
            boolean hasAccess = paperService.getAccessiblePapers(authContext.userId(), authContext.orgTags()).stream()
                    .anyMatch(file -> file.getPaperId().equals(paperId));
            if (!hasAccess) {
                monitor.end("获取引用详情失败：无权限访问引用论文");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.FORBIDDEN.value());
                response.put("message", "无权限访问该引用论文");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取引用详情成功");
            response.put("data", data);
            monitor.end("获取引用详情成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_REFERENCE_DETAIL", "system",
                    "获取引用详情失败: conversationRecordId=%s, referenceNumber=%s", e, conversationRecordId, referenceNumber);
            monitor.end("获取引用详情失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "获取引用详情失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private Map<String, Object> buildPreviewResponse(Paper file, Integer pageNumber, boolean preferSinglePagePreview) {
        String fileName = file.getOriginalFilename();
        String extension = getFileExtension(fileName);
        String previewType = getPreviewType(extension);

        Map<String, Object> payload = new HashMap<>();
        payload.put("paperTitle", file.getPaperTitle());
        payload.put("paperId", file.getPaperId());
        payload.put("originalFilename", fileName);
        payload.put("sourceFileSizeBytes", file.getTotalSize());
        payload.put("previewType", previewType);

        if ("text".equals(previewType)) {
            String previewContent = paperService.getPaperPreviewContent(file.getPaperId(), fileName);
            if (previewContent == null) {
                return null;
            }
            payload.put("content", previewContent);
            return payload;
        }

        if ("pdf".equals(previewType) && preferSinglePagePreview && pageNumber != null && pageNumber > 0) {
            payload.put("previewUrl", buildSinglePagePreviewUrl(file.getPaperId(), pageNumber));
            payload.put("singlePageMode", true);
            payload.put("sourcePageNumber", pageNumber);
            return payload;
        }

        String previewUrl = paperService.generateDownloadUrl(file.getPaperId());
        if (previewUrl == null) {
            return null;
        }

        payload.put("previewUrl", previewUrl);
        return payload;
    }

    private String buildSinglePagePreviewUrl(String paperId, Integer pageNumber) {
        return "/api/v1/papers/"
                + URLEncoder.encode(paperId, StandardCharsets.UTF_8)
                + "/page-preview?pageNumber="
                + pageNumber;
    }

    private String normalizeProcessingStatus(String vectorizationStatus, int uploadStatus) {
        if (vectorizationStatus != null && !vectorizationStatus.isBlank()) {
            return vectorizationStatus;
        }
        if (uploadStatus == Paper.STATUS_UPLOADING) {
            return Paper.VECTORIZATION_STATUS_PENDING;
        }
        if (uploadStatus == Paper.STATUS_MERGING) {
            return Paper.VECTORIZATION_STATUS_PROCESSING;
        }
        if (uploadStatus == Paper.STATUS_COMPLETED) {
            return Paper.VECTORIZATION_STATUS_COMPLETED;
        }
        return Paper.VECTORIZATION_STATUS_PENDING;
    }

    private String readString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Integer readInteger(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private String getPreviewType(String extension) {
        if (extension == null || extension.isEmpty()) {
            return "download";
        }

        String lowerCaseExtension = extension.toLowerCase();
        if ("pdf".equals(lowerCaseExtension)) {
            return "pdf";
        }

        if (List.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg").contains(lowerCaseExtension)) {
            return "image";
        }

        if (List.of("txt", "md", "json", "xml", "csv", "html", "htm", "css", "js", "java", "py", "sql", "yaml", "yml").contains(lowerCaseExtension)) {
            return "text";
        }

        return "download";
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }

        return fileName.substring(dotIndex + 1);
    }

    private RequestAuthContext resolveRequestAuthContext(String authorization, String fallbackToken) {
        String jwtToken = extractBearerToken(authorization);
        if ((jwtToken == null || jwtToken.isBlank()) && fallbackToken != null && !fallbackToken.isBlank()) {
            jwtToken = fallbackToken.trim();
        }

        if (jwtToken == null || jwtToken.isBlank()) {
            return new RequestAuthContext(null, null);
        }

        return new RequestAuthContext(
                jwtUtils.extractUserIdFromToken(jwtToken),
                jwtUtils.extractOrgTagsFromToken(jwtToken)
        );
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null) {
            return null;
        }

        String trimmed = authorization.trim();
        if (trimmed.startsWith("Bearer ")) {
            return trimmed.substring(7);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record RequestAuthContext(String userId, String orgTags) {}

    /**
     * 根据tagId获取tagName
     *
     * @param tagId 组织标签ID
     * @return 组织标签名称，如果找不到则返回原tagId
     */
    private String getOrgTagName(String tagId) {
        if (tagId == null || tagId.isEmpty()) {
            return null;
        }

        try {
            Optional<OrganizationTag> tagOpt = organizationTagRepository.findByTagId(tagId);
            if (tagOpt.isPresent()) {
                return tagOpt.get().getName();
            } else {
                LogUtils.logBusiness("GET_ORG_TAG_NAME", "system", "找不到组织标签: tagId=%s", tagId);
                return tagId; // 如果找不到标签名称，返回原tagId
            }
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_ORG_TAG_NAME", "system", "查询组织标签名称失败: tagId=%s", e, tagId);
            return tagId; // 发生错误时返回原tagId
        }
    }
}
