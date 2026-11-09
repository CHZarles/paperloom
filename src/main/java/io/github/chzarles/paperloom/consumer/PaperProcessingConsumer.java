package io.github.chzarles.paperloom.consumer;

import io.github.chzarles.paperloom.config.KafkaConfig;
import io.github.chzarles.paperloom.model.PaperProcessingTask;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.service.PaperService;
import io.github.chzarles.paperloom.service.PaperSearchabilityService;
import io.github.chzarles.paperloom.service.PaperPublicationService;
import io.github.chzarles.paperloom.service.ParseService;
import io.github.chzarles.paperloom.service.UploadService;
import io.github.chzarles.paperloom.service.RetrievalIndexingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.*;

@Service
@Slf4j
public class PaperProcessingConsumer {

    private final ParseService parseService;
    private final RetrievalIndexingService retrievalIndexingService;
    private final PaperService paperService;
    private final UploadService uploadService;
    private final PaperSearchabilityService searchabilityService;
    private final PaperReadingModelRepository readingModelRepository;
    private final PaperPublicationService publicationService;
    @Autowired
    private KafkaConfig kafkaConfig;


    public PaperProcessingConsumer(
            ParseService parseService,
            RetrievalIndexingService retrievalIndexingService,
            PaperService paperService,
            UploadService uploadService,
            PaperSearchabilityService searchabilityService,
            PaperReadingModelRepository readingModelRepository,
            PaperPublicationService publicationService
    ) {
        this.parseService = parseService;
        this.retrievalIndexingService = retrievalIndexingService;
        this.paperService = paperService;
        this.uploadService = uploadService;
        this.searchabilityService = searchabilityService;
        this.readingModelRepository = readingModelRepository;
        this.publicationService = publicationService;
    }

    @KafkaListener(topics = "#{kafkaConfig.getPaperProcessingTopic()}", groupId = "#{kafkaConfig.getPaperProcessingGroupId()}")
    public void processTask(PaperProcessingTask task) {
        log.info("Received task: {}", task);

        if (searchabilityService.isSearchable(task.getPaperId())) {
            var model = readingModelRepository.findFirstByPaperIdAndIsCurrentTrue(task.getPaperId()).orElseThrow();
            paperService.markVectorizationCompleted(task.getPaperId(), new RetrievalIndexingService.IndexingResult(
                    0,
                    model.getRetrievalIndexedLocationCount() == null ? 0 : model.getRetrievalIndexedLocationCount(),
                    model.getRetrievalIndexContract() == null ? "" : model.getRetrievalIndexContract()
            ));
            publicationService.publishIfAdministrator(task.getPaperId(), task.getUserId());
            log.info("Canonical paper is already searchable; skipped duplicate processing: paperId={}", task.getPaperId());
            return;
        }

        paperService.markVectorizationProcessing(task.getPaperId(), false);

        if (PaperProcessingTask.TASK_TYPE_RETRY_INITIAL.equals(task.getTaskType())
                && readingModelRepository.findFirstByPaperIdAndIsCurrentTrue(task.getPaperId())
                .filter(model -> model.getModelStatus() == PaperReadingModelStatus.READING_MODEL_READY)
                .isPresent()) {
            retryFailedIndex(task);
            return;
        }

        InputStream fileStream = null;
        try {
            fileStream = uploadService.getMergedFileStream(task.getPaperId());

            // 强制转换为可缓存流
            if (!fileStream.markSupported()) {
                fileStream = new BufferedInputStream(fileStream);
            }

            // 解析论文 PDF
            parseService.parseAndSave(task.getPaperId(), fileStream,
                    task.getPaperTitle(), task.getUserId());
            log.info("论文 PDF 解析完成，paperId: {}", task.getPaperId());

            RetrievalIndexingService.IndexingResult indexingResult = retrievalIndexingService.indexWithMetrics(
                    task.getPaperId(), task.getUserId());
            paperService.markVectorizationCompleted(task.getPaperId(), indexingResult);
            publicationService.publishIfAdministrator(task.getPaperId(), task.getUserId());
            log.info("论文词法检索索引完成，paperId: {}", task.getPaperId());
        } catch (Exception e) {
            // 并发护栏不是失败：同一篇论文的另一条任务已经在建索引，这条安静让路即可，
            // 否则会把进行中的索引错误地标成 FAILED（用户看到"解析失败"但内容实际完好）。
            if (isConcurrentIndexClaim(e)) {
                log.warn("论文索引并发护栏触发（另一条任务正在处理同一篇），本条跳过: paperId: {}",
                        task.getPaperId());
                return;
            }
            paperService.markVectorizationFailed(task.getPaperId(), e);
            log.error("Error processing task: {}", task, e);
            // 抛出异常让 Kafka 的 DefaultErrorHandler 捕获并触发重试 / 死信
            throw new RuntimeException("Error processing task", e);
        } finally {
            // 确保关闭输入流
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    log.error("Error closing file stream", e);
                }
            }
        }
    }

    private void retryFailedIndex(PaperProcessingTask task) {
        try {
            String requesterId = task.getRequesterId() == null || task.getRequesterId().isBlank()
                    ? task.getUserId()
                    : task.getRequesterId();
            RetrievalIndexingService.IndexingResult result = retrievalIndexingService.indexWithMetrics(
                    task.getPaperId(), requesterId);
            paperService.markVectorizationCompleted(task.getPaperId(), result);
            publicationService.publishIfAdministrator(task.getPaperId(), task.getUserId());
        } catch (Exception e) {
            if (isConcurrentIndexClaim(e)) {
                log.warn("重试撞上索引并发护栏（另一条任务正在处理同一篇），本条跳过: paperId: {}",
                        task.getPaperId());
                return;
            }
            paperService.markVectorizationFailed(task.getPaperId(), e);
            log.error("Error retrying failed initial index task: {}", task, e);
            throw new RuntimeException("Error retrying failed initial index task", e);
        }
    }

    /** 索引并发护栏（含全量重建互斥）：出现在因果链任何一层都视为"让路"而非失败 */
    private boolean isConcurrentIndexClaim(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof IllegalStateException) {
                String message = current.getMessage();
                if ("PAPER_INDEX_OPERATION_RUNNING".equals(message)
                        || "FULL_REBUILD_ALREADY_RUNNING".equals(message)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

}
