package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.entity.TextChunk;
import io.github.chzarles.paperloom.repository.PaperRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

// 向量化服务类
@Service
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    @Autowired
    private ReadingModelQdrantIndexService qdrantIndexService;

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
                       
            // Current Reading Model 是唯一索引来源；Qdrant 只保存可重建的候选投影。
            updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_EMBEDDING);
            ReadingModelQdrantIndexService.IndexResult result = qdrantIndexService.indexCurrentModel(
                    paperId,
                    requesterId,
                    () -> updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_INDEXING)
            );
            if (result.indexedLocationCount() == 0) {
                throw new IllegalStateException("Current Reading Model contains no indexable locations");
            }

            logger.info("论文向量化完成，paperId: {}", paperId);
            return new VectorizationUsageResult(
                    result.actualEmbeddingTokens(),
                    result.indexedLocationCount(),
                    result.embeddingModelVersion()
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
