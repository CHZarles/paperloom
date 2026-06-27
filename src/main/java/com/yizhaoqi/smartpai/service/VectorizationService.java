package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperTextChunk;
import com.yizhaoqi.smartpai.entity.PaperChunkDocument;
import com.yizhaoqi.smartpai.entity.PaperSearchDocument;
import com.yizhaoqi.smartpai.entity.TextChunk;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.IntStream;

// 向量化服务类
@Service
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private PaperTextChunkRepository paperTextChunkRepository;

    @Autowired
    private PaperRepository paperRepository;

    /**
     * 执行向量化操作
     * @param paperId 论文标识
     * @param userId 上传用户ID
     * @param orgTag 组织标签
     * @param isPublic 是否公开
     */
    public void vectorize(String paperId, String userId, String orgTag, boolean isPublic) {
        vectorizeWithUsage(paperId, userId, orgTag, isPublic, userId);
    }

    public void vectorize(String paperId, String userId, String orgTag, boolean isPublic, String requesterId) {
        vectorizeWithUsage(paperId, userId, orgTag, isPublic, requesterId);
    }

    public VectorizationUsageResult vectorizeWithUsage(String paperId, String userId, String orgTag, boolean isPublic, String requesterId) {
        try {
            logger.info("开始向量化论文，paperId: {}, userId: {}, orgTag: {}, isPublic: {}", 
                       paperId, userId, orgTag, isPublic);
                       
            // 获取论文 chunk 内容
            List<TextChunk> chunks = fetchTextChunks(paperId);
            if (chunks == null || chunks.isEmpty()) {
                logger.warn("未找到论文 chunk 内容，paperId: {}", paperId);
                return new VectorizationUsageResult(0, 0, embeddingClient.currentModelVersion());
            }
            Paper paperMetadata = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId).orElse(null);

            // 提取文本内容
            List<String> texts = chunks.stream()
                    .map(TextChunk::getContent)
                    .toList();

            // 调用外部模型生成向量
            updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_EMBEDDING);
            EmbeddingClient.EmbeddingUsageResult embeddingResult = embeddingClient.embedWithUsage(
                    texts,
                    requesterId,
                    EmbeddingClient.UsageType.UPLOAD
            );
            List<float[]> vectors = embeddingResult.vectors();

            // 构建 Elasticsearch 论文 chunk 并存储
            List<PaperChunkDocument> esDocuments = IntStream.range(0, chunks.size())
                    .mapToObj(i -> {
                        TextChunk chunk = chunks.get(i);
                        PaperChunkDocument document = new PaperChunkDocument(
                                UUID.randomUUID().toString(),
                                paperId,
                                chunk.getChunkId(),
                                chunk.getContent(),
                                chunk.getPageNumber(),
                                chunk.getAnchorText(),
                                chunk.getElementType(),
                                chunk.getSectionTitle(),
                                chunk.getSectionLevel(),
                                chunk.getBboxJson(),
                                chunk.getParserName(),
                                chunk.getParserVersion(),
                                chunk.getSourceKind(),
                                chunk.getTableId(),
                                chunk.getFigureId(),
                                chunk.getFormulaId(),
                                chunk.getEvidenceRole(),
                                vectors.get(i),
                                embeddingResult.modelVersion(),
                                userId,
                                orgTag,
                                isPublic
                        );
                        document.setRetrievalTextContent(buildRetrievalTextContent(paperMetadata, chunk));
                        return document;
                    })
                    .toList();

            updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_INDEXING);
            elasticsearchService.bulkIndex(esDocuments); // 批量存储到 Elasticsearch
            if (paperMetadata != null) {
                elasticsearchService.bulkIndexPaperSearch(List.of(
                        PaperSearchDocument.from(paperMetadata, userId, orgTag, isPublic)
                ));
            }

            logger.info("论文向量化完成，paperId: {}", paperId);
            return new VectorizationUsageResult(
                    embeddingResult.totalTokens(),
                    chunks.size(),
                    embeddingResult.modelVersion()
            );
        } catch (Exception e) {
            logger.error("论文向量化失败，paperId: {}", paperId, e);
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                throw new RuntimeException("论文向量化失败", e);
            }
            throw new RuntimeException("论文向量化失败: " + message, e);
        }
    }
    

    /**
     * 获取论文 chunk 内容
     * @param paperId 论文标识
     * @return 分块内容列表
     */
    // 从数据库获取分块内容
    private List<TextChunk> fetchTextChunks(String paperId) {
        // 调用 Repository 查询数据
        List<PaperTextChunk> vectors = paperTextChunkRepository.findByPaperIdOrderByChunkIdAsc(paperId);

        // 转换为 TextChunk 列表
        return vectors.stream()
                .map(vector -> new TextChunk(
                        vector.getChunkId(),
                        vector.getTextContent(),
                        vector.getPageNumber(),
                        vector.getAnchorText(),
                        vector.getElementType(),
                        vector.getSectionTitle(),
                        vector.getSectionLevel(),
                        vector.getBboxJson(),
                        vector.getParserName(),
                        vector.getParserVersion(),
                        vector.getSourceKind(),
                        vector.getTableId(),
                        vector.getFigureId(),
                        vector.getFormulaId(),
                        vector.getEvidenceRole()
                ))
                .toList();
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

    static String buildRetrievalTextContent(Paper paper, TextChunk chunk) {
        List<String> parts = new ArrayList<>();
        if (paper != null) {
            appendRetrievalPart(parts, "title", paper.getPaperTitle());
            appendRetrievalPart(parts, "filename", paper.getOriginalFilename());
            appendRetrievalPart(parts, "abstract", paper.getAbstractText());
            appendRetrievalPart(parts, "authors", paper.getAuthors());
            appendRetrievalPart(parts, "venue", paper.getVenue());
            appendRetrievalPart(parts, "year", paper.getPublicationYear() == null ? null : paper.getPublicationYear().toString());
        }
        if (chunk != null) {
            appendRetrievalPart(parts, "section", chunk.getSectionTitle());
            appendRetrievalPart(parts, "element", chunk.getElementType());
            appendRetrievalPart(parts, "source", chunk.getSourceKind());
            appendRetrievalPart(parts, "evidence", chunk.getEvidenceRole());
            appendRetrievalPart(parts, "text", chunk.getContent());
        }
        return String.join("\n", parts);
    }

    private static void appendRetrievalPart(List<String> parts, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        parts.add(label + ": " + value.trim());
    }

    public record VectorizationUsageResult(int actualEmbeddingTokens, int actualChunkCount, String modelVersion) {
    }
}
