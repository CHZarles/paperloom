package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperPassage;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperRetrievalIndexStatus;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperPassageRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperRetrievalControlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadingModelQdrantIndexServiceTest {

    private PaperReadingModelRepository modelRepository;
    private PaperReadingElementRepository elementRepository;
    private PaperLocationRepository locationRepository;
    private PaperPassageRepository passageRepository;
    private QdrantClient qdrantClient;
    private RetrievalIndexContractService contractService;
    private ReadingModelQdrantIndexService service;

    @BeforeEach
    void setUp() {
        modelRepository = mock(PaperReadingModelRepository.class);
        elementRepository = mock(PaperReadingElementRepository.class);
        locationRepository = mock(PaperLocationRepository.class);
        passageRepository = mock(PaperPassageRepository.class);
        qdrantClient = mock(QdrantClient.class);
        contractService = mock(RetrievalIndexContractService.class);
        when(contractService.ensureActiveContract(anyDouble())).thenReturn("active-index-contract");
        when(contractService.activeAverageDocumentLength()).thenReturn(2.0);
        service = spy(new ReadingModelQdrantIndexService(
                modelRepository,
                elementRepository,
                locationRepository,
                passageRepository,
                qdrantClient,
                mock(PaperRetrievalControlRepository.class),
                contractService,
                mock(io.github.chzarles.paperloom.service.embedding.EmbeddingProviderFactory.class)
        ));
    }

    @Test
    void initialBuildDeletesBeforeWritingAndFinalizesOnlyAfterExactCount() {
        PaperReadingModel model = model(PaperRetrievalIndexStatus.PENDING);
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a")).thenReturn(Optional.of(model));
        when(modelRepository.claimInitialIndex(eq("paper-a"), eq("rm-1"), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);
        doReturn(List.of(location())).when(service).buildIndexedLocations("paper-a", "rm-1");
        when(qdrantClient.countByPaperIdAndModelVersion("paper-a", "rm-1")).thenReturn(1L);
        when(modelRepository.finishRetrievalIndexReady(
                eq("paper-a"), eq("rm-1"), eq("BUILDING"), anyString(),
                eq("active-index-contract"), eq(1), any(LocalDateTime.class))).thenReturn(1);

        ReadingModelQdrantIndexService.IndexResult result = service.indexCurrentModel("paper-a", "user-1");

        assertEquals(1, result.indexedLocationCount());
        InOrder order = inOrder(qdrantClient);
        order.verify(qdrantClient).deleteByPaperId("paper-a");
        order.verify(qdrantClient).ensureCollection();
        order.verify(qdrantClient).upsert(any());
    }

    @Test
    void failedBuildLeavesDurableFailedState() {
        PaperReadingModel model = model(PaperRetrievalIndexStatus.PENDING);
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a")).thenReturn(Optional.of(model));
        when(modelRepository.claimInitialIndex(eq("paper-a"), eq("rm-1"), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);
        doReturn(List.of(location())).when(service).buildIndexedLocations("paper-a", "rm-1");
        org.mockito.Mockito.doThrow(new IllegalStateException("qdrant down"))
                .when(qdrantClient).upsert(any());

        assertThrows(IllegalStateException.class, () -> service.indexCurrentModel("paper-a", "user-1"));

        verify(modelRepository).finishRetrievalIndexFailed(
                eq("paper-a"), eq("rm-1"), eq("BUILDING"), anyString(),
                eq("IllegalStateException"), eq("qdrant down"));
    }

    @Test
    void pointIdentityDoesNotDependOnBuildAttempt() {
        String first = ReadingModelQdrantIndexService.pointId("paper-a", "rm-1", "location-a");
        String second = ReadingModelQdrantIndexService.pointId("paper-a", "rm-1", "location-a");
        String otherModel = ReadingModelQdrantIndexService.pointId("paper-a", "rm-2", "location-a");

        assertEquals(first, second);
        assertNotEquals(first, otherModel);
    }

    @Test
    void indexesOnlyEvidenceLocationsFromCanonicalPassageContent() {
        PaperPassage passage = new PaperPassage();
        passage.setPassageRef("passage-ref");
        passage.setContentText("canonical passage text");
        passage.setIndexText("Abstract\n\ncanonical passage text");
        passage.setContentHash("content-hash");
        passage.setSourceSpanJson("{\"spans\":[]}");
        passage.setParentSectionRef("section-ref");
        passage.setDocumentOrdinal(1);
        passage.setSectionOrdinal(1);
        passage.setEstimatedTokenCount(5);
        PaperLocation pageLocation = location("page-ref", PaperLocationType.PAGE, "page-1", "Abstract");
        PaperLocation sectionLocation = location(
                "section-ref", PaperLocationType.SECTION, "section-1", "Abstract");
        PaperLocation passageLocation = location(
                "passage-ref", PaperLocationType.PASSAGE, "passage-ref", "Abstract");

        when(elementRepository.findByPaperIdAndModelVersionOrderByPageNumberAscReadingOrderAscIdAsc(
                "paper-a", "rm-1")).thenReturn(List.of());
        when(locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(
                "paper-a", "rm-1")).thenReturn(List.of(pageLocation, sectionLocation, passageLocation));
        when(passageRepository.findByPaperIdAndModelVersionOrderByDocumentOrdinalAsc(
                "paper-a", "rm-1")).thenReturn(List.of(passage));

        List<ReadingModelQdrantIndexService.IndexedLocation> indexed =
                service.buildIndexedLocations("paper-a", "rm-1");

        assertEquals(1, indexed.size());
        assertEquals("Abstract\n\ncanonical passage text", indexed.get(0).searchableText());
        assertEquals("canonical passage text", indexed.get(0).payload().get("content_text"));
        assertEquals("content-hash", indexed.get(0).payload().get("content_hash"));
        assertEquals(List.of("page-ref"), indexed.get(0).payload().get("page_location_refs"));
    }

    private PaperReadingModel model(PaperRetrievalIndexStatus status) {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper-a");
        model.setModelVersion("rm-1");
        model.setCurrent(true);
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setRetrievalIndexStatus(status);
        return model;
    }

    private ReadingModelQdrantIndexService.IndexedLocation location() {
        return new ReadingModelQdrantIndexService.IndexedLocation(
                "paper-a",
                "rm-1",
                "location-a",
                1,
                "searchable text",
                Map.of("paper_id", "paper-a", "model_version", "rm-1", "location_ref", "location-a")
        );
    }

    private PaperLocation location(String ref,
                                   PaperLocationType type,
                                   String sourceObjectId,
                                   String sectionTitle) {
        PaperLocation location = new PaperLocation();
        location.setPaperId("paper-a");
        location.setModelVersion("rm-1");
        location.setLocationRef(ref);
        location.setLocationType(type);
        location.setPageNumber(1);
        location.setSourceObjectId(sourceObjectId);
        location.setSectionTitle(sectionTitle);
        return location;
    }
}
