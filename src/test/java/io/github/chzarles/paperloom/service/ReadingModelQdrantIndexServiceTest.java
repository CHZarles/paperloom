package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.client.EmbeddingClient;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperRetrievalIndexStatus;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadingModelQdrantIndexServiceTest {

    @Test
    void qdrantPayloadKeepsHashesAndRoutingMetadataButNotPrivateContent() {
        PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
        PaperReadingElementRepository elementRepository = mock(PaperReadingElementRepository.class);
        PaperLocationRepository locationRepository = mock(PaperLocationRepository.class);
        ReadingModelQdrantIndexService service = new ReadingModelQdrantIndexService(
                modelRepository,
                elementRepository,
                locationRepository,
                mock(EmbeddingClient.class),
                mock(QdrantClient.class)
        );
        PaperReadingElement element = new PaperReadingElement();
        element.setPaperId("paper-a");
        element.setModelVersion("rm-1");
        element.setReadingElementId("element-a");
        element.setElementType("paragraph");
        element.setPageNumber(1);
        element.setLocationRef("location-a");
        element.setSearchableText("Private paper content used only to build vectors.");
        PaperLocation location = new PaperLocation();
        location.setPaperId("paper-a");
        location.setModelVersion("rm-1");
        location.setLocationRef("location-a");
        location.setLocationType(PaperLocationType.SECTION);
        location.setPageNumber(1);
        when(elementRepository.findByPaperIdAndModelVersionOrderByPageNumberAscReadingOrderAscIdAsc(
                "paper-a", "rm-1")).thenReturn(List.of(element));
        when(locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(
                "paper-a", "rm-1")).thenReturn(List.of(location));

        ReadingModelQdrantIndexService.IndexedLocation indexed =
                service.buildIndexedLocations("paper-a", "rm-1").get(0);

        assertEquals("Private paper content used only to build vectors.", indexed.searchableText());
        assertFalse(indexed.payload().containsKey("searchable_text"));
        assertNotNull(indexed.payload().get("text_hash"));
    }

    @Test
    void staleGenerationCleanupFailureDoesNotRollBackActivatedIndex() {
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
        model.setRetrievalIndexGeneration("generation-old");
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a")).thenReturn(Optional.of(model));
        doReturn(List.of(new ReadingModelQdrantIndexService.IndexedLocation(
                "paper-a", "rm-1", "location-a", 1, "searchable text",
                Map.of("paper_id", "paper-a", "model_version", "rm-1", "location_ref", "location-a")
        ))).when(service).buildIndexedLocations("paper-a", "rm-1");
        when(embeddingClient.embedWithUsage(anyList(), eq("7"), eq(EmbeddingClient.UsageType.UPLOAD)))
                .thenReturn(new EmbeddingClient.EmbeddingUsageResult(
                        List.of(new float[]{1.0f, 0.0f, 0.0f}), 3, "embedding-v1"));
        when(qdrantClient.countByPaperIdAndGeneration(eq("paper-a"), anyString())).thenReturn(1L);
        when(qdrantClient.indexVersion()).thenReturn("test-index");
        when(modelRepository.activateRetrievalIndex(
                eq("paper-a"), eq("rm-1"), eq("generation-old"), anyString(),
                eq("test-index|embedding-v1|3"), eq(1), any()
        )).thenReturn(1);
        doThrow(new IllegalStateException("cleanup failed"))
                .when(qdrantClient).deleteByPaperIdAndGeneration("paper-a", "generation-old");

        ReadingModelQdrantIndexService.IndexResult result = service.indexCurrentModel("paper-a", "7");

        assertEquals(1, result.indexedLocationCount());
        assertEquals(PaperRetrievalIndexStatus.READY, model.getRetrievalIndexStatus());
        assertNotNull(model.getRetrievalIndexGeneration());
    }

    @Test
    void losingGenerationActivationDeletesOnlyItsOwnUnactivatedPoints() {
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
        model.setRetrievalIndexStatus(PaperRetrievalIndexStatus.READY);
        model.setRetrievalIndexGeneration("generation-winner");
        model.setRetrievalEmbeddingContract("test-index|embedding-v1|3");
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a")).thenReturn(Optional.of(model));
        doReturn(List.of(new ReadingModelQdrantIndexService.IndexedLocation(
                "paper-a", "rm-1", "location-a", 1, "searchable text",
                Map.of("paper_id", "paper-a", "model_version", "rm-1", "location_ref", "location-a")
        ))).when(service).buildIndexedLocations("paper-a", "rm-1");
        when(embeddingClient.embedWithUsage(anyList(), eq("7"), eq(EmbeddingClient.UsageType.UPLOAD)))
                .thenReturn(new EmbeddingClient.EmbeddingUsageResult(
                        List.of(new float[]{1.0f, 0.0f, 0.0f}), 3, "embedding-v1"));
        when(qdrantClient.countByPaperIdAndGeneration(eq("paper-a"), anyString())).thenReturn(1L);
        when(qdrantClient.indexVersion()).thenReturn("test-index");
        when(modelRepository.activateRetrievalIndex(
                eq("paper-a"), eq("rm-1"), eq("generation-winner"), anyString(),
                eq("test-index|embedding-v1|3"), eq(1), any()
        )).thenReturn(0);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.indexCurrentModel("paper-a", "7")
        );

        assertTrue(error.getMessage().contains("changed while Qdrant indexing was in progress"));
        verify(qdrantClient).deleteByPaperIdAndGeneration(eq("paper-a"), anyString());
        assertEquals("generation-winner", model.getRetrievalIndexGeneration());
        assertEquals("test-index|embedding-v1|3", model.getRetrievalEmbeddingContract());
    }

    @Test
    void pointIdIsStableWithinGenerationAndDistinctAcrossGenerations() {
        String first = ReadingModelQdrantIndexService.pointId(
                "paper-a", "rm-1", "location-a", "generation-1");
        String replay = ReadingModelQdrantIndexService.pointId(
                "paper-a", "rm-1", "location-a", "generation-1");
        String newGeneration = ReadingModelQdrantIndexService.pointId(
                "paper-a", "rm-1", "location-a", "generation-2");
        String newModel = ReadingModelQdrantIndexService.pointId(
                "paper-a", "rm-2", "location-a", "generation-1");

        assertEquals(first, replay);
        assertNotEquals(first, newGeneration);
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
        model.setRetrievalIndexGeneration("generation-old");
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a")).thenReturn(Optional.of(model));
        doReturn(List.of(new ReadingModelQdrantIndexService.IndexedLocation(
                "paper-a", "rm-1", "location-a", 1, "searchable text",
                Map.of("paper_id", "paper-a", "model_version", "rm-1", "location_ref", "location-a")
        ))).when(service).buildIndexedLocations("paper-a", "rm-1");
        when(embeddingClient.embedWithUsage(anyList(), eq("7"), eq(EmbeddingClient.UsageType.UPLOAD)))
                .thenReturn(new EmbeddingClient.EmbeddingUsageResult(
                        List.of(new float[]{1.0f, 0.0f, 0.0f}), 3, "embedding-v1"));
        when(qdrantClient.countByPaperIdAndGeneration(eq("paper-a"), anyString()))
                .thenAnswer(invocation -> "generation-old".equals(invocation.getArgument(1)) ? 0L : 1L);
        when(qdrantClient.indexVersion()).thenReturn("test-index");
        when(modelRepository.activateRetrievalIndex(
                eq("paper-a"), eq("rm-1"), eq("generation-old"), anyString(),
                eq("test-index|embedding-v1|3"), eq(1), any()
        )).thenReturn(1);

        service.indexCurrentModel("paper-a", "7");

        var ordered = inOrder(qdrantClient, modelRepository);
        ordered.verify(qdrantClient).ensureCollection(3);
        ordered.verify(qdrantClient).upsert(anyList());
        ordered.verify(qdrantClient).countByPaperIdAndGeneration(eq("paper-a"), anyString());
        ordered.verify(modelRepository).activateRetrievalIndex(
                eq("paper-a"), eq("rm-1"), eq("generation-old"), anyString(),
                eq("test-index|embedding-v1|3"), eq(1), any());
        ordered.verify(qdrantClient).deleteByPaperIdAndGeneration("paper-a", "generation-old");
        ordered.verify(qdrantClient).countByPaperIdAndGeneration("paper-a", "generation-old");
        assertEquals(PaperRetrievalIndexStatus.READY, model.getRetrievalIndexStatus());
        assertNotNull(model.getRetrievalIndexGeneration());
        assertEquals("test-index|embedding-v1|3", model.getRetrievalEmbeddingContract());
        assertEquals(1, model.getRetrievalIndexedLocationCount());
        assertNotNull(model.getRetrievalIndexedAt());
    }
}
