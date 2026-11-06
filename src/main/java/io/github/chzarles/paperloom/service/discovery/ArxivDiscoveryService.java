package io.github.chzarles.paperloom.service.discovery;

import io.github.chzarles.paperloom.config.KafkaConfig;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperProcessingTask;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.service.PaperSearchabilityService;
import io.github.chzarles.paperloom.service.UploadService;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * arXiv 论文发现与导入。
 *
 * <p>这个服务只做两件事：把 arXiv 检索结果读出来，把用户明确点选的一篇论文送进现有 PDF 解析链路。
 * 它不产生可引用证据，也不进入研究对话的论文范围；导入后的论文与普通上传论文没有区别，
 * 仍然是当前用户的个人库论文，身份仍然是 PDF 内容 MD5。
 */
@Service
public class ArxivDiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(ArxivDiscoveryService.class);

    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int MAX_RESULTS_CAP = 50;
    private static final int AUTHORS_MAX_LENGTH = 1000;
    private static final Set<String> IN_FLIGHT_STATUSES = Set.of(
            Paper.VECTORIZATION_STATUS_PENDING,
            Paper.VECTORIZATION_STATUS_PROCESSING,
            Paper.VECTORIZATION_STATUS_MINERU_RUNNING,
            Paper.VECTORIZATION_STATUS_MINERU_ARTIFACT_SAVED,
            Paper.VECTORIZATION_STATUS_MAPPING_STRUCTURED_CONTENT,
            Paper.VECTORIZATION_STATUS_RENDERING_VISUAL_ASSETS,
            Paper.VECTORIZATION_STATUS_INDEXING
    );
    private static final DateTimeFormatter HOUR_BUCKET = DateTimeFormatter.ofPattern("yyyyMMddHH");

    private final ArxivClient arxivClient;
    private final PaperRepository paperRepository;
    private final UploadService uploadService;
    private final PaperSearchabilityService paperSearchabilityService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaConfig kafkaConfig;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${paper.discovery.arxiv.imports-per-hour:20}")
    private int importsPerHour;

    public ArxivDiscoveryService(ArxivClient arxivClient,
                                 PaperRepository paperRepository,
                                 UploadService uploadService,
                                 PaperSearchabilityService paperSearchabilityService,
                                 KafkaTemplate<String, Object> kafkaTemplate,
                                 KafkaConfig kafkaConfig,
                                 RedisTemplate<String, Object> redisTemplate) {
        this.arxivClient = arxivClient;
        this.paperRepository = paperRepository;
        this.uploadService = uploadService;
        this.paperSearchabilityService = paperSearchabilityService;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaConfig = kafkaConfig;
        this.redisTemplate = redisTemplate;
    }

    public ArxivSearchPage search(String query, Integer start, Integer maxResults, String sort, String userId) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("检索词不能为空");
        }
        int limit = clampMaxResults(maxResults);
        int offset = Math.max(0, start == null ? 0 : start);
        ArxivClient.ArxivSortOrder sortOrder = ArxivClient.ArxivSortOrder.fromRequest(sort);
        ArxivAtomParser.ArxivFeed feed = arxivClient.search(trimmed, offset, limit, sortOrder);
        Map<String, String> ownership = ownershipByArxivId(userId, feed.papers());
        logger.info("arXiv 检索完成 => query: {}, start: {}, limit: {}, sort: {}, hits: {}, total: {}",
                trimmed, offset, limit, sortOrder, feed.papers().size(), feed.totalResults());
        List<ArxivSearchHit> items = feed.papers().stream()
                .map(paper -> new ArxivSearchHit(
                        paper,
                        ownership.getOrDefault(paper.arxivId(), ArxivSearchHit.LIBRARY_NONE)))
                .toList();
        return new ArxivSearchPage(items, feed.totalResults(), offset, limit);
    }

    /**
     * 用一次查询判断检索结果里哪些已在用户文献库中，避免逐条查询。
     */
    private Map<String, String> ownershipByArxivId(String userId, List<ArxivPaper> papers) {
        List<String> arxivIds = papers.stream().map(ArxivPaper::arxivId).filter(id -> id != null && !id.isBlank()).toList();
        if (userId == null || userId.isBlank() || arxivIds.isEmpty()) {
            return Map.of();
        }
        List<Paper> owned = paperRepository.findByUserIdAndArxivIdIn(userId, arxivIds);
        if (owned.isEmpty()) {
            return Map.of();
        }
        Set<String> searchable = paperSearchabilityService.searchablePaperIds(owned);
        Map<String, String> status = new HashMap<>();
        for (Paper paper : owned) {
            String candidate;
            String vectorizationStatus = paper.getVectorizationStatus();
            if (searchable.contains(paper.getPaperId())) {
                candidate = ArxivSearchHit.LIBRARY_READY;
            } else if (vectorizationStatus != null && IN_FLIGHT_STATUSES.contains(vectorizationStatus)) {
                candidate = ArxivSearchHit.LIBRARY_PROCESSING;
            } else {
                // 解析完成但索引缺失（或状态为空）时，重新入库才能真正可检索。
                candidate = ArxivSearchHit.LIBRARY_FAILED;
            }
            status.merge(paper.getArxivId(), candidate, ArxivDiscoveryService::higherPriority);
        }
        return status;
    }

    /**
     * 同一 arXiv 标识可能有多条记录（不同版本得到不同 MD5），展示优先级 READY &gt; PROCESSING &gt; FAILED。
     */
    private static String higherPriority(String left, String right) {
        if (ArxivSearchHit.LIBRARY_READY.equals(left) || ArxivSearchHit.LIBRARY_READY.equals(right)) {
            return ArxivSearchHit.LIBRARY_READY;
        }
        if (ArxivSearchHit.LIBRARY_PROCESSING.equals(left) || ArxivSearchHit.LIBRARY_PROCESSING.equals(right)) {
            return ArxivSearchHit.LIBRARY_PROCESSING;
        }
        return ArxivSearchHit.LIBRARY_FAILED;
    }

    public ArxivImportResult importPaper(String rawArxivId, String userId) {
        String arxivId = ArxivIdentifier.requireValid(rawArxivId);

        Optional<Paper> alreadyImported = paperRepository
                .findFirstByUserIdAndArxivIdOrderByCreatedAtDesc(userId, arxivId);
        if (alreadyImported.isPresent()) {
            Paper paper = alreadyImported.get();
            Optional<ArxivImportResult> shortCircuit = existingPaperResult(paper, arxivId);
            if (shortCircuit.isPresent()) {
                return shortCircuit.get();
            }
        }

        // 配额只拦真正会产生下载和解析工作的导入，已入库的论文直接返回结果。
        enforceImportBudget(userId);

        ArxivPaper metadata = arxivClient.findById(arxivId)
                .orElseThrow(() -> new ArxivResponseException("arXiv 上找不到该论文: " + arxivId));
        byte[] pdf = arxivClient.downloadPdf(arxivId);
        String paperId = DigestUtils.md5Hex(pdf);

        Optional<Paper> sameContent = paperRepository
                .findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(paperId, userId);
        if (sameContent.isPresent()) {
            Paper existing = sameContent.get();
            Optional<ArxivImportResult> shortCircuit = existingPaperResult(existing, arxivId);
            if (shortCircuit.isPresent()) {
                backfillArxivId(existing, arxivId);
                return shortCircuit.get();
            }
        }

        Paper paper = sameContent.orElseGet(() -> newPaper(paperId, userId));
        try {
            uploadService.storeMergedPdf(paperId, pdf);
        } catch (Exception e) {
            throw new CustomException("保存 arXiv PDF 失败: " + e.getMessage(), HttpStatus.BAD_GATEWAY);
        }

        applyMetadata(paper, metadata, arxivId, pdf.length, userId);
        try {
            paperRepository.save(paper);
        } catch (DataIntegrityViolationException e) {
            // 同一篇论文被并发点击：唯一键 (file_md5, user_id) 拦住了第二条记录。
            Paper concurrent = paperRepository
                    .findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(paperId, userId)
                    .orElseThrow(() -> e);
            return existingPaperResult(concurrent, arxivId).orElseGet(() -> new ArxivImportResult(
                    paperId,
                    arxivId,
                    concurrent.getPaperTitle(),
                    ArxivImportResult.STATUS_PROCESSING,
                    "这篇论文正在入库中，请稍后刷新"
            ));
        }

        enqueueParse(paper, userId);
        recordImport(userId);
        logger.info("arXiv 论文已入库并排队解析 => arxivId: {}, paperId: {}, userId: {}, size: {}",
                arxivId, paperId, userId, pdf.length);

        return new ArxivImportResult(
                paperId,
                arxivId,
                paper.getPaperTitle(),
                ArxivImportResult.STATUS_IMPORTED,
                "已加入文献库，正在解析并建立索引"
        );
    }

    private Optional<ArxivImportResult> existingPaperResult(Paper paper, String arxivId) {
        if (paperSearchabilityService.isSearchable(paper)) {
            return Optional.of(new ArxivImportResult(
                    paper.getPaperId(),
                    arxivId,
                    paper.getPaperTitle(),
                    ArxivImportResult.STATUS_READY,
                    "这篇论文已在你的文献库中，可以直接提问"
            ));
        }
        String vectorizationStatus = paper.getVectorizationStatus();
        if (vectorizationStatus != null && IN_FLIGHT_STATUSES.contains(vectorizationStatus)) {
            return Optional.of(new ArxivImportResult(
                    paper.getPaperId(),
                    arxivId,
                    paper.getPaperTitle(),
                    ArxivImportResult.STATUS_PROCESSING,
                    "这篇论文正在解析中，完成后即可提问"
            ));
        }
        return Optional.empty();
    }

    /**
     * 手动上传的同一篇 PDF 没有 arXiv 标识：命中内容哈希时补上，检索结果才能显示"已入库"。
     */
    private void backfillArxivId(Paper paper, String arxivId) {
        if (paper.getArxivId() != null && !paper.getArxivId().isBlank()) {
            return;
        }
        // 只写 arxiv_id，避免把并发解析线程刚更新的状态用旧快照覆盖回去。
        paperRepository.backfillArxivIdIfAbsent(paper.getPaperId(), paper.getUserId(), arxivId);
        paper.setArxivId(arxivId);
    }

    private Paper newPaper(String paperId, String userId) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setUserId(userId);
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_PENDING);
        return paper;
    }

    private void applyMetadata(Paper paper, ArxivPaper metadata, String arxivId, int size, String userId) {
        String title = metadata.title() == null || metadata.title().isBlank() ? arxivId : metadata.title();
        paper.setOriginalFilename(arxivId + ".pdf");
        paper.setPaperTitle(title.length() > 255 ? title.substring(0, 255) : title);
        String authors = metadata.authorsText();
        paper.setAuthors(authors.length() > AUTHORS_MAX_LENGTH ? authors.substring(0, AUTHORS_MAX_LENGTH) : authors);
        paper.setPublicationYear(metadata.publishedYear());
        paper.setAbstractText(metadata.summary());
        paper.setArxivId(arxivId);
        paper.setTotalSize(size);
        paper.setStatus(Paper.STATUS_COMPLETED);
        paper.setMergedAt(LocalDateTime.now());
        paper.setUserId(userId);
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_PROCESSING);
        paper.setVectorizationErrorMessage(null);
        paper.setRetrievalIndexedTokenCount(null);
        paper.setRetrievalIndexedLocationCount(null);
    }

    private void enqueueParse(Paper paper, String userId) {
        PaperProcessingTask task = new PaperProcessingTask(
                paper.getPaperId(),
                null,
                paper.getPaperTitle(),
                userId,
                PaperProcessingTask.TASK_TYPE_UPLOAD_PROCESS,
                userId
        );
        try {
            kafkaTemplate.executeInTransaction(sender -> {
                sender.send(kafkaConfig.getPaperProcessingTopic(), paper.getPaperId(), task);
                return true;
            });
        } catch (RuntimeException e) {
            paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_FAILED);
            paper.setVectorizationErrorMessage("排队解析失败: " + e.getMessage());
            paperRepository.save(paper);
            throw new CustomException("arXiv 论文已保存，但解析任务排队失败: " + e.getMessage(),
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private void enforceImportBudget(String userId) {
        if (importsPerHour <= 0) {
            return;
        }
        try {
            Object current = redisTemplate.opsForValue().get(budgetKey(userId));
            int used = current == null ? 0 : Integer.parseInt(String.valueOf(current));
            if (used >= importsPerHour) {
                throw new CustomException("每小时最多导入 " + importsPerHour + " 篇 arXiv 论文，请稍后再试",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("导入配额检查失败，放行本次导入 => userId: {}, error: {}", userId, e.getMessage());
        }
    }

    private void recordImport(String userId) {
        if (importsPerHour <= 0) {
            return;
        }
        try {
            String key = budgetKey(userId);
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofHours(2));
            }
        } catch (Exception e) {
            logger.warn("导入配额计数失败 => userId: {}, error: {}", userId, e.getMessage());
        }
    }

    private String budgetKey(String userId) {
        return "paperloom:discovery:arxiv:import:" + userId + ":" + LocalDateTime.now().format(HOUR_BUCKET);
    }

    private int clampMaxResults(Integer maxResults) {
        if (maxResults == null) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.max(1, Math.min(MAX_RESULTS_CAP, maxResults));
    }
}
