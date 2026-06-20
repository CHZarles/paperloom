package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.OrganizationTag;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.OrganizationTagRepository;
import com.yizhaoqi.smartpai.service.ChatHandler;
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
    private FileUploadRepository fileUploadRepository;
    
    @Autowired
    private OrganizationTagRepository organizationTagRepository;
    
    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ChatHandler chatHandler;

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
            Optional<FileUpload> fileOpt = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(paperId, userId);
            if (fileOpt.isEmpty()) {
                LogUtils.logUserOperation(userId, "DELETE_PAPER", paperId, "FAILED_NOT_FOUND");
                monitor.end("删除失败：论文不存在");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "论文不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            FileUpload file = fileOpt.get();
            
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
            
            LogUtils.logFileOperation(userId, "DELETE", file.getFileName(), paperId, "SUCCESS");
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

            Optional<FileUpload> fileOpt = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(paperId);
            if (fileOpt.isEmpty()) {
                monitor.end("重建失败：论文不存在");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "论文不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            FileUpload file = fileOpt.get();
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
            data.put("paperTitle", file.getFileName());
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

            Optional<FileUpload> fileOpt = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(paperId);
            if (fileOpt.isEmpty()) {
                monitor.end("重试失败：论文不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "code", HttpStatus.NOT_FOUND.value(),
                        "message", "论文不存在"
                ));
            }

            FileUpload file = fileOpt.get();
            if (!file.getUserId().equals(userId) && !"ADMIN".equals(role)) {
                monitor.end("重试失败：权限不足");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "code", HttpStatus.FORBIDDEN.value(),
                        "message", "没有权限重试此论文向量化"
                ));
            }

            FileUpload queuedFile = paperService.enqueueAsyncVectorizationRetry(paperId, userId);
            monitor.end("异步向量化重试任务已提交");

            Map<String, Object> data = new HashMap<>();
            data.put("paperId", queuedFile.getFileMd5());
            data.put("paperTitle", queuedFile.getFileName());
            data.put("vectorizationStatus", queuedFile.getVectorizationStatus());

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
            
            List<FileUpload> files = paperService.getAccessiblePapers(userId, orgTags);
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
            
            List<FileUpload> files = paperService.getUserUploadedPapers(userId);

            LogUtils.logBusiness("GET_USER_UPLOADED_PAPERS", userId, "开始处理论文列表，总数: %d", files.size());
            for (int i = 0; i < files.size(); i++) {
                FileUpload file = files.get(i);
                LogUtils.logBusiness("GET_USER_UPLOADED_PAPERS", userId,
                    "论文[%d]: paperTitle=%s, paperId=%s, sourceFileSizeBytes=%d",
                    i, file.getFileName(), file.getFileMd5(), file.getTotalSize());
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

    private List<Map<String, Object>> convertPapersToResponse(List<FileUpload> files) {
        return files.stream().map(file -> {
            Map<String, Object> dto = new HashMap<>();
            dto.put("paperId", file.getFileMd5());
            dto.put("paperTitle", file.getFileName());
            dto.put("sourceFileName", file.getFileName());
            dto.put("sourceFileSizeBytes", file.getTotalSize());
            dto.put("uploadStatus", file.getStatus());
            dto.put("userId", file.getUserId());
            dto.put("orgTag", file.getOrgTag());
            dto.put("public", file.isPublic());
            dto.put("isPublic", file.isPublic());
            dto.put("createdAt", file.getCreatedAt());
            dto.put("mergedAt", file.getMergedAt());
            dto.put("estimatedEmbeddingTokens", file.getEstimatedEmbeddingTokens());
            dto.put("estimatedChunkCount", file.getEstimatedChunkCount());
            dto.put("actualEmbeddingTokens", file.getActualEmbeddingTokens());
            dto.put("actualChunkCount", file.getActualChunkCount());
            dto.put("vectorizationStatus", file.getVectorizationStatus());
            dto.put("vectorizationErrorMessage", file.getVectorizationErrorMessage());
            dto.put("orgTagName", getOrgTagName(file.getOrgTag()));
            return dto;
        }).collect(Collectors.toList());
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
                Optional<FileUpload> publicFile = fileUploadRepository.findFirstByFileMd5AndIsPublicTrueOrderByCreatedAtDesc(paperId);
                if (publicFile.isEmpty()) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("code", HttpStatus.NOT_FOUND.value());
                    response.put("message", "论文不存在或需要登录访问");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }
                
                FileUpload file = publicFile.get();
                String downloadUrl = paperService.generateDownloadUrl(file.getFileMd5());
                
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
                    "paperId", file.getFileMd5(),
                    "paperTitle", file.getFileName(),
                    "sourceFileName", file.getFileName(),
                    "downloadUrl", downloadUrl,
                    "sourceFileSizeBytes", file.getTotalSize()
                ));
                return ResponseEntity.ok(response);
            }
            
            List<FileUpload> accessibleFiles = paperService.getAccessiblePapers(userId, orgTags);
            
            Optional<FileUpload> targetFile = accessibleFiles.stream()
                    .filter(file -> file.getFileMd5().equals(paperId))
                    .findFirst();
                    
            if (targetFile.isEmpty()) {
                LogUtils.logUserOperation(userId, "DOWNLOAD_PAPER", paperId, "FAILED_NOT_FOUND");
                monitor.end("下载失败：论文不存在或无权限访问");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "论文不存在或无权限访问");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            FileUpload file = targetFile.get();
            
            String downloadUrl = paperService.generateDownloadUrl(file.getFileMd5());
            
            if (downloadUrl == null) {
                LogUtils.logUserOperation(userId, "DOWNLOAD_PAPER", paperId, "FAILED_GENERATE_URL");
                monitor.end("下载失败：无法生成下载链接");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                response.put("message", "无法生成下载链接");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }
            
            LogUtils.logFileOperation(userId, "DOWNLOAD", file.getFileName(), file.getFileMd5(), "SUCCESS");
            LogUtils.logUserOperation(userId, "DOWNLOAD_PAPER", paperId, "SUCCESS");
            monitor.end("论文下载链接生成成功");
            
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "论文下载链接生成成功");
            response.put("data", Map.of(
                "paperId", file.getFileMd5(),
                "paperTitle", file.getFileName(),
                "sourceFileName", file.getFileName(),
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

            FileUpload file = null;

            if (userId == null) {
                file = fileUploadRepository.findFirstByFileMd5AndIsPublicTrueOrderByCreatedAtDesc(paperId)
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
                        file.getFileMd5(),
                        Boolean.TRUE.equals(previewData.get("singlePageMode")) ? "single-page" : "full-document",
                        pageNumber);
                return ResponseEntity.ok(response);
            }

            List<FileUpload> accessibleFiles = paperService.getAccessiblePapers(userId, orgTags);

            Optional<FileUpload> targetFile = accessibleFiles.stream()
                    .filter(f -> f.getFileMd5().equals(paperId))
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
            
            LogUtils.logFileOperation(userId, "PREVIEW", file.getFileName(), file.getFileMd5(), "SUCCESS");
            LogUtils.logUserOperation(userId, "PREVIEW_PAPER", paperId, "SUCCESS");
            monitor.end("论文预览内容获取成功");
            
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "论文预览内容获取成功");
            response.put("data", previewData);
            LogUtils.logBusiness("PREVIEW_PAPER", userId,
                    "论文预览响应已生成: paperId=%s, mode=%s, pageNumber=%s",
                    file.getFileMd5(),
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

            FileUpload file = paperService.getAccessiblePapers(userId, orgTags).stream()
                    .filter(item -> item.getFileMd5().equals(paperId))
                    .findFirst()
                    .orElse(null);

            if (file == null) {
                monitor.end("预览失败：论文不存在或无权限访问");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "论文不存在或无权限访问");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            if (!"pdf".equalsIgnoreCase(getFileExtension(file.getFileName()))) {
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

    @GetMapping("/reference-detail")
    public ResponseEntity<?> getReferenceDetail(
            @RequestParam String sessionId,
            @RequestParam Integer referenceNumber,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_REFERENCE_DETAIL");
        try {
            LogUtils.logBusiness("GET_REFERENCE_DETAIL", "system",
                    "接收到获取引用详情请求: sessionId=%s, referenceNumber=%s", sessionId, referenceNumber);

            ChatHandler.ReferenceInfo detail = chatHandler.getReferenceDetail(sessionId, referenceNumber);
            if (detail == null) {
                monitor.end("未找到引用映射");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "未找到对应的论文引用");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            RequestAuthContext authContext = resolveRequestAuthContext(authorization, null);
            if (authContext.userId() != null) {
                boolean hasAccess = paperService.getAccessiblePapers(authContext.userId(), authContext.orgTags()).stream()
                        .anyMatch(file -> file.getFileMd5().equals(detail.paperId()));
                if (!hasAccess) {
                    monitor.end("获取引用详情失败：无权限访问引用论文");
                    Map<String, Object> response = new HashMap<>();
                    response.put("code", HttpStatus.FORBIDDEN.value());
                    response.put("message", "无权限访问该引用论文");
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
                }
            }

            Map<String, Object> data = new HashMap<>();
            data.put("paperId", detail.paperId());
            data.put("paperTitle", detail.paperTitle());
            data.put("referenceNumber", referenceNumber);
            data.put("pageNumber", detail.pageNumber());
            data.put("anchorText", detail.anchorText());
            data.put("retrievalMode", detail.retrievalMode());
            data.put("retrievalLabel", detail.retrievalLabel());
            data.put("retrievalQuery", detail.retrievalQuery());
            data.put("matchedChunkText", detail.matchedChunkText());
            data.put("evidenceSnippet", detail.evidenceSnippet());
            data.put("score", detail.score());
            data.put("chunkId", detail.chunkId());

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取引用详情成功");
            response.put("data", data);
            monitor.end("获取引用详情成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_REFERENCE_DETAIL", "system",
                    "获取引用详情失败: sessionId=%s, referenceNumber=%s", e, sessionId, referenceNumber);
            monitor.end("获取引用详情失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "获取引用详情失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private Map<String, Object> buildPreviewResponse(FileUpload file, Integer pageNumber, boolean preferSinglePagePreview) {
        String fileName = file.getFileName();
        String extension = getFileExtension(fileName);
        String previewType = getPreviewType(extension);

        Map<String, Object> payload = new HashMap<>();
        payload.put("paperTitle", fileName);
        payload.put("paperId", file.getFileMd5());
        payload.put("sourceFileName", fileName);
        payload.put("sourceFileSizeBytes", file.getTotalSize());
        payload.put("previewType", previewType);

        if ("text".equals(previewType)) {
            String previewContent = paperService.getPaperPreviewContent(file.getFileMd5(), fileName);
            if (previewContent == null) {
                return null;
            }
            payload.put("content", previewContent);
            return payload;
        }

        if ("pdf".equals(previewType) && preferSinglePagePreview && pageNumber != null && pageNumber > 0) {
            payload.put("previewUrl", buildSinglePagePreviewUrl(file.getFileMd5(), pageNumber));
            payload.put("singlePageMode", true);
            payload.put("sourcePageNumber", pageNumber);
            return payload;
        }

        String previewUrl = paperService.generateDownloadUrl(file.getFileMd5());
        if (previewUrl == null) {
            return null;
        }

        payload.put("previewUrl", previewUrl);
        return payload;
    }

    private String buildSinglePagePreviewUrl(String paperId, Integer pageNumber) {
        return "/api/v1/papers/page-preview?paperId="
                + URLEncoder.encode(paperId, StandardCharsets.UTF_8)
                + "&pageNumber="
                + pageNumber;
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
