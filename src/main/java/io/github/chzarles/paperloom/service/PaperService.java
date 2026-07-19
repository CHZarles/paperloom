package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.config.KafkaConfig;
import io.github.chzarles.paperloom.model.ChunkInfo;
import io.github.chzarles.paperloom.model.PaperProcessingTask;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.paper.parser.MinerUUnavailableException;
import io.github.chzarles.paperloom.paper.parser.PaperParsingException;
import io.github.chzarles.paperloom.repository.ChunkInfoRepository;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperPageRepository;
import io.github.chzarles.paperloom.repository.PaperPublicationRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperSectionRepository;
import io.github.chzarles.paperloom.repository.PaperSourceQuoteRepository;
import io.github.chzarles.paperloom.repository.PaperTextChunkRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 论文管理服务。
 * 负责论文删除、访问列表、预览和索引重建。
 */
@Service
public class PaperService {

    private static final Logger logger = LoggerFactory.getLogger(PaperService.class);

    @Autowired
    private PaperRepository paperRepository;

    @Autowired
    private PaperPublicationRepository paperPublicationRepository;

    @Autowired
    private PaperAccessService paperAccessService;

    @Autowired
    private PaperSearchabilityService paperSearchabilityService;

    @Autowired
    private PaperTextChunkRepository paperTextChunkRepository;

    @Autowired
    private PaperSourceQuoteRepository paperSourceQuoteRepository;

    @Autowired
    private PaperReadingElementRepository paperReadingElementRepository;

    @Autowired
    private PaperLocationRepository paperLocationRepository;

    @Autowired
    private PaperSectionRepository paperSectionRepository;

    @Autowired
    private PaperPageRepository paperPageRepository;

    @Autowired
    private PaperReadingModelRepository paperReadingModelRepository;

    @Autowired
    private ChunkInfoRepository chunkInfoRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ReadingModelQdrantIndexService qdrantIndexService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private ParseService parseService;

    @Autowired
    private PaperParserArtifactService paperParserArtifactService;

    @Autowired
    private PaperVisualAssetService paperVisualAssetService;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaConfig kafkaConfig;

    @Transactional
    public void deletePaper(String paperId, String userId) {
        deletePaper(paperId, userId, "USER");
    }

    /**
     * 删除论文记录及其最后一份物理资源。
     * 多个用户可能上传同一 PDF，因此 paperId 指向的 ES/MinIO/chunk 资源只有在最后一条记录删除后才清理。
     *
     * @param paperId 论文标识，当前使用 PDF 内容哈希
     * @param requesterId 当前发起删除的用户
     * @param role 当前用户角色
     */
    @Transactional
    public void deletePaper(String paperId, String requesterId, String role) {
        logger.info("开始删除论文: paperId={}, requesterId={}, role={}", paperId, requesterId, role);

        try {
            Paper paper = findPaperForDeletion(paperId, requesterId);
            String ownerId = paper.getUserId();

            if (paperRepository.countByPaperId(paperId) <= 1
                    && paperPublicationRepository.existsByPaperId(paperId)) {
                throw new IllegalStateException("Published paper must be unpublished before deleting its last library entry");
            }

            clearUploadMarker(paperId, ownerId);
            paperRepository.delete(paper);
            paperRepository.flush();
            logger.info("成功删除论文上传记录: paperId={}, ownerId={}", paperId, ownerId);

            long remainingRecords = paperRepository.countByPaperId(paperId);
            if (remainingRecords > 0) {
                logger.info("论文仍被其他记录引用，跳过物理资源清理: paperId={}, remainingRecords={}",
                        paperId, remainingRecords);
                return;
            }

            deletePhysicalPaperArtifacts(paperId);
            logger.info("论文删除完成: paperId={}, requesterId={}, ownerId={}", paperId, requesterId, ownerId);
        } catch (IllegalStateException e) {
            logger.warn("删除论文被拒绝: paperId={}, reason={}", paperId, e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("删除论文过程中发生错误: paperId={}", paperId, e);
            throw new RuntimeException("删除论文失败: " + e.getMessage(), e);
        }
    }

    private Paper findPaperForDeletion(String paperId, String requesterId) {
        return paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(paperId, requesterId)
                .orElseThrow(() -> new RuntimeException("论文不存在"));
    }

    private void clearUploadMarker(String paperId, String ownerId) {
        try {
            uploadService.deleteFileMark(paperId, ownerId);
            logger.info("成功清理论文上传状态标记: paperId={}, ownerId={}", paperId, ownerId);
        } catch (Exception e) {
            logger.warn("清理论文上传状态标记失败，将继续删除论文记录: paperId={}, ownerId={}, error={}",
                    paperId, ownerId, e.getMessage());
        }
    }

    private void deletePhysicalPaperArtifacts(String paperId) {
        deleteReadingModelArtifacts(paperId);
        deleteSearchIndex(paperId);
        deleteMergedPaperObject(paperId);
        deleteUploadChunks(paperId);
        deleteParserArtifacts(paperId);
        deleteVisualAssets(paperId);
        deleteParsedChunks(paperId);
    }

    private void deleteReadingModelArtifacts(String paperId) {
        paperSourceQuoteRepository.deleteByPaperId(paperId);
        paperReadingElementRepository.deleteByPaperId(paperId);
        paperLocationRepository.deleteByPaperId(paperId);
        paperSectionRepository.deleteByPaperId(paperId);
        paperPageRepository.deleteByPaperId(paperId);
        paperReadingModelRepository.deleteByPaperId(paperId);
        logger.info("成功删除论文 Reading Model 数据: paperId={}", paperId);
    }

    private void deleteSearchIndex(String paperId) {
        try {
            qdrantIndexService.deleteByPaperId(paperId);
            logger.info("成功从 Qdrant 删除论文 Reading Model 索引: paperId={}", paperId);
        } catch (Exception e) {
            logger.error("从 Qdrant 删除论文索引时出错: paperId={}", paperId, e);
        }
    }

    private void deleteMergedPaperObject(String paperId) {
        try {
            String objectName = "merged/" + paperId;
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket("uploads")
                            .object(objectName)
                            .build()
            );
            logger.info("成功从 MinIO 删除论文 PDF: {}", objectName);
        } catch (Exception e) {
            logger.error("从 MinIO 删除论文 PDF 时出错: paperId={}", paperId, e);
        }
    }

    private void deleteUploadChunks(String paperId) {
        try {
            List<ChunkInfo> chunks = chunkInfoRepository.findByPaperIdOrderByChunkIndexAsc(paperId);
            for (ChunkInfo chunk : chunks) {
                try {
                    minioClient.removeObject(
                            RemoveObjectArgs.builder()
                                    .bucket("uploads")
                                    .object(chunk.getStoragePath())
                                    .build()
                    );
                    logger.debug("成功从 MinIO 删除上传分片: paperId={}, path={}", paperId, chunk.getStoragePath());
                } catch (Exception e) {
                    logger.warn("从 MinIO 删除上传分片失败，将继续清理其他分片: paperId={}, path={}, error={}",
                            paperId, chunk.getStoragePath(), e.getMessage());
                }
            }
            chunkInfoRepository.deleteByPaperId(paperId);
            logger.info("成功删除论文上传分片记录: paperId={}, chunkCount={}", paperId, chunks.size());
        } catch (Exception e) {
            logger.error("删除论文上传分片记录时出错: paperId={}", paperId, e);
        }
    }

    private void deleteParsedChunks(String paperId) {
        try {
            paperTextChunkRepository.deleteByPaperId(paperId);
            logger.info("成功删除论文解析 chunk 记录: paperId={}", paperId);
        } catch (Exception e) {
            logger.error("删除论文解析 chunk 记录时出错: paperId={}", paperId, e);
        }
    }

    private void deleteParserArtifacts(String paperId) {
        try {
            paperParserArtifactService.deleteParserArtifacts(paperId);
            logger.info("成功删除 parser artifact: paperId={}", paperId);
        } catch (Exception e) {
            logger.error("删除 parser artifact 时出错: paperId={}", paperId, e);
        }
    }

    private void deleteVisualAssets(String paperId) {
        try {
            paperVisualAssetService.deleteVisualAssets(paperId);
            logger.info("成功删除论文视觉资产: paperId={}", paperId);
        } catch (Exception e) {
            logger.error("删除论文视觉资产时出错: paperId={}", paperId, e);
        }
    }

    @Transactional
    public Paper enqueueAsyncVectorizationRetry(String paperId, String requesterId) {
        Paper paper = paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(paperId, requesterId)
                .orElseThrow(() -> new RuntimeException("论文不存在"));

        if (paperSearchabilityService.isSearchable(paper)) {
            throw new IllegalStateException("PAPER_ALREADY_READY");
        }
        if (!Paper.VECTORIZATION_STATUS_FAILED.equalsIgnoreCase(paper.getVectorizationStatus())) {
            throw new IllegalStateException("Paper initial processing has not failed");
        }

        markVectorizationProcessing(paper, true);

        PaperProcessingTask task = new PaperProcessingTask(
                paper.getPaperId(),
                null,
                paper.getOriginalFilename(),
                paper.getUserId(),
                PaperProcessingTask.TASK_TYPE_RETRY_INITIAL,
                requesterId
        );

        kafkaTemplate.executeInTransaction(kt -> {
            kt.send(kafkaConfig.getPaperProcessingTopic(), paper.getPaperId(), task);
            return true;
        });

        logger.info("已发送异步论文处理重试任务: paperId={}, requesterId={}", paperId, requesterId);
        return paper;
    }

    @Transactional
    public void markVectorizationProcessing(String paperId, boolean resetIndexMetrics) {
        Paper paper = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId)
                .orElseThrow(() -> new RuntimeException("论文不存在"));
        markVectorizationProcessing(paper, resetIndexMetrics);
    }

    @Transactional
    public void markVectorizationCompleted(String paperId, RetrievalIndexingService.IndexingResult result) {
        Paper paper = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId)
                .orElseThrow(() -> new RuntimeException("论文不存在"));
        markVectorizationCompleted(paper, result);
    }

    @Transactional
    public void markVectorizationFailed(String paperId, String errorMessage) {
        Paper paper = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId)
                .orElseThrow(() -> new RuntimeException("论文不存在"));
        markVectorizationFailed(paper, errorMessage);
    }

    @Transactional
    public void markVectorizationFailed(String paperId, Throwable error) {
        Paper paper = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId)
                .orElseThrow(() -> new RuntimeException("论文不存在"));
        markVectorizationFailed(paper, error);
    }

    private void markVectorizationProcessing(Paper paper, boolean resetIndexMetrics) {
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_PROCESSING);
        paper.setVectorizationErrorMessage(null);
        if (resetIndexMetrics) {
            paper.setRetrievalIndexedTokenCount(null);
            paper.setRetrievalIndexedLocationCount(null);
        }
        paperRepository.save(paper);
    }

    private void markVectorizationCompleted(Paper paper, RetrievalIndexingService.IndexingResult result) {
        paper.setRetrievalIndexedTokenCount((long) result.indexedTokenCount());
        paper.setRetrievalIndexedLocationCount(result.indexedLocationCount());
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_COMPLETED);
        paper.setVectorizationErrorMessage(null);
        paperRepository.save(paper);
    }

    private void markVectorizationFailed(Paper paper, String errorMessage) {
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_FAILED);
        paper.setVectorizationErrorMessage(trimVectorizationErrorMessage(errorMessage));
        paperRepository.save(paper);
    }

    private void markVectorizationFailed(Paper paper, Throwable error) {
        markVectorizationFailed(paper, resolveVectorizationErrorMessage(error));
    }

    private String resolveVectorizationErrorMessage(Throwable error) {
        Throwable current = error;
        String deepestMessage = null;

        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                if (current instanceof MinerUUnavailableException) {
                    return message;
                }
                if (current instanceof PaperParsingException && message.startsWith("MinerU ")) {
                    return message;
                }
                deepestMessage = message;
                if (message.contains("余额不足")) {
                    return message;
                }
            }
            current = current.getCause();
        }

        if (deepestMessage == null || deepestMessage.isBlank()) {
            return "论文解析或词法索引失败，请稍后重试";
        }

        if ("向量化失败".equals(deepestMessage) || "Error processing task".equals(deepestMessage)) {
            return "论文解析或词法索引失败，请稍后重试";
        }

        return deepestMessage;
    }

    private String trimVectorizationErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "论文解析或词法索引失败，请稍后重试";
        }
        return errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage;
    }

    /**
     * 获取用户可访问的所有论文列表：个人空间论文和管理员发布的全局论文。
     *
     * @param userId 用户ID
     * @param orgTags 兼容参数，不参与论文权限判断
     * @return 用户可访问的论文列表
     */
    public List<Paper> getAccessiblePapers(String userId, String orgTags) {
        logger.info("获取用户可访问论文列表: userId={}", userId);

        try {
            User user = resolveUser(userId);
            String userDbId = String.valueOf(user.getId());
            List<Paper> files = paperAccessService.accessiblePapers(userDbId);

            logger.info("成功获取用户可访问论文列表: userId={}, paperCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户可访问论文列表失败: userId={}", userId, e);
            throw new RuntimeException("获取可访问论文列表失败: " + e.getMessage(), e);
        }
    }

    public List<Paper> getAccessiblePapersByIds(String userId, List<String> paperIds) {
        List<String> requestedIds = paperIds == null ? List.of() : paperIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (requestedIds.isEmpty()) {
            return List.of();
        }
        User user = resolveUser(userId);
        String userDbId = String.valueOf(user.getId());
        return paperAccessService.accessiblePapers(userDbId, requestedIds);
    }

    public Page<Paper> getAccessiblePapersPage(String userId, String orgTags, Pageable pageable) {
        logger.info("分页获取用户可访问论文列表: userId={}, page={}, size={}",
                userId,
                pageable == null ? null : pageable.getPageNumber(),
                pageable == null ? null : pageable.getPageSize());

        try {
            User user = resolveUser(userId);
            String userDbId = String.valueOf(user.getId());
            Pageable effectivePageable = pageable == null ? Pageable.ofSize(10) : pageable;

            return page(paperAccessService.accessiblePapers(userDbId), effectivePageable);
        } catch (Exception e) {
            logger.error("分页获取用户可访问论文列表失败: userId={}", userId, e);
            throw new RuntimeException("分页获取可访问论文列表失败: " + e.getMessage(), e);
        }
    }

    public Page<Paper> searchAccessiblePaperCandidates(String userId,
                                                       String orgTags,
                                                       String query,
                                                       String readiness,
                                                       Pageable pageable) {
        logger.info("分页搜索用户可访问论文候选: userId={}, query={}, readiness={}, page={}, size={}",
                userId,
                query,
                readiness,
                pageable == null ? null : pageable.getPageNumber(),
                pageable == null ? null : pageable.getPageSize());

        try {
            User user = resolveUser(userId);
            String userDbId = String.valueOf(user.getId());
            Pageable effectivePageable = pageable == null ? Pageable.ofSize(10) : pageable;
            String normalizedQuery = normalizeSearchQuery(query);

            boolean searchableOnly = isSearchableReadiness(readiness);
            List<Paper> matches = paperAccessService.accessiblePapers(userDbId).stream()
                    .filter(paper -> !searchableOnly || paperSearchabilityService.isSearchable(paper))
                    .filter(paper -> matchesPaperQuery(paper, normalizedQuery))
                    .toList();
            return page(matches, effectivePageable);
        } catch (Exception e) {
            logger.error("分页搜索用户可访问论文候选失败: userId={}", userId, e);
            throw new RuntimeException("分页搜索可访问论文候选失败: " + e.getMessage(), e);
        }
    }

    private String normalizeSearchQuery(String query) {
        if (query == null) {
            return null;
        }
        return query.trim();
    }

    private boolean isSearchableReadiness(String readiness) {
        return readiness != null && "searchable".equalsIgnoreCase(readiness.trim());
    }

    private Page<Paper> page(List<Paper> papers, Pageable pageable) {
        List<Paper> sorted = new ArrayList<>(papers == null ? List.of() : papers);
        sorted.sort(Comparator.comparing(Paper::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        int start = (int) Math.min(pageable.getOffset(), sorted.size());
        int end = Math.min(start + pageable.getPageSize(), sorted.size());
        return new PageImpl<>(sorted.subList(start, end), pageable, sorted.size());
    }

    private boolean matchesPaperQuery(Paper paper, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return contains(paper.getPaperTitle(), needle)
                || contains(paper.getOriginalFilename(), needle)
                || contains(paper.getAuthors(), needle)
                || contains(paper.getVenue(), needle)
                || contains(paper.getAbstractText(), needle)
                || contains(paper.getDoi(), needle)
                || contains(paper.getArxivId(), needle)
                || contains(paper.getPaperId(), needle)
                || (paper.getPublicationYear() != null
                && paper.getPublicationYear().toString().contains(needle));
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * 获取用户上传的所有论文列表。
     *
     * @param userId 用户ID
     * @return 用户上传的论文列表
     */
    public List<Paper> getUserUploadedPapers(String userId) {
        logger.info("获取用户上传的论文列表: userId={}", userId);

        try {
            List<Paper> files = paperRepository.findByUserId(userId);
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

    /**
     * 生成论文 PDF 下载链接。
     *
     * @param paperId 论文标识，当前使用 PDF 内容哈希
     * @return 预签名下载URL
     */
    public String generateDownloadUrl(String paperId) {
        return generateDownloadUrl(paperId, PdfDownloadHeaders.Disposition.INLINE);
    }

    public String generateAttachmentDownloadUrl(String paperId) {
        return generateDownloadUrl(paperId, PdfDownloadHeaders.Disposition.ATTACHMENT);
    }

    public InputStream openMergedPdfStream(String paperId) {
        try {
            return uploadService.getMergedFileStream(paperId);
        } catch (Exception e) {
            logger.error("打开论文 PDF 流失败: paperId={}", paperId, e);
            throw new RuntimeException("无法打开论文 PDF", e);
        }
    }

    public InputStream openMergedPdfRangeStream(String paperId, long offset, long length) {
        try {
            return uploadService.getMergedFileRangeStream(paperId, offset, length);
        } catch (Exception e) {
            logger.error("打开论文 PDF 范围流失败: paperId={}, offset={}, length={}", paperId, offset, length, e);
            throw new RuntimeException("无法打开论文 PDF 范围流", e);
        }
    }

    private String generateDownloadUrl(String paperId, PdfDownloadHeaders.Disposition disposition) {
        logger.info("生成论文 PDF 下载链接: paperId={}", paperId);

        try {
            Paper paper = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId)
                    .orElseThrow(() -> new RuntimeException("论文不存在: " + paperId));

            String objectName = "merged/" + paperId;
            String presignedUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket("uploads")
                            .object(objectName)
                            .expiry(3600)
                            .extraQueryParams(PdfDownloadHeaders.presignedQueryParams(
                                    paper.getOriginalFilename(),
                                    paperId,
                                    disposition
                            ))
                            .build()
            );
            logger.info("成功生成论文 PDF 下载链接: paperId={}, paperTitle={}, objectName={}",
                    paperId, paper.getOriginalFilename(), objectName);

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
            paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId)
                    .orElseThrow(() -> new RuntimeException("论文不存在: " + paperId));
            return "PaperLoom 当前仅支持 PDF 论文预览。";

        } catch (Exception e) {
            logger.error("获取论文预览兜底内容失败: paperId={}, paperTitle={}", paperId, paperTitle, e);
            return "预览失败: " + e.getMessage();
        }
    }

}
