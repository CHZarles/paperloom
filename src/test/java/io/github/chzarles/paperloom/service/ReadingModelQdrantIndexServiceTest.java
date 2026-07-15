package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.client.EmbeddingClient;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class ReadingModelQdrantIndexServiceTest {

    @Test
    void pointIdIsStableAndModelVersionSpecific() {
        String first = ReadingModelQdrantIndexService.stablePointId("paper-a", "rm-1", "location-a");
        String replay = ReadingModelQdrantIndexService.stablePointId("paper-a", "rm-1", "location-a");
        String newModel = ReadingModelQdrantIndexService.stablePointId("paper-a", "rm-2", "location-a");

        assertEquals("f185a54d-2862-807b-aadc-7664360dc7c7", first);
        assertEquals(first, replay);
        assertNotEquals(first, newModel);
    }

    @Test
    void sparseVectorIsDeterministicAndKeepsTermFrequency() {
        QdrantSparseVector first = ReadingModelQdrantIndexService.sparseVector("attention attention transformer");
        QdrantSparseVector replay = ReadingModelQdrantIndexService.sparseVector("attention attention transformer");

        assertEquals(first, replay);
        assertEquals(2, first.indices().size());
        assertFalse(first.values().stream().allMatch(value -> value == 1.0f));
    }

    @Test
    void verifiesNewGenerationBeforeDeletingStalePoints() {
        PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
        PaperReadingElementRepository elementRepository = mock(PaperReadingElementRepository.class);
        PaperLocationRepository locationRepository = mock(PaperLocationRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        ReadingModelQdrantIndexService service = spy(new ReadingModelQdrantIndexService(
                modelRepository, elementRepository, locationRepository, embeddingClient, qdrantClient));
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper-a");
        model.setModelVersion("rm-1");
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a")).thenReturn(Optional.of(model));
        doReturn(List.of(new ReadingModelQdrantIndexService.IndexedLocation(
                "paper-a", "rm-1", "location-a", 1, "searchable text",
                Map.of("paper_id", "paper-a", "model_version", "rm-1", "location_ref", "location-a")
        ))).when(service).buildIndexedLocations("paper-a", "rm-1");
        when(embeddingClient.embedWithUsage(anyList(), eq("7"), eq(EmbeddingClient.UsageType.UPLOAD)))
                .thenReturn(new EmbeddingClient.EmbeddingUsageResult(
                        List.of(new float[]{1.0f, 0.0f, 0.0f}), 3, "embedding-v1"));
        when(qdrantClient.countByPaperIdAndGeneration(eq("paper-a"), anyString())).thenReturn(1L);
        when(qdrantClient.countByPaperId("paper-a")).thenReturn(1L);

        service.indexCurrentModel("paper-a", "7");

        var ordered = inOrder(qdrantClient);
        ordered.verify(qdrantClient).ensureCollection(3);
        ordered.verify(qdrantClient).upsert(anyList());
        ordered.verify(qdrantClient).countByPaperIdAndGeneration(eq("paper-a"), anyString());
        ordered.verify(qdrantClient).deleteByPaperIdExceptGeneration(eq("paper-a"), anyString());
        ordered.verify(qdrantClient).countByPaperId("paper-a");
    }
}
