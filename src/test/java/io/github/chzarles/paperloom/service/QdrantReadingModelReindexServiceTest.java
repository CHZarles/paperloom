package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QdrantReadingModelReindexServiceTest {

    @Test
    void zeroIndexedLocationsAreReportedAsBackfillFailure() {
        PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
        ReadingModelQdrantIndexService indexService = mock(ReadingModelQdrantIndexService.class);
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper-a");
        model.setModelVersion("rm-1");
        model.setCurrent(true);
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        when(modelRepository.findByIsCurrentTrueAndModelStatusOrderByPaperIdAsc(
                PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of(model));
        when(indexService.indexCurrentModel("paper-a", "admin-1")).thenReturn(
                new ReadingModelQdrantIndexService.IndexResult(0, 0, "embedding-v1", "rm-1")
        );
        QdrantReadingModelReindexService service = new QdrantReadingModelReindexService(
                modelRepository, indexService);

        QdrantReadingModelReindexService.ReindexResult result = service.reindexAllCurrent("admin-1");

        assertFalse(result.completed());
        assertEquals(List.of(), result.indexedPaperIds());
        assertEquals(1, result.failures().size());
        assertEquals("paper-a", result.failures().get(0).paperId());
    }
}
