package io.github.chzarles.paperloom.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.OrganizationTag;
import io.github.chzarles.paperloom.model.PaperParserArtifact;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperVisualAsset;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.OrganizationTagRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.service.ChatHandler;
import io.github.chzarles.paperloom.service.ConversationService;
import io.github.chzarles.paperloom.service.PaperCandidateSearchRequest;
import io.github.chzarles.paperloom.service.PaperParserArtifactService;
import io.github.chzarles.paperloom.service.PaperRecommendationCandidate;
import io.github.chzarles.paperloom.service.PaperRecommendationCandidateService;
import io.github.chzarles.paperloom.service.PaperRecommendationSearchRequest;
import io.github.chzarles.paperloom.service.PaperService;
import io.github.chzarles.paperloom.service.PaperVisualAssetService;
import io.github.chzarles.paperloom.utils.LogUtils;
import io.github.chzarles.paperloom.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 论文控制器，处理论文库、预览和证据引用相关请求。
 */
@RestController
@RequestMapping("/api/v1/papers")
public class PaperController {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int PDF_PREVIEW_RANGE_CHUNK_SIZE_BYTES = 256 * 1024;
    private static final int PDF_PREVIEW_MAX_RANGE_SIZE_BYTES = 64 * 1024 * 1024;

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

    @Autowired
    private PaperParserArtifactService paperParserArtifactService;

    @Autowired
    private PaperReadingModelRepository paperReadingModelRepository;

    @Autowired
    private PaperReadingElementRepository paperReadingElementRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private PaperVisualAssetService paperVisualAssetService;

    @Autowired
    private PaperRecommendationCandidateService paperRecommendationCandidateService;

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

            Optional<Paper> fileOpt = "ADMIN".equalsIgnoreCase(role)
                    ? paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId)
                    : paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(paperId, userId);
            if (fileOpt.isEmpty()) {
                LogUtils.logUserOperation(userId, "DELETE_PAPER", paperId, "FAILED_NOT_FOUND");
                monitor.end("删除失败：论文不存在");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "论文不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            Paper file = fileOpt.get();
            if (!file.getUserId().equals(userId) && !"ADMIN".equals(role)) {
                LogUtils.logUserOperation(userId, "DELETE_PAPER", paperId, "FAILED_PERMISSION_DENIED");
                LogUtils.logBusiness("DELETE_PAPER", userId, "用户无权删除论文: paperId=%s, owner=%s", paperId, file.getUserId());
                monitor.end("删除失败：权限不足");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.FORBIDDEN.value());
                response.put("message", "没有权限删除此论文");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }

            paperService.deletePaper(paperId, userId, role);

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
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String readiness) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_ACCESSIBLE_PAPERS");
        try {
            LogUtils.logBusiness("GET_ACCESSIBLE_PAPERS", userId,
                    "接收到获取可访问论文请求: orgTags=%s, query=%s, readiness=%s",
                    orgTags,
                    query,
                    readiness);

            if (hasText(readiness) && !isSupportedReadiness(readiness)) {
                monitor.end("获取可访问论文失败：不支持的 readiness 参数");
                return ResponseEntity.badRequest().body(Map.of(
                        "code", HttpStatus.BAD_REQUEST.value(),
                        "message", "不支持的 readiness 参数: " + readiness
                ));
            }

            Object data;
            long total;
            if (isPagedRequest(page, size) || isCandidateSearchRequest(query, readiness)) {
                PageRequest pageRequest = toPageRequest(page, size);
                Page<Paper> paperPage = isCandidateSearchRequest(query, readiness)
                        ? paperService.searchAccessiblePaperCandidates(userId, orgTags, query, readiness, pageRequest)
                        : paperService.getAccessiblePapersPage(userId, orgTags, pageRequest);
                List<Map<String, Object>> paperData = convertPapersToResponse(paperPage.getContent());
                data = buildPageResponse(paperData, page, size, paperPage.getTotalElements());
                total = paperPage.getTotalElements();
            } else {
                List<Paper> files = paperService.getAccessiblePapers(userId, orgTags);
                data = convertPapersToResponse(files);
                total = files.size();
            }

            LogUtils.logUserOperation(userId, "GET_ACCESSIBLE_PAPERS", "paper_list", "SUCCESS");
            LogUtils.logBusiness("GET_ACCESSIBLE_PAPERS", userId, "成功获取可访问论文: paperCount=%d", total);
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
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String readiness) {
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
        return getAccessiblePapers(userId, orgTags, page, size, query, readiness);
    }

    @PostMapping("/recommendation-candidates")
    public ResponseEntity<?> recommendationCandidates(
            @RequestBody(required = false) PaperRecommendationCandidatesRequest request,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        if (request == null || !hasText(request.queryText())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", HttpStatus.BAD_REQUEST.value(),
                    "message", "queryText 不能为空"
            ));
        }

        PaperRecommendationSearchRequest searchRequest = new PaperRecommendationSearchRequest(
                request.queryText(),
                userId,
                orgTags,
                PaperCandidateSearchRequest.clampLimit(request.paperLimit()),
                PaperRecommendationSearchRequest.clampPerPaperLocationLimit(request.perPaperLocationLimit())
        );
        List<PaperRecommendationCandidate> candidates = paperRecommendationCandidateService.search(searchRequest);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("queryText", searchRequest.queryText());
        data.put("scope", "PRODUCT_LIBRARY");
        data.put("candidates", candidates);

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "获取论文候选成功",
                "data", data
        ));
    }

    public record PaperRecommendationCandidatesRequest(
            String queryText,
            Integer paperLimit,
            Integer perPaperLocationLimit
    ) {
    }

    private boolean isPagedRequest(Integer page, Integer size) {
        return page != null || size != null;
    }

    private boolean isCandidateSearchRequest(String query, String readiness) {
        return hasText(query) || hasText(readiness);
    }

    private boolean isSupportedReadiness(String readiness) {
        return "searchable".equalsIgnoreCase(readiness.trim());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private PageRequest toPageRequest(Integer page, Integer size) {
        int pageNumber = normalizePage(page);
        int pageSize = normalizePageSize(size);
        return PageRequest.of(pageNumber - 1, pageSize);
    }

    private int normalizePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private int normalizePageSize(Integer size) {
        int requested = size == null || size < 1 ? DEFAULT_PAGE_SIZE : size;
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private Map<String, Object> buildPageResponse(List<Map<String, Object>> pageData, Integer page, Integer size, long total) {
        Map<String, Object> result = new HashMap<>();
        result.put("data", pageData);
        result.put("content", pageData);
        result.put("number", normalizePage(page));
        result.put("size", normalizePageSize(size));
        result.put("totalElements", total);
        return result;
    }

    private Map<String, Object> paginateList(List<Map<String, Object>> records, Integer page, Integer size) {
        int pageNumber = normalizePage(page);
        int pageSize = normalizePageSize(size);
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
            Map<String, Object> dto = new LinkedHashMap<>();
            Map<String, Object> parserArtifact = buildParserArtifactStatus(file.getPaperId());
            Map<String, Object> tableAsset = buildTableAssetStatus(file.getPaperId());
            Map<String, Object> figureAsset = buildFigureAssetStatus(file.getPaperId());
            Map<String, Object> formulaAsset = buildFormulaAssetStatus(file.getPaperId());
            Map<String, Object> visualAsset = buildVisualAssetStatus(file.getPaperId());

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
            dto.put("parserArtifact", parserArtifact);
            dto.put("tableAsset", tableAsset);
            dto.put("figureAsset", figureAsset);
            dto.put("formulaAsset", formulaAsset);
            dto.put("visualAsset", visualAsset);
            dto.putAll(buildEvidenceReadiness(file, parserArtifact, visualAsset));
            return dto;
        }).collect(Collectors.toList());
    }

    @GetMapping("/{paperId}/parser-artifact")
    public ResponseEntity<?> getParserArtifact(
            @PathVariable String paperId,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {
        try {
            paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId)
                    .orElseThrow(() -> new RuntimeException("论文不存在"));
            boolean canDownloadArtifact = "ADMIN".equalsIgnoreCase(role)
                    || paperRepository.countByPaperIdAndUserId(paperId, userId) > 0;
            if (!canDownloadArtifact) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "code", HttpStatus.FORBIDDEN.value(),
                        "message", "只有论文拥有者或管理员可以下载 parser artifact"
                ));
            }

            Optional<PaperParserArtifact> artifactOpt = paperParserArtifactService.findLatestParserArtifact(paperId);
            if (artifactOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "code", HttpStatus.NOT_FOUND.value(),
                        "message", "parser artifact 不存在"
                ));
            }

            PaperParserArtifact artifact = artifactOpt.get();
            String downloadUrl = paperParserArtifactService.generateDownloadUrl(artifact);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("paperId", paperId);
            data.put("parserName", artifact.getParserName());
            data.put("parserVersion", artifact.getParserVersion());
            data.put("artifactType", artifact.getArtifactType());
            data.put("downloadUrl", downloadUrl);
            data.put("sizeBytes", artifact.getSizeBytes());
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "parser artifact 下载链接生成成功",
                    "data", data
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "code", HttpStatus.NOT_FOUND.value(),
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/{paperId}/tables")
    public ResponseEntity<?> listTables(
            @PathVariable String paperId,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        Optional<Paper> paper = findAccessiblePaper(paperId, userId, orgTags);
        if (paper.isEmpty()) {
            return paperNotFoundOrForbidden();
        }

        List<Map<String, Object>> tables = currentReadingElements(paperId, List.of("TABLE")).stream()
                .map(this::buildTableResponse)
                .toList();
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "获取论文表格成功",
                "data", tables
        ));
    }

    @GetMapping("/{paperId}/tables/{tableId}")
    public ResponseEntity<?> getTable(
            @PathVariable String paperId,
            @PathVariable String tableId,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        Optional<Paper> paper = findAccessiblePaper(paperId, userId, orgTags);
        if (paper.isEmpty()) {
            return paperNotFoundOrForbidden();
        }

        return currentReadingElement(paperId, tableId, List.of("TABLE"))
                .<ResponseEntity<?>>map(table -> ResponseEntity.ok(Map.of(
                        "code", 200,
                        "message", "获取论文表格成功",
                        "data", buildTableResponse(table)
                )))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "code", HttpStatus.NOT_FOUND.value(),
                        "message", "表格不存在"
                )));
    }

    @GetMapping("/{paperId}/tables/{tableId}/screenshot")
    public ResponseEntity<?> getTableScreenshot(
            @PathVariable String paperId,
            @PathVariable String tableId,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        Optional<Paper> paper = findAccessiblePaper(paperId, userId, orgTags);
        if (paper.isEmpty()) {
            return paperNotFoundOrForbidden();
        }

        return currentReadingElement(paperId, tableId, List.of("TABLE"))
                .flatMap(table -> paperVisualAssetService.findTableCropByReadingElementId(
                        paperId,
                        table.getReadingElementId()
                ))
                .<ResponseEntity<?>>map(asset -> visualAssetUrlResponse(asset, "表格截图链接生成成功"))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "code", HttpStatus.NOT_FOUND.value(),
                        "message", "表格截图不存在"
                )));
    }

    @GetMapping("/{paperId}/pages/{pageNumber}/screenshot")
    public ResponseEntity<?> getPageScreenshot(
            @PathVariable String paperId,
            @PathVariable Integer pageNumber,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        Optional<Paper> paper = findAccessiblePaper(paperId, userId, orgTags);
        if (paper.isEmpty()) {
            return paperNotFoundOrForbidden();
        }

        return paperVisualAssetService.findPageScreenshot(paperId, pageNumber)
                .<ResponseEntity<?>>map(asset -> visualAssetUrlResponse(asset, "页面截图链接生成成功"))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "code", HttpStatus.NOT_FOUND.value(),
                        "message", "页面截图不存在"
                )));
    }

    @GetMapping("/{paperId}/figures")
    public ResponseEntity<?> listFigures(
            @PathVariable String paperId,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        Optional<Paper> paper = findAccessiblePaper(paperId, userId, orgTags);
        if (paper.isEmpty()) {
            return paperNotFoundOrForbidden();
        }

        List<Map<String, Object>> figures = currentReadingElements(paperId, List.of("IMAGE", "CHART")).stream()
                .map(this::buildFigureResponse)
                .toList();
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "获取论文图像成功",
                "data", figures
        ));
    }

    @GetMapping("/{paperId}/figures/{figureId}")
    public ResponseEntity<?> getFigure(
            @PathVariable String paperId,
            @PathVariable String figureId,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        Optional<Paper> paper = findAccessiblePaper(paperId, userId, orgTags);
        if (paper.isEmpty()) {
            return paperNotFoundOrForbidden();
        }

        return currentReadingElement(paperId, figureId, List.of("IMAGE", "CHART"))
                .<ResponseEntity<?>>map(figure -> ResponseEntity.ok(Map.of(
                        "code", 200,
                        "message", "获取论文图像成功",
                        "data", buildFigureResponse(figure)
                )))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "code", HttpStatus.NOT_FOUND.value(),
                        "message", "图像不存在"
                )));
    }

    @GetMapping("/{paperId}/figures/{figureId}/screenshot")
    public ResponseEntity<?> getFigureScreenshot(
            @PathVariable String paperId,
            @PathVariable String figureId,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        Optional<Paper> paper = findAccessiblePaper(paperId, userId, orgTags);
        if (paper.isEmpty()) {
            return paperNotFoundOrForbidden();
        }

        return currentReadingElement(paperId, figureId, List.of("IMAGE", "CHART"))
                .flatMap(figure -> paperVisualAssetService.findFigureCropByReadingElementId(
                        paperId,
                        figure.getReadingElementId()
                ))
                .<ResponseEntity<?>>map(asset -> visualAssetUrlResponse(asset, "图像截图链接生成成功"))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "code", HttpStatus.NOT_FOUND.value(),
                        "message", "图像截图不存在"
                )));
    }

    @GetMapping("/{paperId}/formulas")
    public ResponseEntity<?> listFormulas(
            @PathVariable String paperId,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        Optional<Paper> paper = findAccessiblePaper(paperId, userId, orgTags);
        if (paper.isEmpty()) {
            return paperNotFoundOrForbidden();
        }

        List<Map<String, Object>> formulas = currentReadingElements(paperId, List.of("FORMULA")).stream()
                .map(this::buildFormulaResponse)
                .toList();
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "获取论文公式成功",
                "data", formulas
        ));
    }

    @GetMapping("/{paperId}/formulas/{formulaId}")
    public ResponseEntity<?> getFormula(
            @PathVariable String paperId,
            @PathVariable String formulaId,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {
        Optional<Paper> paper = findAccessiblePaper(paperId, userId, orgTags);
        if (paper.isEmpty()) {
            return paperNotFoundOrForbidden();
        }

        return currentReadingElement(paperId, formulaId, List.of("FORMULA"))
                .<ResponseEntity<?>>map(formula -> ResponseEntity.ok(Map.of(
                        "code", 200,
                        "message", "获取论文公式成功",
                        "data", buildFormulaResponse(formula)
                )))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "code", HttpStatus.NOT_FOUND.value(),
                        "message", "公式不存在"
                )));
    }

    private Map<String, Object> buildParserArtifactStatus(String paperId) {
        Map<String, Object> status = new LinkedHashMap<>();
        Optional<PaperParserArtifact> artifactOpt = paperParserArtifactService.findLatestParserArtifact(paperId);
        status.put("available", artifactOpt.isPresent());
        artifactOpt.ifPresent(artifact -> {
            status.put("parserName", artifact.getParserName());
            status.put("parserVersion", artifact.getParserVersion());
        });
        return status;
    }

    private Map<String, Object> buildTableAssetStatus(String paperId) {
        long tableCount = currentReadingElements(paperId, List.of("TABLE")).size();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("tableCount", tableCount);
        status.put("tableSearchable", tableCount > 0);
        return status;
    }

    private Map<String, Object> buildFigureAssetStatus(String paperId) {
        long figureCount = currentReadingElements(paperId, List.of("IMAGE", "CHART")).size();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("figureCount", figureCount);
        status.put("figureSearchable", figureCount > 0);
        return status;
    }

    private Map<String, Object> buildFormulaAssetStatus(String paperId) {
        long formulaCount = currentReadingElements(paperId, List.of("FORMULA")).size();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("formulaCount", formulaCount);
        status.put("formulaSearchable", formulaCount > 0);
        return status;
    }

    private Map<String, Object> buildVisualAssetStatus(String paperId) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("pageScreenshotCount", paperVisualAssetService.countPageScreenshots(paperId));
        status.put("tableCropCount", paperVisualAssetService.countTableCrops(paperId));
        status.put("figureCropCount", paperVisualAssetService.countFigureCrops(paperId));
        return status;
    }

    private Map<String, Object> buildEvidenceReadiness(
            Paper paper,
            Map<String, Object> parserArtifact,
            Map<String, Object> visualAsset) {
        long pageScreenshotCount = longValue(visualAsset, "pageScreenshotCount");
        boolean pdfEvidenceAvailable = pageScreenshotCount > 0;
        String evidenceAssetLevel = pdfEvidenceAvailable ? "PDF_VISUAL" : "PDF_PENDING_ASSETS";

        List<String> assetWarnings = new ArrayList<>();
        if (!Boolean.TRUE.equals(parserArtifact.get("available"))) {
            assetWarnings.add("parser_artifact_missing");
        }
        if (pageScreenshotCount <= 0) {
            assetWarnings.add("page_screenshots_missing");
        }

        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("sourceType", "PDF");
        readiness.put("evidenceAssetLevel", evidenceAssetLevel);
        readiness.put("assetWarnings", assetWarnings);
        readiness.put("pdfEvidenceAvailable", pdfEvidenceAvailable);
        return readiness;
    }

    private long longValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Long.parseLong(stringValue);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private Optional<Paper> findAccessiblePaper(String paperId, String userId, String orgTags) {
        return paperService.getAccessiblePapers(userId, orgTags).stream()
                .filter(paper -> paper.getPaperId().equals(paperId))
                .findFirst();
    }

    private ResponseEntity<?> paperNotFoundOrForbidden() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "code", HttpStatus.NOT_FOUND.value(),
                "message", "论文不存在或无权限访问"
        ));
    }

    private List<PaperReadingElement> currentReadingElements(String paperId, List<String> elementTypes) {
        return paperReadingModelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId)
                .map(model -> paperReadingElementRepository.findByPaperIdAndModelVersionAndElementTypeInOrderByPageNumberAscReadingOrderAscIdAsc(
                        paperId,
                        model.getModelVersion(),
                        elementTypes
                ))
                .orElse(List.of());
    }

    private Optional<PaperReadingElement> currentReadingElement(String paperId, String elementId, List<String> elementTypes) {
        if (elementId == null || elementId.isBlank()) {
            return Optional.empty();
        }
        return currentReadingElements(paperId, elementTypes).stream()
                .filter(element -> elementId.equals(element.getReadingElementId())
                        || elementId.equals(element.getSourceObjectId())
                        || elementId.equals(element.getParserElementId()))
                .findFirst();
    }

    private Map<String, Object> buildTableResponse(PaperReadingElement table) {
        JsonNode payload = structuredPayload(table);
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("paperId", table.getPaperId());
        dto.put("tableId", table.getReadingElementId());
        dto.put("readingElementId", table.getReadingElementId());
        dto.put("parserTableId", table.getSourceObjectId());
        dto.put("parserElementId", table.getParserElementId());
        dto.put("pageNumber", table.getPageNumber());
        dto.put("caption", table.getCaptionText());
        dto.put("sectionTitle", table.getSectionTitle());
        dto.put("rowCount", nullableInt(payload, "rowCount"));
        dto.put("columnCount", nullableInt(payload, "columnCount"));
        dto.put("tableText", table.getBodyText());
        dto.put("tableMarkdown", textValue(payload, "tableMarkdown"));
        dto.put("searchableText", table.getSearchableText());
        dto.put("bboxJson", table.getBboxJson());
        dto.put("parserName", table.getParserName());
        dto.put("parserVersion", table.getParserVersion());
        dto.put("locationRef", table.getLocationRef());
        dto.put("locationNotCreatedReason", table.getLocationNotCreatedReason());
        dto.put("screenshotAvailable", paperVisualAssetService.findTableCropByReadingElementId(
                table.getPaperId(),
                table.getReadingElementId()
        ).filter(asset -> asset.getObjectKey() != null && !asset.getObjectKey().isBlank()).isPresent());
        return dto;
    }

    private Map<String, Object> buildFigureResponse(PaperReadingElement figure) {
        JsonNode payload = structuredPayload(figure);
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("paperId", figure.getPaperId());
        dto.put("figureId", figure.getReadingElementId());
        dto.put("readingElementId", figure.getReadingElementId());
        dto.put("parserFigureId", figure.getSourceObjectId());
        dto.put("parserElementId", figure.getParserElementId());
        dto.put("elementType", figure.getElementType());
        dto.put("pageNumber", figure.getPageNumber());
        dto.put("caption", figure.getCaptionText());
        dto.put("sectionTitle", figure.getSectionTitle());
        dto.put("figureText", figure.getBodyText());
        dto.put("searchableText", figure.getSearchableText());
        dto.put("bboxJson", figure.getBboxJson());
        dto.put("detectionSource", textValue(payload, "detectionSource"));
        dto.put("confidence", textValue(payload, "confidence"));
        dto.put("captionSource", figure.getCaptionSource());
        dto.put("associationStatus", figure.getAssociationStatus());
        dto.put("attachmentRole", figure.getAttachmentRole());
        dto.put("parentReadingElementId", figure.getParentReadingElementId());
        dto.put("parserName", figure.getParserName());
        dto.put("parserVersion", figure.getParserVersion());
        dto.put("locationRef", figure.getLocationRef());
        dto.put("locationNotCreatedReason", figure.getLocationNotCreatedReason());
        dto.put("screenshotAvailable", paperVisualAssetService.findFigureCropByReadingElementId(
                figure.getPaperId(),
                figure.getReadingElementId()
        ).filter(asset -> asset.getObjectKey() != null && !asset.getObjectKey().isBlank()).isPresent());
        return dto;
    }

    private Map<String, Object> buildFormulaResponse(PaperReadingElement formula) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("paperId", formula.getPaperId());
        dto.put("formulaId", formula.getReadingElementId());
        dto.put("readingElementId", formula.getReadingElementId());
        dto.put("parserFormulaId", formula.getSourceObjectId());
        dto.put("parserElementId", formula.getParserElementId());
        dto.put("pageNumber", formula.getPageNumber());
        dto.put("latex", textValue(structuredPayload(formula), "latex"));
        dto.put("contextText", textValue(structuredPayload(formula), "contextText"));
        dto.put("bodyText", formula.getBodyText());
        dto.put("searchableText", formula.getSearchableText());
        dto.put("sectionTitle", formula.getSectionTitle());
        dto.put("bboxJson", formula.getBboxJson());
        dto.put("parserName", formula.getParserName());
        dto.put("parserVersion", formula.getParserVersion());
        dto.put("locationRef", formula.getLocationRef());
        dto.put("locationNotCreatedReason", formula.getLocationNotCreatedReason());
        return dto;
    }

    private JsonNode structuredPayload(PaperReadingElement element) {
        if (element == null || element.getStructuredPayloadJson() == null || element.getStructuredPayloadJson().isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(element.getStructuredPayloadJson());
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    private ResponseEntity<?> visualAssetUrlResponse(PaperVisualAsset asset, String message) {
        String url = paperVisualAssetService.generateDownloadUrl(asset);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paperId", asset.getPaperId());
        data.put("assetType", asset.getAssetType());
        data.put("pageNumber", asset.getPageNumber());
        data.put("readingElementId", asset.getReadingElementId());
        data.put("sourceObjectId", asset.getSourceObjectId());
        data.put("parserElementId", asset.getParserElementId());
        data.put("parserImagePath", asset.getParserImagePath());
        data.put("assetStatus", asset.getAssetStatus());
        data.put("failureReason", asset.getFailureReason());
        data.put("downloadUrl", url);
        data.put("contentType", asset.getContentType());
        data.put("widthPx", asset.getWidthPx());
        data.put("heightPx", asset.getHeightPx());
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", message,
                "data", data
        ));
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
                String downloadUrl = paperService.generateAttachmentDownloadUrl(file.getPaperId());

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

            String downloadUrl = paperService.generateAttachmentDownloadUrl(file.getPaperId());

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

                Map<String, Object> previewData = buildPreviewResponse(file);
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
                        "论文预览响应已生成: paperId=%s, mode=full-document, pageNumber=%s",
                        file.getPaperId(),
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

            Map<String, Object> previewData = buildPreviewResponse(file);
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
                    "论文预览响应已生成: paperId=%s, mode=full-document, pageNumber=%s",
                    file.getPaperId(),
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

    @GetMapping("/{paperId}/preview/pdf")
    public ResponseEntity<?> previewPdfByPath(
            @PathVariable String paperId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String token) {
        return previewPdfDataByPath(paperId, authorization, token);
    }

    @GetMapping("/{paperId}/preview/pdf-data")
    public ResponseEntity<?> previewPdfDataByPath(
            @PathVariable String paperId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String token) {
        try {
            RequestAuthContext authContext = resolveRequestAuthContext(authorization, token);
            Optional<Paper> targetFile = findPreviewablePaper(paperId, authContext);
            if (targetFile.isEmpty()) {
                return paperNotFoundOrForbidden();
            }

            Paper file = targetFile.get();
            String filename = sanitizePdfFilename(file.getOriginalFilename(), file.getPaperId());

            Map<String, Object> payload = buildPdfPreviewDataPayload(file, filename);

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "PDF 预览数据获取成功",
                    "data", payload
            ));
        } catch (Exception e) {
            LogUtils.logBusinessError("PREVIEW_PDF", "system", "PDF 预览流生成失败: paperId=%s", e, paperId);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "message", "PDF 预览流生成失败: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/{paperId}/preview/pdf-data/range")
    public ResponseEntity<?> previewPdfDataRangeByPath(
            @PathVariable String paperId,
            @RequestParam Long begin,
            @RequestParam Long end,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String token) {
        try {
            RequestAuthContext authContext = resolveRequestAuthContext(authorization, token);
            Optional<Paper> targetFile = findPreviewablePaper(paperId, authContext);
            if (targetFile.isEmpty()) {
                return paperNotFoundOrForbidden();
            }

            if (begin == null || end == null || begin < 0 || end <= begin) {
                return invalidPdfPreviewRange("无效的 PDF 预览范围");
            }

            Paper file = targetFile.get();
            long totalSizeBytes = resolvePdfSourceSizeBytes(file);
            if (begin >= totalSizeBytes) {
                return invalidPdfPreviewRange("PDF 预览范围超出文件长度");
            }

            long normalizedEnd = Math.min(end, totalSizeBytes);
            long requestedLength = normalizedEnd - begin;
            if (requestedLength > PDF_PREVIEW_MAX_RANGE_SIZE_BYTES) {
                return invalidPdfPreviewRange("PDF 预览范围过大");
            }

            byte[] rangeBytes;
            try (InputStream pdfStream = paperService.openMergedPdfRangeStream(file.getPaperId(), begin, requestedLength)) {
                rangeBytes = pdfStream.readNBytes((int) requestedLength);
            }

            long actualEnd = begin + rangeBytes.length;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("paperId", file.getPaperId());
            payload.put("paperTitle", file.getPaperTitle());
            payload.put("originalFilename", sanitizePdfFilename(file.getOriginalFilename(), file.getPaperId()));
            payload.put("contentType", MediaType.APPLICATION_PDF_VALUE);
            payload.put("begin", begin);
            payload.put("end", actualEnd);
            payload.put("offset", begin);
            payload.put("length", rangeBytes.length);
            payload.put("totalSizeBytes", totalSizeBytes);
            payload.put("contentBase64", Base64.getEncoder().encodeToString(rangeBytes));

            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).body(Map.of(
                    "code", 200,
                    "message", "PDF 预览分块数据获取成功",
                    "data", payload
            ));
        } catch (Exception e) {
            LogUtils.logBusinessError("PREVIEW_PDF_RANGE", "system", "PDF 预览分块读取失败: paperId=%s", e, paperId);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "message", "PDF 预览分块读取失败: " + e.getMessage()
            ));
        }
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

    private Map<String, Object> buildPreviewResponse(Paper file) {
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

        if ("pdf".equals(previewType)) {
            String previewDataUrl = buildPdfPreviewDataUrl(file.getPaperId());
            payload.put("previewUrl", previewDataUrl);
            payload.put("previewDataUrl", previewDataUrl);
            return payload;
        }

        String previewUrl = paperService.generateDownloadUrl(file.getPaperId());
        if (previewUrl == null) {
            return null;
        }
        payload.put("previewUrl", previewUrl);
        return payload;
    }

    private Map<String, Object> buildPdfPreviewDataPayload(Paper file, String filename) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paperId", file.getPaperId());
        payload.put("paperTitle", file.getPaperTitle());
        payload.put("originalFilename", filename);
        payload.put("contentType", MediaType.APPLICATION_PDF_VALUE);
        payload.put("sourceFileSizeBytes", resolvePdfSourceSizeBytes(file));
        payload.put("chunkSizeBytes", PDF_PREVIEW_RANGE_CHUNK_SIZE_BYTES);
        payload.put("rangeUrl", buildPdfPreviewDataRangeUrl(file.getPaperId()));
        return payload;
    }

    private String buildPdfPreviewDataUrl(String paperId) {
        String encodedPaperId = UriUtils.encodePathSegment(paperId, StandardCharsets.UTF_8);
        return "/api/v1/papers/" + encodedPaperId + "/preview/pdf-data";
    }

    private String buildPdfPreviewDataRangeUrl(String paperId) {
        return buildPdfPreviewDataUrl(paperId) + "/range";
    }

    private long resolvePdfSourceSizeBytes(Paper file) {
        Long sourceFileSizeBytes = file.getTotalSize();
        if (sourceFileSizeBytes != null && sourceFileSizeBytes > 0) {
            return sourceFileSizeBytes;
        }

        try (InputStream pdfStream = paperService.openMergedPdfStream(file.getPaperId())) {
            return pdfStream.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw new RuntimeException("无法读取 PDF 文件大小", e);
        }
    }

    private ResponseEntity<?> invalidPdfPreviewRange(String message) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).body(Map.of(
                "code", HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value(),
                "message", message
        ));
    }

    private Optional<Paper> findPreviewablePaper(String paperId, RequestAuthContext authContext) {
        if (authContext.userId() == null) {
            return paperRepository.findFirstByPaperIdAndIsPublicTrueOrderByCreatedAtDesc(paperId);
        }
        return findAccessiblePaper(paperId, authContext.userId(), authContext.orgTags());
    }

    private String sanitizePdfFilename(String originalFilename, String fallbackPaperId) {
        String filename = originalFilename;
        if (filename == null || filename.isBlank()) {
            filename = fallbackPaperId;
        }
        if (filename == null || filename.isBlank()) {
            filename = "paper";
        }

        filename = filename.trim()
                .replaceAll("[\\r\\n\\t\\p{Cntrl}]+", " ")
                .replace('/', '_')
                .replace('\\', '_')
                .replace('"', '_')
                .trim();
        filename = filename.replaceAll("\\s+", " ");
        if (filename.isBlank()) {
            filename = "paper";
        }
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            filename = filename + ".pdf";
        }
        return filename;
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
