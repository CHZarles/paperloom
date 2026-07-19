package io.github.chzarles.paperloom.consumer;

import io.github.chzarles.paperloom.config.KafkaConfig;
import io.github.chzarles.paperloom.model.PaperProcessingTask;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.service.PaperService;
import io.github.chzarles.paperloom.service.PaperSearchabilityService;
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
    @Autowired
    private KafkaConfig kafkaConfig;


    public PaperProcessingConsumer(
            ParseService parseService,
            RetrievalIndexingService retrievalIndexingService,
            PaperService paperService,
            UploadService uploadService,
            PaperSearchabilityService searchabilityService,
            PaperReadingModelRepository readingModelRepository
    ) {
        this.parseService = parseService;
        this.retrievalIndexingService = retrievalIndexingService;
        this.paperService = paperService;
        this.uploadService = uploadService;
        this.searchabilityService = searchabilityService;
        this.readingModelRepository = readingModelRepository;
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
                    task.getPaperTitle(), task.getUserId(), null, false);
            log.info("论文 PDF 解析完成，paperId: {}", task.getPaperId());

            RetrievalIndexingService.IndexingResult indexingResult = retrievalIndexingService.indexWithMetrics(
                    task.getPaperId(), task.getUserId());
            paperService.markVectorizationCompleted(task.getPaperId(), indexingResult);
            log.info("论文词法检索索引完成，paperId: {}", task.getPaperId());
        } catch (Exception e) {
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
        } catch (Exception e) {
            paperService.markVectorizationFailed(task.getPaperId(), e);
            log.error("Error retrying failed initial index task: {}", task, e);
            throw new RuntimeException("Error retrying failed initial index task", e);
        }
    }

}
