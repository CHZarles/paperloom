package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class QdrantReadingModelReindexService {

    private final PaperReadingModelRepository modelRepository;
    private final ReadingModelQdrantIndexService indexService;

    public QdrantReadingModelReindexService(PaperReadingModelRepository modelRepository,
                                            ReadingModelQdrantIndexService indexService) {
        this.modelRepository = modelRepository;
        this.indexService = indexService;
    }

    public ReindexResult reindexAllCurrent(String requesterId) {
        List<PaperReadingModel> models = modelRepository
                .findByIsCurrentTrueAndModelStatusOrderByPaperIdAsc(PaperReadingModelStatus.READING_MODEL_READY);
        List<String> indexedPaperIds = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        int indexedLocations = 0;
        int embeddingTokens = 0;
        for (PaperReadingModel model : models) {
            try {
                ReadingModelQdrantIndexService.IndexResult result = indexService.indexCurrentModel(
                        model.getPaperId(), requesterId);
                indexedPaperIds.add(model.getPaperId());
                indexedLocations += result.indexedLocationCount();
                embeddingTokens += result.actualEmbeddingTokens();
            } catch (Exception error) {
                failures.add(new Failure(
                        model.getPaperId(),
                        error.getClass().getSimpleName(),
                        error.getMessage() == null ? "Qdrant reindex failed" : error.getMessage()
                ));
            }
        }
        return new ReindexResult(models.size(), indexedPaperIds, indexedLocations, embeddingTokens, failures);
    }

    public record ReindexResult(int currentModelCount,
                                List<String> indexedPaperIds,
                                int indexedLocationCount,
                                int actualEmbeddingTokens,
                                List<Failure> failures) {
        public ReindexResult {
            indexedPaperIds = indexedPaperIds == null ? List.of() : List.copyOf(indexedPaperIds);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        public boolean completed() {
            return failures.isEmpty() && indexedPaperIds.size() == currentModelCount;
        }
    }

    public record Failure(String paperId, String errorType, String message) {
    }
}
