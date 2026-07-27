package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.paper.parser.PaperPdfParser;
import io.github.chzarles.paperloom.paper.parser.ParsedPaper;
import io.github.chzarles.paperloom.repository.PaperRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

@Service
public class ParseService {

    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);

    @Autowired
    private PaperRepository paperRepository;

    @Autowired
    private PaperPdfParser paperPdfParser;

    @Autowired
    private PaperParserArtifactService paperParserArtifactService;

    @Autowired
    private PaperVisualAssetService paperVisualAssetService;

    @Autowired
    private PaperReadingModelService paperReadingModelService;

    @Value("${paper.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;

    /**
     * Parses a research paper PDF and persists the current reading model artifacts.
     */
    public void parseAndSave(String paperId, InputStream fileStream,
                             String userId) throws IOException {
        parseAndSave(paperId, fileStream, null, userId);
    }

    public void parseAndSave(String paperId, InputStream fileStream, String originalFilename,
                             String userId) throws IOException {
        logger.info("开始解析论文 PDF，paperId: {}, userId: {}", paperId, userId);

        checkMemoryThreshold();

        byte[] pdfBytes = fileStream.readAllBytes();
        Integer physicalPageCount = physicalPageCount(pdfBytes);
        updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_MINERU_RUNNING);
        ParsedPaper parsedPaper = paperPdfParser.parse(new ByteArrayInputStream(pdfBytes), originalFilename);
        updatePaperMetadata(paperId, parsedPaper);
        paperParserArtifactService.saveParserArtifact(paperId, parsedPaper, userId);
        updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_MINERU_ARTIFACT_SAVED);
        PaperReadingModel readingModel = physicalPageCount == null
                ? paperReadingModelService.replaceFromParsedPaper(
                paperId,
                parsedPaper,
                userId
        )
                : paperReadingModelService.replaceFromParsedPaper(
                paperId,
                parsedPaper,
                physicalPageCount,
                userId
        );
        if (readingModel == null || readingModel.getModelStatus() != PaperReadingModelStatus.READING_MODEL_READY) {
            String reason = readingModel == null ? "READING_MODEL_MISSING" : readingModel.getFailureReason();
            throw new PaperReadingModelNotReadyException("Reading Model is not ready for paperId="
                    + paperId + ", reason=" + reason);
        }
        updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_MAPPING_STRUCTURED_CONTENT);
        updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_RENDERING_VISUAL_ASSETS);
        paperVisualAssetService.replaceVisualAssets(
                paperId,
                readingModel.getModelVersion(),
                pdfBytes,
                parsedPaper,
                userId
        );
        logger.info("论文 PDF 结构化解析和入库完成，paperId: {}", paperId);
    }

    private Integer physicalPageCount(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return null;
        }
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            return document.getNumberOfPages();
        } catch (Exception e) {
            logger.warn("无法读取 PDF 物理页数，将使用 parser 页数: {}", e.getMessage());
            return null;
        }
    }

    private void updatePaperMetadata(String paperId, ParsedPaper parsedPaper) {
        if (parsedPaper == null || parsedPaper.metadata() == null) {
            return;
        }

        var metadata = parsedPaper.metadata();
        boolean hasTitle = metadata.title() != null && !metadata.title().isBlank();
        boolean hasAuthors = metadata.authors() != null && !metadata.authors().isBlank();
        if (!hasTitle && !hasAuthors) {
            return;
        }

        paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId).ifPresent(paper -> {
            if (hasTitle) {
                paper.setPaperTitle(metadata.title().trim());
            }
            if (hasAuthors) {
                paper.setAuthors(metadata.authors().trim());
            }
            paperRepository.save(paper);
            logger.info("论文 parser 元数据已回写论文记录: paperId={}, hasTitle={}, hasAuthors={}",
                    paperId, hasTitle, hasAuthors);
        });
    }

    private void updatePipelineStatus(String paperId, String status) {
        if (paperId == null || paperId.isBlank() || status == null || status.isBlank()) {
            return;
        }
        paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId).ifPresent(paper -> {
            paper.setVectorizationStatus(status);
            paper.setVectorizationErrorMessage(null);
            paperRepository.save(paper);
        });
    }

    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double memoryUsage = (double) usedMemory / maxMemory;

        if (memoryUsage > maxMemoryThreshold) {
            logger.warn("内存使用率过高: {:.2f}%, 触发垃圾回收", memoryUsage * 100);
            System.gc();

            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double) usedMemory / maxMemory;

            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("内存不足，无法处理大 PDF。当前使用率: " +
                        String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }

}
