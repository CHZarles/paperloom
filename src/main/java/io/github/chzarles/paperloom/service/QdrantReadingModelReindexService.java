package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperRetrievalControl;
import io.github.chzarles.paperloom.model.PaperRetrievalIndexStatus;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperRetrievalControlRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class QdrantReadingModelReindexService {

    private final PaperReadingModelRepository modelRepository;
    private final ReadingModelQdrantIndexService indexService;
    private final PaperRetrievalControlRepository controlRepository;
    private final QdrantClient qdrantClient;
    private final RetrievalIndexContractService contractService;

    public QdrantReadingModelReindexService(PaperReadingModelRepository modelRepository,
                                            ReadingModelQdrantIndexService indexService,
                                            PaperRetrievalControlRepository controlRepository,
                                            QdrantClient qdrantClient,
                                            RetrievalIndexContractService contractService) {
        this.modelRepository = modelRepository;
        this.indexService = indexService;
        this.controlRepository = controlRepository;
        this.qdrantClient = qdrantClient;
        this.contractService = contractService;
    }

    public ReindexResult reindexAllCurrent(String requesterId) {
        List<PaperReadingModel> models = modelRepository
                .findByIsCurrentTrueAndModelStatusOrderByPaperIdAsc(PaperReadingModelStatus.READING_MODEL_READY);
        ensureControlRow();
        String jobId = UUID.randomUUID().toString();
        if (controlRepository.claimFullRebuild(jobId, requesterId, models.size(), LocalDateTime.now()) != 1) {
            throw new IllegalStateException("FULL_REBUILD_ALREADY_RUNNING");
        }
        if (modelRepository.countByIsCurrentTrueAndRetrievalIndexStatusIn(List.of(
                PaperRetrievalIndexStatus.BUILDING,
                PaperRetrievalIndexStatus.REBUILDING)) > 0) {
            controlRepository.finishFullRebuild(
                    jobId, PaperRetrievalControl.FAILED, 0, 1, LocalDateTime.now(),
                    "PAPER_INDEX_OPERATION_RUNNING");
            throw new IllegalStateException("PAPER_INDEX_OPERATION_RUNNING");
        }

        List<String> indexedPaperIds = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        int indexedLocations = 0;
        int indexedTokens = 0;
        try {
            List<String> texts = models.stream()
                    .flatMap(model -> indexService.buildIndexedLocations(
                            model.getPaperId(), model.getModelVersion()).stream())
                    .map(ReadingModelQdrantIndexService.IndexedLocation::searchableText)
                    .toList();
            double averageDocumentLength = LexicalBm25Encoder.averageDocumentLength(texts);
            if (averageDocumentLength <= 0) {
                throw new IllegalStateException("Current corpus contains no lexical tokens");
            }
            contractService.activateContract(averageDocumentLength);
            for (PaperReadingModel model : models) {
                int claimed = modelRepository.claimFullRebuildPaper(
                        model.getPaperId(), model.getModelVersion(), jobId, LocalDateTime.now());
                if (claimed != 1) {
                    throw new IllegalStateException("Failed to claim paper for full rebuild: " + model.getPaperId());
                }
            }
            qdrantClient.deleteCollectionIfExists();
        } catch (RuntimeException error) {
            failClaimedModels(models, jobId, error);
            controlRepository.finishFullRebuild(
                    jobId, PaperRetrievalControl.FAILED, 0, models.size(), LocalDateTime.now(), message(error));
            throw error;
        }

        for (PaperReadingModel model : models) {
            try {
                ReadingModelQdrantIndexService.IndexResult result = indexService.rebuildClaimedCurrentModel(
                        model.getPaperId(), requesterId, jobId);
                if (result.indexedLocationCount() <= 0) {
                    throw new IllegalStateException(
                            "Current Reading Model contains no indexable locations");
                }
                indexedPaperIds.add(model.getPaperId());
                indexedLocations += result.indexedLocationCount();
                indexedTokens += result.indexedTokenCount();
            } catch (Exception error) {
                failures.add(new Failure(
                        model.getPaperId(),
                        error.getClass().getSimpleName(),
                        error.getMessage() == null ? "Qdrant reindex failed" : error.getMessage()
                ));
            }
            controlRepository.updateFullRebuildProgress(
                    jobId, indexedPaperIds.size(), failures.size());
        }
        String status = failures.isEmpty() ? PaperRetrievalControl.SUCCEEDED : PaperRetrievalControl.FAILED;
        controlRepository.finishFullRebuild(
                jobId,
                status,
                indexedPaperIds.size(),
                failures.size(),
                LocalDateTime.now(),
                failures.isEmpty() ? null : failures.get(0).message()
        );
        return new ReindexResult(jobId, models.size(), indexedPaperIds, indexedLocations, indexedTokens, failures);
    }

    public Optional<PaperRetrievalControl> fullRebuildStatus() {
        return controlRepository.findById(PaperRetrievalControl.FULL_REBUILD);
    }

    private void ensureControlRow() {
        if (controlRepository.existsById(PaperRetrievalControl.FULL_REBUILD)) {
            return;
        }
        PaperRetrievalControl control = new PaperRetrievalControl();
        control.setControlName(PaperRetrievalControl.FULL_REBUILD);
        control.setFullRebuildStatus(PaperRetrievalControl.IDLE);
        try {
            controlRepository.saveAndFlush(control);
        } catch (DataIntegrityViolationException ignored) {
            // Another replica created the singleton row.
        }
    }

    private void failClaimedModels(List<PaperReadingModel> models, String jobId, RuntimeException error) {
        for (PaperReadingModel model : models) {
            modelRepository.finishRetrievalIndexFailed(
                    model.getPaperId(),
                    model.getModelVersion(),
                    PaperRetrievalIndexStatus.REBUILDING.name(),
                    jobId,
                    error.getClass().getSimpleName(),
                    message(error)
            );
        }
    }

    private String message(Throwable error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) {
            value = error.getClass().getSimpleName();
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    public record ReindexResult(String jobId,
                                int currentModelCount,
                                List<String> indexedPaperIds,
                                int indexedLocationCount,
                                int indexedTokenCount,
                                List<Failure> failures) {
        public ReindexResult {
            jobId = jobId == null ? "" : jobId;
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
