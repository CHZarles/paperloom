package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperTextChunk;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.paper.parser.PaperChunkBuilder;
import com.yizhaoqi.smartpai.paper.parser.PaperChunkCandidate;
import com.yizhaoqi.smartpai.paper.parser.PaperPdfParser;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class ParseService {

    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);

    @Autowired
    private PaperTextChunkRepository paperTextChunkRepository;

    @Autowired
    private PaperRepository paperRepository;

    @Autowired
    private UsageQuotaService usageQuotaService;

    @Autowired
    private PaperPdfParser paperPdfParser;

    @Autowired
    private PaperChunkBuilder paperChunkBuilder;

    @Autowired
    private PaperParserArtifactService paperParserArtifactService;

    @Autowired
    private PaperTableService paperTableService;

    @Autowired
    private PaperVisualAssetService paperVisualAssetService;

    @Autowired
    private PaperFigureService paperFigureService;

    @Autowired
    private PaperFormulaService paperFormulaService;

    @Value("${paper.parsing.chunk-size:512}")
    private int chunkSize;

    @Value("${paper.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;

    /**
     * Parses a research paper PDF, builds page-aware chunks, and persists chunk provenance.
     */
    public void parseAndSave(String paperId, InputStream fileStream,
                             String userId, String orgTag, boolean isPublic) throws IOException {
        parseAndSave(paperId, fileStream, null, userId, orgTag, isPublic);
    }

    public void parseAndSave(String paperId, InputStream fileStream, String originalFilename,
                             String userId, String orgTag, boolean isPublic) throws IOException {
        logger.info("开始解析论文 PDF，paperId: {}, userId: {}, orgTag: {}, isPublic: {}",
                paperId, userId, orgTag, isPublic);

        checkMemoryThreshold();

        byte[] pdfBytes = fileStream.readAllBytes();
        updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_MINERU_RUNNING);
        ParsedPaper parsedPaper = paperPdfParser.parse(new ByteArrayInputStream(pdfBytes), originalFilename);
        updatePaperMetadata(paperId, parsedPaper);
        paperParserArtifactService.saveParserArtifact(paperId, parsedPaper, userId, orgTag, isPublic);
        updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_MINERU_ARTIFACT_SAVED);
        updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_MAPPING_STRUCTURED_CONTENT);
        var tables = paperTableService.replaceTables(paperId, parsedPaper, userId, orgTag, isPublic);
        var figures = paperFigureService.replaceFigures(paperId, parsedPaper, userId, orgTag, isPublic);
        paperFormulaService.replaceFormulas(paperId, parsedPaper, userId, orgTag, isPublic);
        updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_RENDERING_VISUAL_ASSETS);
        paperVisualAssetService.replaceVisualAssets(paperId, pdfBytes, parsedPaper, tables, figures, userId, orgTag, isPublic);
        updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_CHUNKING);
        List<PaperChunkCandidate> chunks = paperChunkBuilder.buildChunks(parsedPaper, chunkSize);
        saveStructuredChunks(paperId, chunks, userId, orgTag, isPublic);
        logger.info("论文 PDF 结构化解析和入库完成，paperId: {}, chunkCount: {}", paperId, chunks.size());
    }

    public EmbeddingEstimate estimateEmbeddingUsage(InputStream fileStream) throws IOException {
        return estimateEmbeddingUsage(fileStream, null);
    }

    public EmbeddingEstimate estimateEmbeddingUsage(InputStream fileStream, String originalFilename) throws IOException {
        logger.info("开始估算论文 Embedding Token");
        checkMemoryThreshold();

        byte[] pdfBytes = fileStream.readAllBytes();
        List<String> texts = splitEstimateText(extractEstimateText(pdfBytes));
        long estimatedTokens = usageQuotaService.estimateEmbeddingTokens(texts);
        return new EmbeddingEstimate(estimatedTokens, texts.size());
    }

    private String extractEstimateText(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return "";
        }
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            return new PDFTextStripper().getText(document);
        } catch (Exception ignored) {
            return new String(pdfBytes, StandardCharsets.UTF_8);
        }
    }

    private List<String> splitEstimateText(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        int effectiveChunkSize = Math.max(1, chunkSize);
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < normalized.length(); start += effectiveChunkSize) {
            int end = Math.min(start + effectiveChunkSize, normalized.length());
            chunks.add(normalized.substring(start, end));
        }
        return chunks;
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

    private void saveStructuredChunks(String paperId, List<PaperChunkCandidate> chunks,
                                      String userId, String orgTag, boolean isPublic) {
        for (PaperChunkCandidate chunk : chunks) {
            PaperTextChunk paperChunk = new PaperTextChunk();
            paperChunk.setPaperId(paperId);
            paperChunk.setChunkId(chunk.chunkId());
            paperChunk.setTextContent(chunk.text());
            paperChunk.setPageNumber(chunk.pageNumber());
            paperChunk.setAnchorText(chunk.anchorText());
            paperChunk.setElementType(chunk.elementType());
            paperChunk.setSectionTitle(chunk.sectionTitle());
            paperChunk.setSectionLevel(chunk.sectionLevel());
            paperChunk.setBboxJson(chunk.bboxJson());
            paperChunk.setParserName(chunk.parserName());
            paperChunk.setParserVersion(chunk.parserVersion());
            paperChunk.setRawProvenanceJson(chunk.rawProvenanceJson());
            paperChunk.setSourceKind(chunk.sourceKind());
            paperChunk.setTableId(chunk.tableId());
            paperChunk.setFigureId(chunk.figureId());
            paperChunk.setFormulaId(chunk.formulaId());
            paperChunk.setEvidenceRole(chunk.evidenceRole());
            paperChunk.setUserId(userId);
            paperChunk.setOrgTag(orgTag);
            paperChunk.setPublic(isPublic);
            paperTextChunkRepository.save(paperChunk);
        }
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

    public record EmbeddingEstimate(long estimatedTokens, int estimatedChunkCount) {
    }
}
