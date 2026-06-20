package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.model.PaperProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 论文管理服务。
 * 负责论文删除、访问列表、预览和索引重建。
 */
@Service
public class PaperService {

    public record PdfSinglePagePreview(byte[] content, boolean cacheHit) {}
    private record InMemoryPdfPreviewCache(byte[] content, long expiresAtMillis) {}

    private static final Logger logger = LoggerFactory.getLogger(PaperService.class);
    private static final String PDF_SINGLE_PAGE_CACHE_PREFIX = "preview:pdf:single-page:";
    private static final long PDF_SINGLE_PAGE_CACHE_TTL_MINUTES = 30;
    private static final long PDF_SINGLE_PAGE_CACHE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(PDF_SINGLE_PAGE_CACHE_TTL_MINUTES);
    private static final String LEGACY_COMPLETED_WITHOUT_USAGE_MESSAGE = "历史数据未统计实际 Tokens，可按需重试以回写实际向量化结果";
    private static final String LEGACY_FAILED_MESSAGE = "历史向量化结果缺失，可点击重试向量化重新处理";
    private static final Map<String, InMemoryPdfPreviewCache> PDF_SINGLE_PAGE_LOCAL_CACHE = new ConcurrentHashMap<>();

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private PaperTextChunkRepository paperTextChunkRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ParseService parseService;

    @Autowired
    private VectorizationService vectorizationService;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaConfig kafkaConfig;

    /**
     * 删除论文及其相关数据。
     * 该方法将删除:
     * 1. 上传记录
     * 2. 解析 chunk 记录
     * 3. MinIO 中的 PDF 对象
     * 4. Elasticsearch 中的 paper chunk 数据
     *
     * @param paperId 论文标识，当前使用 PDF 内容哈希
     */
    @Transactional
    public void deletePaper(String paperId, String userId) {
        logger.info("开始删除论文: paperId={}", paperId);
        
        try {
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(paperId, userId)
                    .orElseThrow(() -> new RuntimeException("论文不存在"));
            
            // 1. 删除 Elasticsearch 中的 paper chunk
            try {
                elasticsearchService.deleteByPaperId(paperId);
                logger.info("成功从 Elasticsearch 删除论文 chunk: paperId={}", paperId);
            } catch (Exception e) {
                logger.error("从 Elasticsearch 删除论文 chunk 时出错: paperId={}", paperId, e);
                // 继续删除其他数据
            }
            
            // 2. 删除 MinIO 中的 PDF 对象
            try {
                String objectName = "merged/" + fileUpload.getFileMd5();
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket("uploads")
                                .object(objectName)
                                .build()
                );
                logger.info("成功从 MinIO 删除论文 PDF: {}", objectName);
            } catch (Exception e) {
                logger.error("从 MinIO 删除论文 PDF 时出错: paperId={}", paperId, e);
                // 继续删除其他数据
            }

            invalidatePdfSinglePagePreviewCache(paperId);
            
            // 3. 删除解析 chunk 记录
            try {
                paperTextChunkRepository.deleteByPaperId(paperId);
                logger.info("成功删除论文 chunk 记录: paperId={}", paperId);
            } catch (Exception e) {
                logger.error("删除论文 chunk 记录时出错: paperId={}", paperId, e);
                // 继续删除其他数据
            }
            
            // 4. 删除上传记录
            fileUploadRepository.deleteByFileMd5(paperId);
            logger.info("成功删除论文上传记录: paperId={}", paperId);
            
            logger.info("论文删除完成: paperId={}", paperId);
        } catch (Exception e) {
            logger.error("删除论文过程中发生错误: paperId={}", paperId, e);
            throw new RuntimeException("删除论文失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public VectorizationService.VectorizationUsageResult reindexPaper(String paperId, String requesterId) {
        logger.info("开始重建论文索引: paperId={}, requesterId={}", paperId, requesterId);

        FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(paperId)
                .orElseThrow(() -> new RuntimeException("论文不存在"));

        markVectorizationProcessing(fileUpload, true);

        try (InputStream fileStream = uploadService.getMergedFileStream(paperId)) {
            try {
                elasticsearchService.deleteByPaperId(paperId);
                logger.info("重建前已清理 Elasticsearch 论文 chunk: paperId={}", paperId);
            } catch (Exception e) {
                logger.warn("重建前清理 Elasticsearch 失败: paperId={}, error={}", paperId, e.getMessage());
            }

            paperTextChunkRepository.deleteByPaperId(paperId);
            invalidatePdfSinglePagePreviewCache(paperId);

            parseService.parseAndSave(
                    paperId,
                    fileStream,
                    fileUpload.getUserId(),
                    fileUpload.getOrgTag(),
                    fileUpload.isPublic()
            );

            VectorizationService.VectorizationUsageResult result = vectorizationService.vectorizeWithUsage(
                    paperId,
                    fileUpload.getUserId(),
                    fileUpload.getOrgTag(),
                    fileUpload.isPublic(),
                    requesterId
            );
            markVectorizationCompleted(fileUpload, result);

            logger.info(
                    "论文索引重建完成: paperId={}, actualTokens={}, actualChunkCount={}",
                    paperId,
                    result.actualEmbeddingTokens(),
                    result.actualChunkCount()
            );
            return result;
        } catch (TikaException e) {
            markVectorizationFailed(fileUpload, e);
            logger.error("重建论文索引失败，PDF 解析异常: paperId={}", paperId, e);
            throw new RuntimeException("重建论文索引失败: " + e.getMessage(), e);
        } catch (Exception e) {
            markVectorizationFailed(fileUpload, e);
            logger.error("重建论文索引失败: paperId={}", paperId, e);
            throw new RuntimeException("重建论文索引失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public FileUpload enqueueAsyncVectorizationRetry(String paperId, String requesterId) {
        FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(paperId)
                .orElseThrow(() -> new RuntimeException("论文不存在"));

        markVectorizationProcessing(fileUpload, true);

        PaperProcessingTask task = new PaperProcessingTask(
                fileUpload.getFileMd5(),
                null,
                fileUpload.getFileName(),
                fileUpload.getUserId(),
                fileUpload.getOrgTag(),
                fileUpload.isPublic(),
                PaperProcessingTask.TASK_TYPE_REINDEX,
                requesterId
        );

        kafkaTemplate.executeInTransaction(kt -> {
            kt.send(kafkaConfig.getPaperProcessingTopic(), task);
            return true;
        });

        logger.info("已发送异步论文向量化重试任务: paperId={}, requesterId={}", paperId, requesterId);
        return fileUpload;
    }

    @Transactional
    public void markVectorizationProcessing(String paperId, boolean resetActualUsage) {
        FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(paperId)
                .orElseThrow(() -> new RuntimeException("论文不存在"));
        markVectorizationProcessing(fileUpload, resetActualUsage);
    }

    @Transactional
    public void markVectorizationCompleted(String paperId, VectorizationService.VectorizationUsageResult result) {
        FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(paperId)
                .orElseThrow(() -> new RuntimeException("论文不存在"));
        markVectorizationCompleted(fileUpload, result);
    }

    @Transactional
    public void markVectorizationFailed(String paperId, String errorMessage) {
        FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(paperId)
                .orElseThrow(() -> new RuntimeException("论文不存在"));
        markVectorizationFailed(fileUpload, errorMessage);
    }

    @Transactional
    public void markVectorizationFailed(String paperId, Throwable error) {
        FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(paperId)
                .orElseThrow(() -> new RuntimeException("论文不存在"));
        markVectorizationFailed(fileUpload, error);
    }

    private void markVectorizationProcessing(FileUpload fileUpload, boolean resetActualUsage) {
        fileUpload.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_PROCESSING);
        fileUpload.setVectorizationErrorMessage(null);
        if (resetActualUsage) {
            fileUpload.setActualEmbeddingTokens(null);
            fileUpload.setActualChunkCount(null);
        }
        fileUploadRepository.save(fileUpload);
    }

    private void markVectorizationCompleted(FileUpload fileUpload, VectorizationService.VectorizationUsageResult result) {
        fileUpload.setActualEmbeddingTokens((long) result.actualEmbeddingTokens());
        fileUpload.setActualChunkCount(result.actualChunkCount());
        fileUpload.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_COMPLETED);
        fileUpload.setVectorizationErrorMessage(null);
        fileUploadRepository.save(fileUpload);
    }

    private void markVectorizationFailed(FileUpload fileUpload, String errorMessage) {
        fileUpload.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_FAILED);
        fileUpload.setVectorizationErrorMessage(trimVectorizationErrorMessage(errorMessage));
        fileUploadRepository.save(fileUpload);
    }

    private void markVectorizationFailed(FileUpload fileUpload, Throwable error) {
        markVectorizationFailed(fileUpload, resolveVectorizationErrorMessage(error));
    }

    private String resolveVectorizationErrorMessage(Throwable error) {
        Throwable current = error;
        String deepestMessage = null;

        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                deepestMessage = message;
                if (message.contains("余额不足")) {
                    return message;
                }
            }
            current = current.getCause();
        }

        if (deepestMessage == null || deepestMessage.isBlank()) {
            return "向量化失败，请稍后重试";
        }

        if ("向量化失败".equals(deepestMessage) || "Error processing task".equals(deepestMessage)) {
            return "向量化失败，请稍后重试";
        }

        return deepestMessage;
    }

    private String trimVectorizationErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "向量化失败，请稍后重试";
        }
        return errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage;
    }
    
    /**
     * 获取用户可访问的所有论文列表。
     * 包括用户自己的论文、公开论文和用户所属组织的论文（支持层级权限）。
     *
     * @param userId 用户ID
     * @param orgTags 用户所属的组织标签（逗号分隔的字符串，仅供兼容性使用）
     * @return 用户可访问的论文列表
     */
    public List<FileUpload> getAccessiblePapers(String userId, String orgTags) {
        logger.info("获取用户可访问论文列表: userId={}", userId);
        
        try {
            backfillLegacyVectorizationStatuses();

            User user = resolveUser(userId);
            String userDbId = String.valueOf(user.getId());
            
            List<String> userEffectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户有效组织标签: {}", userEffectiveTags);
            
            // 使用有效标签查询论文
            List<FileUpload> files;
            if (userEffectiveTags.isEmpty()) {
                // 如果用户没有任何组织标签，只返回自己的论文和公开论文
                files = fileUploadRepository.findByUserIdOrIsPublicTrue(userDbId);
                logger.debug("用户无组织标签，仅返回个人和公开论文");
            } else {
                // 查询用户可访问的所有论文（考虑层级标签）
                files = fileUploadRepository.findAccessibleFilesWithTags(userDbId, userEffectiveTags);
                logger.debug("使用有效组织标签查询论文");
            }

            logger.info("成功获取用户可访问论文列表: userId={}, paperCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户可访问论文列表失败: userId={}", userId, e);
            throw new RuntimeException("获取可访问论文列表失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取用户上传的所有论文列表。
     *
     * @param userId 用户ID
     * @return 用户上传的论文列表
     */
    public List<FileUpload> getUserUploadedPapers(String userId) {
        logger.info("获取用户上传的论文列表: userId={}", userId);
        
        try {
            backfillLegacyVectorizationStatuses();
            List<FileUpload> files = fileUploadRepository.findByUserId(userId);
            logger.info("成功获取用户上传的论文列表: userId={}, paperCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户上传的论文列表失败: userId={}", userId, e);
            throw new RuntimeException("获取用户上传的论文列表失败: " + e.getMessage(), e);
        }
    }

    private User resolveUser(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);
            return userRepository.findById(userIdLong)
                    .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
        } catch (NumberFormatException ignored) {
            return userRepository.findByUsername(userId)
                    .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
        }
    }

    private void backfillLegacyVectorizationStatuses() {
        backfillLegacyVectorizationStatuses(fileUploadRepository.findAllByVectorizationStatusIsNull());
    }

    private void backfillLegacyVectorizationStatuses(List<FileUpload> files) {
        if (files == null || files.isEmpty()) {
            return;
        }

        Map<String, Long> vectorCountCache = new HashMap<>();
        for (FileUpload file : files) {
            if (file == null || file.getVectorizationStatus() != null) {
                continue;
            }

            boolean changed = false;
            if (file.getStatus() == FileUpload.STATUS_UPLOADING) {
                file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_PENDING);
                file.setVectorizationErrorMessage(null);
                changed = true;
            } else if (file.getStatus() == FileUpload.STATUS_MERGING) {
                file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_PROCESSING);
                file.setVectorizationErrorMessage(null);
                changed = true;
            } else if (file.getActualEmbeddingTokens() != null || file.getActualChunkCount() != null) {
                file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_COMPLETED);
                file.setVectorizationErrorMessage(null);
                changed = true;
            } else {
                long vectorCount = vectorCountCache.computeIfAbsent(
                        file.getFileMd5(),
                        paperTextChunkRepository::countByPaperId
                );
                if (vectorCount > 0) {
                    file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_COMPLETED);
                    file.setVectorizationErrorMessage(LEGACY_COMPLETED_WITHOUT_USAGE_MESSAGE);
                    changed = true;
                } else if (file.getEstimatedEmbeddingTokens() != null || file.getEstimatedChunkCount() != null) {
                    file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_FAILED);
                    file.setVectorizationErrorMessage(LEGACY_FAILED_MESSAGE);
                    changed = true;
                }
            }

            if (changed) {
                fileUploadRepository.save(file);
            }
        }
    }
    
    /**
     * 生成论文 PDF 下载链接。
     *
     * @param paperId 论文标识，当前使用 PDF 内容哈希
     * @return 预签名下载URL
     */
    public String generateDownloadUrl(String paperId) {
        logger.info("生成论文 PDF 下载链接: paperId={}", paperId);

        try {
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(paperId)
                    .orElseThrow(() -> new RuntimeException("论文不存在: " + paperId));

            String objectName = "merged/" + paperId;
            String presignedUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket("uploads")
                            .object(objectName)
                            .expiry(3600)
                            .build()
            );
            logger.info("成功生成论文 PDF 下载链接: paperId={}, paperTitle={}, objectName={}",
                    paperId, fileUpload.getFileName(), objectName);

            return uploadService.transToPublicUrl(presignedUrl);
        } catch (Exception e) {
            logger.error("生成论文 PDF 下载链接失败: paperId={}", paperId, e);
            return null;
        }
    }
    
    /**
     * 获取非 PDF 论文预览兜底内容。
     *
     * @param paperId 论文标识
     * @param paperTitle 论文标题
     * @return 预览提示
     */
    public String getPaperPreviewContent(String paperId, String paperTitle) {
        logger.info("获取论文预览兜底内容: paperId={}, paperTitle={}", paperId, paperTitle);

        try {
            fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(paperId)
                    .orElseThrow(() -> new RuntimeException("论文不存在: " + paperId));
            return "PaperLoom 当前仅支持 PDF 论文预览。";

        } catch (Exception e) {
            logger.error("获取论文预览兜底内容失败: paperId={}, paperTitle={}", paperId, paperTitle, e);
            return "预览失败: " + e.getMessage();
        }
    }

    public PdfSinglePagePreview getPdfSinglePagePreview(String paperId, int pageNumber) {
        logger.info("生成论文 PDF 单页预览: paperId={}, pageNumber={}", paperId, pageNumber);

        try {
            String cacheKey = buildPdfSinglePageCacheKey(paperId, pageNumber);
            byte[] localPreview = getLocalPdfSinglePagePreview(cacheKey);
            if (localPreview != null) {
                logger.info("命中论文 PDF 单页预览本地缓存: paperId={}, pageNumber={}, previewSize={}",
                        paperId, pageNumber, localPreview.length);
                return new PdfSinglePagePreview(localPreview, true);
            }

            byte[] cachedPreview = getCachedPdfSinglePagePreview(cacheKey);
            if (cachedPreview != null) {
                cacheLocalPdfSinglePagePreview(cacheKey, cachedPreview);
                logger.info("命中论文 PDF 单页预览缓存: paperId={}, pageNumber={}, previewSize={}",
                        paperId, pageNumber, cachedPreview.length);
                return new PdfSinglePagePreview(cachedPreview, true);
            }

            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(paperId)
                    .orElseThrow(() -> new RuntimeException("论文不存在: " + paperId));

            try (InputStream inputStream = openFileStream(fileUpload);
                 PDDocument sourceDocument = PDDocument.load(inputStream);
                 PDDocument singlePageDocument = new PDDocument();
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

                int totalPages = sourceDocument.getNumberOfPages();
                if (pageNumber < 1 || pageNumber > totalPages) {
                    throw new IllegalArgumentException("页码超出范围: " + pageNumber + "/" + totalPages);
                }

                singlePageDocument.importPage(sourceDocument.getPage(pageNumber - 1));
                singlePageDocument.save(outputStream);

                byte[] previewBytes = outputStream.toByteArray();
                cacheLocalPdfSinglePagePreview(cacheKey, previewBytes);
                cachePdfSinglePagePreview(cacheKey, previewBytes);
                logger.info("成功生成论文 PDF 单页预览: paperId={}, pageNumber={}, previewSize={}",
                        paperId, pageNumber, outputStream.size());
                return new PdfSinglePagePreview(previewBytes, false);
            }
        } catch (Exception e) {
            logger.error("生成论文 PDF 单页预览失败: paperId={}, pageNumber={}", paperId, pageNumber, e);
            throw new RuntimeException("生成论文 PDF 单页预览失败: " + e.getMessage(), e);
        }
    }
    
    private InputStream openFileStream(FileUpload fileUpload) throws Exception {
        String objectName = "merged/" + fileUpload.getFileMd5();
        InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket("uploads")
                        .object(objectName)
                        .build());
        logger.info("使用 paperId 路径获取论文 PDF 流: paperId={}, objectName={}", fileUpload.getFileMd5(), objectName);
        return inputStream;
    }

    private String buildPdfSinglePageCacheKey(String paperId, int pageNumber) {
        return PDF_SINGLE_PAGE_CACHE_PREFIX + paperId + ":" + pageNumber;
    }

    private byte[] getLocalPdfSinglePagePreview(String cacheKey) {
        InMemoryPdfPreviewCache cached = PDF_SINGLE_PAGE_LOCAL_CACHE.get(cacheKey);
        if (cached == null) {
            return null;
        }

        if (cached.expiresAtMillis() <= System.currentTimeMillis()) {
            PDF_SINGLE_PAGE_LOCAL_CACHE.remove(cacheKey);
            return null;
        }

        return cached.content();
    }

    private void cacheLocalPdfSinglePagePreview(String cacheKey, byte[] previewBytes) {
        PDF_SINGLE_PAGE_LOCAL_CACHE.put(
                cacheKey,
                new InMemoryPdfPreviewCache(previewBytes, System.currentTimeMillis() + PDF_SINGLE_PAGE_CACHE_TTL_MILLIS)
        );
    }

    private byte[] getCachedPdfSinglePagePreview(String cacheKey) {
        try {
            String normalizedValue = stringRedisTemplate.opsForValue().get(cacheKey);
            if (normalizedValue != null && !normalizedValue.isBlank()) {
                normalizedValue = normalizedValue.trim();
                if (normalizedValue.startsWith("\"") && normalizedValue.endsWith("\"") && normalizedValue.length() >= 2) {
                    normalizedValue = normalizedValue.substring(1, normalizedValue.length() - 1);
                }
                logger.info("命中 PDF 单页预览缓存 key: cacheKey={}, encodedLength={}", cacheKey, normalizedValue.length());
                return Base64.getDecoder().decode(normalizedValue);
            }
            logger.info("未命中 PDF 单页预览缓存 key: cacheKey={}", cacheKey);
        } catch (Exception e) {
            logger.warn("读取 PDF 单页预览缓存失败: cacheKey={}, error={}", cacheKey, e.getMessage());
        }
        return null;
    }

    private void cachePdfSinglePagePreview(String cacheKey, byte[] previewBytes) {
        try {
            String encodedPreview = Base64.getEncoder().encodeToString(previewBytes);
            stringRedisTemplate.opsForValue().set(
                    cacheKey,
                    encodedPreview,
                    PDF_SINGLE_PAGE_CACHE_TTL_MINUTES,
                    TimeUnit.MINUTES
            );
            String storedValue = stringRedisTemplate.opsForValue().get(cacheKey);
            Long ttlSeconds = stringRedisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
            logger.info("写入 PDF 单页预览缓存完成: cacheKey={}, encodedLength={}, storedLength={}, ttlSeconds={}",
                    cacheKey,
                    encodedPreview.length(),
                    storedValue != null ? storedValue.length() : 0,
                    ttlSeconds);
        } catch (Exception e) {
            logger.warn("写入 PDF 单页预览缓存失败: cacheKey={}, error={}", cacheKey, e.getMessage());
        }
    }

    private void invalidatePdfSinglePagePreviewCache(String paperId) {
        try {
            PDF_SINGLE_PAGE_LOCAL_CACHE.keySet().removeIf(key -> key.startsWith(PDF_SINGLE_PAGE_CACHE_PREFIX + paperId + ":"));
            Set<String> cacheKeys = stringRedisTemplate.keys(PDF_SINGLE_PAGE_CACHE_PREFIX + paperId + ":*");
            if (cacheKeys != null && !cacheKeys.isEmpty()) {
                stringRedisTemplate.delete(cacheKeys);
                logger.info("删除论文 PDF 单页预览缓存: paperId={}, cacheCount={}", paperId, cacheKeys.size());
            }
        } catch (Exception e) {
            logger.warn("删除论文 PDF 单页预览缓存失败: paperId={}, error={}", paperId, e.getMessage());
        }
    }
    
} 
