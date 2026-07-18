package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.repository.PaperRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RetrievalIndexingService {

    private static final Logger logger = LoggerFactory.getLogger(RetrievalIndexingService.class);

    private final ReadingModelQdrantIndexService qdrantIndexService;
    private final PaperRepository paperRepository;

    public RetrievalIndexingService(ReadingModelQdrantIndexService qdrantIndexService,
                                    PaperRepository paperRepository) {
        this.qdrantIndexService = qdrantIndexService;
        this.paperRepository = paperRepository;
    }

    public void index(String paperId, String requesterId) {
        indexWithMetrics(paperId, requesterId);
    }

    public IndexingResult indexWithMetrics(String paperId, String requesterId) {
        try {
            logger.info("开始构建论文词法检索索引，paperId={}, requesterId={}", paperId, requesterId);
            updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_INDEXING);
            ReadingModelQdrantIndexService.IndexResult result = qdrantIndexService.indexCurrentModel(
                    paperId,
                    requesterId,
                    () -> { }
            );
            if (result.indexedLocationCount() <= 0) {
                throw new IllegalStateException("Current Reading Model contains no indexable locations");
            }
            logger.info("论文词法检索索引完成，paperId={}, locations={}, tokens={}",
                    paperId, result.indexedLocationCount(), result.indexedTokenCount());
            return new IndexingResult(
                    result.indexedTokenCount(),
                    result.indexedLocationCount(),
                    result.retrievalIndexContract()
            );
        } catch (Exception error) {
            logger.error("论文词法检索索引失败，paperId={}", paperId, error);
            String message = error.getMessage();
            throw new RuntimeException(
                    message == null || message.isBlank()
                            ? "论文词法检索索引失败"
                            : "论文词法检索索引失败: " + message,
                    error
            );
        }
    }

    private void updatePipelineStatus(String paperId, String status) {
        if (paperId == null || paperId.isBlank()) {
            return;
        }
        paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId).ifPresent(paper -> {
            paper.setVectorizationStatus(status);
            paper.setVectorizationErrorMessage(null);
            paperRepository.save(paper);
        });
    }

    public record IndexingResult(
            int indexedTokenCount,
            int indexedLocationCount,
            String retrievalIndexContract
    ) {
    }
}
