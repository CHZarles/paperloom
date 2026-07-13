package io.github.chzarles.paperloom.consumer;

import io.github.chzarles.paperloom.config.KafkaConfig;
import io.github.chzarles.paperloom.model.PaperProcessingTask;
import io.github.chzarles.paperloom.service.PaperService;
import io.github.chzarles.paperloom.service.ParseService;
import io.github.chzarles.paperloom.service.UploadService;
import io.github.chzarles.paperloom.service.VectorizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.*;

@Service
@Slf4j
public class PaperProcessingConsumer {

    private final ParseService parseService;
    private final VectorizationService vectorizationService;
    private final PaperService paperService;
    private final UploadService uploadService;
    @Autowired
    private KafkaConfig kafkaConfig;


    public PaperProcessingConsumer(
            ParseService parseService,
            VectorizationService vectorizationService,
            PaperService paperService,
            UploadService uploadService
    ) {
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.paperService = paperService;
        this.uploadService = uploadService;
    }

    @KafkaListener(topics = "#{kafkaConfig.getPaperProcessingTopic()}", groupId = "#{kafkaConfig.getPaperProcessingGroupId()}")
    public void processTask(PaperProcessingTask task) {
        log.info("Received task: {}", task);
        log.info("论文权限信息: userId={}, orgTag={}, isPublic={}", 
                task.getUserId(), task.getOrgTag(), task.isPublic());

        paperService.markVectorizationProcessing(task.getPaperId(), false);

        if (PaperProcessingTask.TASK_TYPE_REINDEX.equals(task.getTaskType())) {
            processReindexTask(task);
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
                    task.getPaperTitle(), task.getUserId(), task.getOrgTag(), task.isPublic());
            log.info("论文 PDF 解析完成，paperId: {}", task.getPaperId());

            // 向量化处理
            VectorizationService.VectorizationUsageResult vectorizationResult = vectorizationService.vectorizeWithUsage(
                    task.getPaperId(),
                    task.getUserId(),
                    task.getOrgTag(),
                    task.isPublic(),
                    task.getUserId()
            );
            paperService.markVectorizationCompleted(task.getPaperId(), vectorizationResult);
            log.info("论文向量化完成，paperId: {}", task.getPaperId());
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

    private void processReindexTask(PaperProcessingTask task) {
        try {
            String requesterId = task.getRequesterId() == null || task.getRequesterId().isBlank()
                    ? task.getUserId()
                    : task.getRequesterId();
            paperService.reindexPaper(task.getPaperId(), requesterId);
        } catch (Exception e) {
            paperService.markVectorizationFailed(task.getPaperId(), e);
            log.error("Error reindexing task: {}", task, e);
            throw new RuntimeException("Error reindexing task", e);
        }
    }

}
