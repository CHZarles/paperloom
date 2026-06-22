package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.model.PaperProcessingTask;
import com.yizhaoqi.smartpai.service.PaperService;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import io.minio.errors.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@Service
@Slf4j
public class PaperProcessingConsumer {

    private final ParseService parseService;
    private final VectorizationService vectorizationService;
    private final PaperService paperService;
    @Autowired
    private KafkaConfig kafkaConfig;


    public PaperProcessingConsumer(
            ParseService parseService,
            VectorizationService vectorizationService,
            PaperService paperService
    ) {
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.paperService = paperService;
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
            // 下载论文 PDF
            fileStream = downloadPaperFromStorage(task.getPaperObjectUrl());
            // 在 downloadPaperFromStorage 返回后立即检查流是否可读
            if (fileStream == null) {
                throw new IOException("流为空");
            }

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

    /**
     * 从存储系统下载论文 PDF。
     *
     * @param paperObjectUrl PDF 对象 URL 或本地路径
     * @return PDF 输入流
     */
    private InputStream downloadPaperFromStorage(String paperObjectUrl) throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        log.info("Downloading paper PDF from storage: {}", paperObjectUrl);

        try {
            // 如果是本地路径
            File file = new File(paperObjectUrl);
            if (file.exists()) {
                log.info("Detected local paper PDF path: {}", paperObjectUrl);
                return new FileInputStream(file);
            }

            // 如果是远程 URL
            if (paperObjectUrl.startsWith("http://") || paperObjectUrl.startsWith("https://")) {
                log.info("Detected remote paper PDF URL: {}", paperObjectUrl);
                URL url = new URL(paperObjectUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000); // 连接超时30秒
                connection.setReadTimeout(180000);   // 读取超时时间3分钟

                // 添加必要的请求头
                connection.setRequestProperty("User-Agent", "PaperLoom-PaperProcessor/1.0");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    log.info("Successfully connected to paper PDF URL, starting download...");
                    return connection.getInputStream();
                } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    log.error("Access forbidden - possible expired presigned URL");
                    throw new IOException("Access forbidden - the presigned URL may have expired");
                } else {
                    log.error("Failed to download paper PDF, HTTP response code: {} for URL: {}", responseCode, paperObjectUrl);
                    throw new IOException(String.format("Failed to download paper PDF, HTTP response code: %d", responseCode));
                }
            }

            // 如果既不是本地路径也不是 URL
            throw new IllegalArgumentException("Unsupported paper object URL format: " + paperObjectUrl);
        } catch (Exception e) {
            log.error("Error downloading paper PDF from storage: {}", paperObjectUrl, e);
            return null; // 或者抛出异常
        }
    }

}
