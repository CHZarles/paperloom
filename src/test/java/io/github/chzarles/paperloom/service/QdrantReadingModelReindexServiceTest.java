package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperRetrievalControl;
import io.github.chzarles.paperloom.repository.PaperRetrievalControlRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class QdrantReadingModelReindexServiceTest {

    @Test
    void zeroIndexedLocationsAreReportedAsBackfillFailure() {
        PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
        ReadingModelQdrantIndexService indexService = mock(ReadingModelQdrantIndexService.class);
        PaperRetrievalControlRepository controlRepository = mock(PaperRetrievalControlRepository.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        RetrievalIndexContractService contractService = mock(RetrievalIndexContractService.class);
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper-a");
        model.setModelVersion("rm-1");
        model.setCurrent(true);
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        when(modelRepository.findByIsCurrentTrueAndModelStatusOrderByPaperIdAsc(
                PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of(model));
        when(controlRepository.existsById(PaperRetrievalControl.FULL_REBUILD)).thenReturn(true);
        when(controlRepository.claimFullRebuild(anyString(), eq("admin-1"), eq(1), any())).thenReturn(1);
        when(modelRepository.claimFullRebuildPaper(eq("paper-a"), eq("rm-1"), anyString(), any())).thenReturn(1);
        when(indexService.buildIndexedLocations("paper-a", "rm-1")).thenReturn(List.of(
                new ReadingModelQdrantIndexService.IndexedLocation(
                        "paper-a", "rm-1", "location-a", 1, "searchable text", Map.of())
        ));
        when(indexService.rebuildClaimedCurrentModel(eq("paper-a"), eq("admin-1"), anyString())).thenReturn(
                new ReadingModelQdrantIndexService.IndexResult(0, 0, 0, "active-index-contract", "rm-1")
        );
        QdrantReadingModelReindexService service = new QdrantReadingModelReindexService(
                modelRepository, indexService, controlRepository, qdrantClient, contractService);

        QdrantReadingModelReindexService.ReindexResult result = service.reindexAllCurrent("admin-1");

        assertFalse(result.completed());
        assertEquals(List.of(), result.indexedPaperIds());
        assertEquals(1, result.failures().size());
        assertEquals("paper-a", result.failures().get(0).paperId());
    }
}
