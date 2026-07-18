package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.config.KafkaConfig;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.PaperProcessingTask;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.OrganizationTag;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.service.FileTypeValidationService;
import io.github.chzarles.paperloom.service.ParseService;
import io.github.chzarles.paperloom.service.UploadService;
import io.github.chzarles.paperloom.service.UserService;
import io.github.chzarles.paperloom.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/papers/upload")
public class PaperUploadController {

    private static final long DEFAULT_CHUNK_SIZE_BYTES = 5L * 1024 * 1024L;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaConfig kafkaConfig;

    @Autowired
    private UserService userService;

    @Autowired
    private PaperRepository paperRepository;

    @Autowired
    private FileTypeValidationService fileTypeValidationService;

    @Autowired
    private ParseService parseService;

    public PaperUploadController(UploadService uploadService, KafkaTemplate<String, Object> kafkaTemplate) {
        this.uploadService = uploadService;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 上传论文 PDF 分片接口。
     *
     * @param paperId 论文标识，当前使用 PDF 内容哈希
     * @param chunkIndex 分片索引，表示当前分片的位置
     * @param totalSize PDF 文件总大小
     * @param paperTitle 论文标题，当前使用 PDF 文件名
     * @param totalChunks 总分片数量
     * @param orgTag 组织标签，如果未指定则使用用户的主组织标签
     * @param isPublic 是否公开，默认为false
     * @param file 分片内容
     * @return 返回包含已上传分片和上传进度的响应
     * @throws IOException 当文件读写发生错误时抛出
     */
    @PostMapping("/chunk")
    public ResponseEntity<Map<String, Object>> uploadChunk(
            @RequestParam("paperId") String paperId,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("totalSize") long totalSize,
            @RequestParam("paperTitle") String paperTitle,
            @RequestParam(value = "totalChunks", required = false) Integer totalChunks,
            @RequestParam(value = "orgTag", required = false) String orgTag,
            @RequestParam(value = "isPublic", required = false, defaultValue = "false") boolean isPublic,
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") String userId) throws IOException {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("UPLOAD_CHUNK");
        try {
            // 文件类型验证（仅在第一个分片时进行验证）
            if (chunkIndex == 0) {
                FileTypeValidationService.FileTypeValidationResult validationResult =
                    fileTypeValidationService.validateFileType(paperTitle);

                LogUtils.logBusiness("UPLOAD_CHUNK", userId, "论文 PDF 类型验证结果: paperTitle=%s, valid=%s, fileType=%s, message=%s",
                        paperTitle, validationResult.isValid(), validationResult.getFileType(), validationResult.getMessage());

                if (!validationResult.isValid()) {
                    LogUtils.logBusinessError("UPLOAD_CHUNK", userId, "论文 PDF 类型验证失败: paperTitle=%s, fileType=%s",
                            new RuntimeException(validationResult.getMessage()), paperTitle, validationResult.getFileType());
                    monitor.end("论文 PDF 类型验证失败: " + validationResult.getMessage());

                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("code", HttpStatus.BAD_REQUEST.value());
                    errorResponse.put("message", validationResult.getMessage());
                    errorResponse.put("fileType", validationResult.getFileType());
                    errorResponse.put("supportedTypes", fileTypeValidationService.getSupportedFileTypes());
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
                }
            }

            String fileType = getFileType(paperTitle);
            String contentType = file.getContentType();

            LogUtils.logBusiness("UPLOAD_CHUNK", userId, "接收到论文分片上传请求: paperId=%s, chunkIndex=%d, paperTitle=%s, fileType=%s, contentType=%s, fileSize=%d, totalSize=%d, orgTag=%s, isPublic=%s",
                    paperId, chunkIndex, paperTitle, fileType, contentType, file.getSize(), totalSize, orgTag, isPublic);

            // 如果未指定组织标签，则获取用户的主组织标签
            if (orgTag == null || orgTag.isEmpty()) {
                try {
                    LogUtils.logBusiness("UPLOAD_CHUNK", userId, "组织标签未指定，尝试获取用户主组织标签: paperTitle=%s", paperTitle);
                    String primaryOrg = userService.getUserPrimaryOrg(userId);
                    orgTag = primaryOrg;
                    LogUtils.logBusiness("UPLOAD_CHUNK", userId, "成功获取用户主组织标签: paperTitle=%s, orgTag=%s", paperTitle, orgTag);
                } catch (Exception e) {
                    LogUtils.logBusinessError("UPLOAD_CHUNK", userId, "获取用户主组织标签失败: paperTitle=%s", e, paperTitle);
                    monitor.end("获取主组织标签失败: " + e.getMessage());
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                    errorResponse.put("message", "获取用户主组织标签失败: " + e.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                }
            }

            if (!userService.isAdminUser(userId)) {
                OrganizationTag uploadOrg = userService.getOrganizationTag(orgTag);
                Long uploadMaxSizeBytes = uploadOrg.getUploadMaxSizeBytes();
                long estimatedUploadedBytes = (long) chunkIndex * DEFAULT_CHUNK_SIZE_BYTES + file.getSize();
                boolean exceedsLimit = uploadMaxSizeBytes != null
                        && uploadMaxSizeBytes > 0
                        && (totalSize > uploadMaxSizeBytes || estimatedUploadedBytes > uploadMaxSizeBytes);
                if (exceedsLimit) {
                    LogUtils.logUserOperation(userId, "UPLOAD_CHUNK", paperTitle, "FAILED_SIZE_LIMIT_EXCEEDED");
                    monitor.end("分片上传失败: 论文 PDF 超过组织上传大小限制");

                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("code", HttpStatus.PAYLOAD_TOO_LARGE.value());
                    errorResponse.put("message", "当前组织限制非管理员上传论文 PDF 不超过 " + formatSize(uploadMaxSizeBytes)
                            + "，当前 PDF 大小为 " + formatSize(totalSize));
                    errorResponse.put("limitBytes", uploadMaxSizeBytes);
                    errorResponse.put("fileSizeBytes", totalSize);
                    errorResponse.put("orgTag", orgTag);
                    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(errorResponse);
                }
            }

            LogUtils.logFileOperation(userId, "UPLOAD_CHUNK", paperTitle, paperId, "PROCESSING");

            uploadService.uploadChunk(paperId, chunkIndex, totalSize, paperTitle, file, orgTag, isPublic, userId);

            List<Integer> uploadedChunks = uploadService.getUploadedChunks(paperId, userId);
            int actualTotalChunks = uploadService.getTotalChunks(paperId, userId);
            double progress = calculateProgress(uploadedChunks, actualTotalChunks);

            LogUtils.logBusiness("UPLOAD_CHUNK", userId, "论文分片上传成功: paperId=%s, paperTitle=%s, fileType=%s, chunkIndex=%d, 进度=%.2f%%",
                    paperId, paperTitle, fileType, chunkIndex, progress);
            monitor.end("分片上传成功");

            // 构建数据对象
            Map<String, Object> data = new HashMap<>();
            data.put("uploaded", uploadedChunks);
            data.put("progress", progress);
            data.put("paperId", paperId);
            data.put("paperTitle", paperTitle);
            data.put("originalFilename", paperTitle);

            // 构建统一响应格式
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "分片上传成功");
            response.put("data", data);

            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            LogUtils.logBusinessError("UPLOAD_CHUNK", userId, "论文分片上传失败: paperId=%s, paperTitle=%s, chunkIndex=%d", e, paperId, paperTitle, chunkIndex);
            monitor.end("分片上传失败: " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", e.getStatus().value());
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(errorResponse);
        } catch (Exception e) {
            String fileType = getFileType(paperTitle);
            LogUtils.logBusinessError("UPLOAD_CHUNK", userId, "论文分片上传失败: paperId=%s, paperTitle=%s, fileType=%s, chunkIndex=%d", e, paperId, paperTitle, fileType, chunkIndex);
            monitor.end("分片上传失败: " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            errorResponse.put("message", "分片上传失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * 获取论文 PDF 上传状态接口。
     *
     * @param paperId 论文标识，当前使用 PDF 内容哈希
     * @return 返回包含已上传分片和上传进度的响应
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getUploadStatus(@RequestParam("paperId") String paperId, @RequestAttribute("userId") String userId) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_UPLOAD_STATUS");
        try {
            // 获取论文信息
            String fileName = "unknown";
            String fileType = "unknown";
            try {
                Optional<Paper> paper = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId);
                if (paper.isPresent()) {
                    fileName = paper.get().getOriginalFilename();
                    fileType = getFileType(fileName);
                }
            } catch (Exception e) {
                // 获取文件信息失败不影响状态查询，继续处理
                LogUtils.logBusiness("GET_UPLOAD_STATUS", "system", "获取论文信息失败，使用默认值: paperId=%s, 错误=%s", paperId, e.getMessage());
            }

            LogUtils.logBusiness("GET_UPLOAD_STATUS", "system", "获取论文上传状态: paperId=%s, paperTitle=%s, fileType=%s", paperId, fileName, fileType);

            List<Integer> uploadedChunks = uploadService.getUploadedChunks(paperId, userId);
            int totalChunks = uploadService.getTotalChunks(paperId, userId);
            double progress = calculateProgress(uploadedChunks, totalChunks);

            LogUtils.logBusiness("GET_UPLOAD_STATUS", "system", "论文上传状态: paperId=%s, paperTitle=%s, fileType=%s, 已上传=%d/%d, 进度=%.2f%%",
                    paperId, fileName, fileType, uploadedChunks.size(), totalChunks, progress);
            monitor.end("获取上传状态成功");

            // 构建数据对象
            Map<String, Object> data = new HashMap<>();
            data.put("uploaded", uploadedChunks);
            data.put("progress", progress);
            data.put("paperId", paperId);
            data.put("paperTitle", fileName);
            data.put("originalFilename", fileName);
            data.put("fileType", fileType);

            // 构建统一响应格式
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取上传状态成功");
            response.put("data", data);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_UPLOAD_STATUS", "system", "获取论文上传状态失败: paperId=%s", e, paperId);
            monitor.end("获取上传状态失败: " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            errorResponse.put("message", "获取上传状态失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * 合并论文 PDF 分片接口。
     *
     * @param request 包含 paperId 和 paperTitle 的请求体
     * @param userId 当前用户ID
     * @return 返回包含合并后 PDF 访问 URL 的响应
     */
    @PostMapping("/merge")
    public ResponseEntity<Map<String, Object>> mergeFile(
            @RequestBody MergeRequest request,
            @RequestAttribute("userId") String userId) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("MERGE_FILE");
        try {
            String fileType = getFileType(request.paperTitle());
            LogUtils.logBusiness("MERGE_FILE", userId, "接收到合并论文请求: paperId=%s, paperTitle=%s, fileType=%s",
                    request.paperId(), request.paperTitle(), fileType);

            // 检查论文完整性和权限
            LogUtils.logBusiness("MERGE_FILE", userId, "检查论文记录和权限: paperId=%s, paperTitle=%s", request.paperId(), request.paperTitle());
            Paper paper = paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(request.paperId(), userId)
                    .orElseThrow(() -> {
                        LogUtils.logUserOperation(userId, "MERGE_FILE", request.paperId(), "FAILED_FILE_NOT_FOUND");
                        return new RuntimeException("论文记录不存在");
                    });

            // 确保用户有权限操作该论文
            if (!paper.getUserId().equals(userId)) {
                LogUtils.logUserOperation(userId, "MERGE_FILE", request.paperId(), "FAILED_PERMISSION_DENIED");
                LogUtils.logBusiness("MERGE_FILE", userId, "权限验证失败: 尝试合并不属于自己的论文, paperId=%s, paperTitle=%s, 实际所有者=%s",
                        request.paperId(), request.paperTitle(), paper.getUserId());
                monitor.end("合并失败：权限不足");
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("code", HttpStatus.FORBIDDEN.value());
                errorResponse.put("message", "没有权限操作此论文");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
            }

            if (paper.getStatus() == Paper.STATUS_COMPLETED) {
                LogUtils.logBusiness("MERGE_FILE", userId, "论文已完成合并，按幂等成功返回: paperId=%s, paperTitle=%s", request.paperId(), request.paperTitle());
                monitor.end("论文 PDF 已完成合并");
                return buildAlreadyMergedResponse(request.paperId());
            }

            if (paper.getStatus() == Paper.STATUS_MERGING) {
                throw new CustomException("论文 PDF 正在合并中，请稍后重试", HttpStatus.CONFLICT);
            }

            LogUtils.logBusiness("MERGE_FILE", userId, "权限验证通过，开始合并论文: paperId=%s, paperTitle=%s, fileType=%s", request.paperId(), request.paperTitle(), fileType);

            // 检查分片是否全部上传完成
            List<Integer> uploadedChunks = uploadService.getUploadedChunks(request.paperId(), userId);
            int totalChunks = uploadService.getTotalChunks(request.paperId(), userId);
            LogUtils.logBusiness("MERGE_FILE", userId, "论文分片上传状态: paperId=%s, paperTitle=%s, 已上传=%d/%d",
                    request.paperId(), request.paperTitle(), uploadedChunks.size(), totalChunks);

            if (uploadedChunks.size() < totalChunks) {
                LogUtils.logUserOperation(userId, "MERGE_FILE", request.paperId(), "FAILED_INCOMPLETE_CHUNKS");
                monitor.end("合并失败：分片未全部上传");
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("code", HttpStatus.BAD_REQUEST.value());
                errorResponse.put("message", "论文 PDF 分片未全部上传，无法合并");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
            }

            int updatedRows = paperRepository.updateStatusIfCurrent(
                    paper.getId(),
                    Paper.STATUS_UPLOADING,
                    Paper.STATUS_MERGING
            );
            if (updatedRows == 0) {
                Paper latestPaper = paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(request.paperId(), userId)
                        .orElseThrow(() -> new RuntimeException("论文记录不存在"));
                if (latestPaper.getStatus() == Paper.STATUS_COMPLETED) {
                    LogUtils.logBusiness("MERGE_FILE", userId, "论文已被其他请求合并完成，按幂等成功返回: paperId=%s, paperTitle=%s", request.paperId(), request.paperTitle());
                    monitor.end("论文 PDF 已完成合并");
                    return buildAlreadyMergedResponse(request.paperId());
                }
                if (latestPaper.getStatus() == Paper.STATUS_MERGING) {
                    throw new CustomException("论文 PDF 正在合并中，请稍后重试", HttpStatus.CONFLICT);
                }
                throw new CustomException("论文 PDF 状态已变化，请刷新后重试", HttpStatus.CONFLICT);
            }

            paper = paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(request.paperId(), userId)
                    .orElseThrow(() -> new RuntimeException("论文记录不存在"));

            // 合并论文 PDF
            LogUtils.logBusiness("MERGE_FILE", userId, "开始合并论文分片: paperId=%s, paperTitle=%s, fileType=%s, 分片数量=%d", request.paperId(), request.paperTitle(), fileType, totalChunks);
            String objectUrl;
            try {
                objectUrl = uploadService.mergeChunks(request.paperId(), request.paperTitle(), userId);
            } catch (Exception mergeException) {
                paperRepository.updateStatusIfCurrent(paper.getId(), Paper.STATUS_MERGING, Paper.STATUS_UPLOADING);
                throw mergeException;
            }
            LogUtils.logFileOperation(userId, "MERGE", request.paperTitle(), request.paperId(), "SUCCESS");

            paper = paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(request.paperId(), userId)
                    .orElseThrow(() -> new RuntimeException("论文记录不存在"));

            // 发送任务到 Kafka，包含完整的权限信息
            LogUtils.logBusiness("MERGE_FILE", userId, "创建论文处理任务: paperId=%s, paperTitle=%s, fileType=%s, orgTag=%s, isPublic=%s",
                    request.paperId(), request.paperTitle(), fileType, paper.getOrgTag(), paper.isPublic());

            PaperProcessingTask task = new PaperProcessingTask(
                    request.paperId(),
                    objectUrl,
                    request.paperTitle(),
                    paper.getUserId(),
                    paper.getOrgTag(),
                    paper.isPublic(),
                    PaperProcessingTask.TASK_TYPE_UPLOAD_PROCESS,
                    userId
            );

            paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_PROCESSING);
            paper.setVectorizationErrorMessage(null);
            paper.setRetrievalIndexedTokenCount(null);
            paper.setRetrievalIndexedLocationCount(null);
            paperRepository.save(paper);

            LogUtils.logBusiness("MERGE_FILE", userId, "发送论文处理任务到 Kafka(事务): topic=%s, paperId=%s, paperTitle=%s",
                    kafkaConfig.getPaperProcessingTopic(), request.paperId(), request.paperTitle());
            kafkaTemplate.executeInTransaction(kt -> {
                kt.send(kafkaConfig.getPaperProcessingTopic(), request.paperId(), task);
                return true;
            });
            LogUtils.logBusiness("MERGE_FILE", userId, "论文处理任务已发送: paperId=%s, paperTitle=%s, fileType=%s", request.paperId(), request.paperTitle(), fileType);

            // 构建数据对象
            Map<String, Object> data = new HashMap<>();
            data.put("paperId", request.paperId());
            data.put("paperTitle", paper.getPaperTitle());
            data.put("originalFilename", paper.getOriginalFilename());
            data.put("objectUrl", objectUrl);
            // 构建统一响应格式
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "论文 PDF 合并成功，处理任务已发送到 Kafka");
            response.put("data", data);

            LogUtils.logUserOperation(userId, "MERGE_FILE", request.paperId(), "SUCCESS");
            monitor.end("论文 PDF 合并成功");
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            String fileType = getFileType(request.paperTitle());
            LogUtils.logBusinessError("MERGE_FILE", userId, "论文合并失败: paperId=%s, paperTitle=%s, fileType=%s", e,
                    request.paperId(), request.paperTitle(), fileType);
            monitor.end("论文 PDF 合并失败: " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", e.getStatus().value());
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(errorResponse);
        } catch (Exception e) {
            String fileType = getFileType(request.paperTitle());
            LogUtils.logBusinessError("MERGE_FILE", userId, "论文合并失败: paperId=%s, paperTitle=%s, fileType=%s", e,
                    request.paperId(), request.paperTitle(), fileType);
            monitor.end("论文 PDF 合并失败: " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            errorResponse.put("message", "论文 PDF 合并失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    private ResponseEntity<Map<String, Object>> buildAlreadyMergedResponse(String paperId) throws Exception {
        Optional<Paper> paper = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId);
        Map<String, Object> data = new HashMap<>();
        data.put("paperId", paperId);
        paper.ifPresent(value -> {
            data.put("paperTitle", value.getPaperTitle());
            data.put("originalFilename", value.getOriginalFilename());
        });
        data.put("objectUrl", uploadService.generateMergedObjectUrl(paperId));

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "论文 PDF 已完成合并");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * 计算上传进度
     *
     * @param uploadedChunks 已上传的分片列表
     * @param totalChunks 总分片数量
     * @return 返回上传进度的百分比
     */
    private double calculateProgress(List<Integer> uploadedChunks, int totalChunks) {
        if (totalChunks == 0) {
            LogUtils.logBusiness("CALCULATE_PROGRESS", "system", "计算上传进度时总分片数为0");
            return 0.0;
        }
        return (double) uploadedChunks.size() / totalChunks * 100;
    }

    private String formatSize(long sizeInBytes) {
        double sizeInMb = sizeInBytes / (1024d * 1024d);
        if (sizeInMb >= 1024d) {
            return String.format("%.2f GB", sizeInMb / 1024d);
        }
        if (sizeInMb >= 1d) {
            return String.format("%.2f MB", sizeInMb);
        }
        return String.format("%.2f KB", sizeInBytes / 1024d);
    }

    /**
     * 合并请求，paperId 当前使用 PDF 内容哈希。
     */
    public record MergeRequest(String paperId, String paperTitle) {}

    /**
     * 获取支持的论文格式列表接口。
     *
     * @return 返回支持的论文格式信息
     */
    @GetMapping("/supported-types")
    public ResponseEntity<Map<String, Object>> getSupportedFileTypes() {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_SUPPORTED_TYPES");
        try {
            LogUtils.logBusiness("GET_SUPPORTED_TYPES", "system", "获取支持的论文格式列表");

            Set<String> supportedTypes = fileTypeValidationService.getSupportedFileTypes();
            Set<String> supportedExtensions = fileTypeValidationService.getSupportedExtensions();

            // 构建数据对象
            Map<String, Object> data = new HashMap<>();
            data.put("supportedTypes", supportedTypes);
            data.put("supportedExtensions", supportedExtensions);
            data.put("description", "PaperLoom 当前只支持 PDF 论文上传、解析和向量化");

            // 构建统一响应格式
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取支持的论文格式成功");
            response.put("data", data);

            LogUtils.logBusiness("GET_SUPPORTED_TYPES", "system", "成功返回支持的论文格式: 类型数量=%d, 扩展名数量=%d",
                    supportedTypes.size(), supportedExtensions.size());
            monitor.end("获取支持的论文格式成功");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_SUPPORTED_TYPES", "system", "获取支持的论文格式失败", e);
            monitor.end("获取支持的论文格式失败: " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            errorResponse.put("message", "获取支持的论文格式失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * 根据 PDF 文件名获取论文格式。
     *
     * @param fileName 文件名
     * @return 论文格式
     */
    private String getFileType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unknown";
        }

        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "unknown";
        }

        String extension = fileName.substring(lastDotIndex + 1).toLowerCase();

        return "pdf".equals(extension) ? "PDF论文" : "unsupported";
    }
}
