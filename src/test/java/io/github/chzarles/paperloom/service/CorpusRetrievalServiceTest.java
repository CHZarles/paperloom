package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.client.EmbeddingClient;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperRetrievalIndexStatus;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CorpusRetrievalServiceTest {

    @Test
    void explicitSmallComparisonRecallsCandidatesFromEveryRequestedPaper() {
        PaperService paperService = mock(PaperService.class);
        PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
        PaperLocationRepository locationRepository = mock(PaperLocationRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        CanonicalReadingLocationService readService = mock(CanonicalReadingLocationService.class);
        CorpusRetrievalService service = new CorpusRetrievalService(
                paperService, modelRepository, locationRepository, embeddingClient, qdrantClient, readService);
        List<String> scope = List.of("paper-a", "paper-b");
        Map<String, String> generations = Map.of(
                "paper-a", "generation-paper-a",
                "paper-b", "generation-paper-b"
        );
        Map<String, Object> globalFilter = Map.of("branch", "global");
        Map<String, Object> paperAFilter = Map.of("branch", "paper-a");
        Map<String, Object> paperBFilter = Map.of("branch", "paper-b");
        when(paperService.getAccessiblePapersByIds("7", scope)).thenReturn(scope.stream().map(CorpusRetrievalServiceTest::paper).toList());
        when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                scope, PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of(readyModel("paper-a", "rm-a"), readyModel("paper-b", "rm-b")));
        when(embeddingClient.embedWithUsage(
                anyList(), eq("7"), eq(EmbeddingClient.UsageType.QUERY), any(Duration.class)))
                .thenReturn(new EmbeddingClient.EmbeddingUsageResult(
                        List.of(new float[]{1.0f, 0.0f, 0.0f}), 1, "embedding-v1"));
        when(qdrantClient.indexVersion()).thenReturn("test-index");
        when(qdrantClient.filter(generations, null, null)).thenReturn(globalFilter);
        when(qdrantClient.filter(Map.of("paper-a", "generation-paper-a"), null, null)).thenReturn(paperAFilter);
        when(qdrantClient.filter(Map.of("paper-b", "generation-paper-b"), null, null)).thenReturn(paperBFilter);
        when(qdrantClient.searchDense(any(float[].class), eq(globalFilter), anyInt()))
                .thenReturn(List.of(hitFor("paper-a", "rm-a", "location-a")));
        when(qdrantClient.searchDense(any(float[].class), eq(paperAFilter), anyInt()))
                .thenReturn(List.of(hitFor("paper-a", "rm-a", "location-a")));
        when(qdrantClient.searchDense(any(float[].class), eq(paperBFilter), anyInt()))
                .thenReturn(List.of(hitFor("paper-b", "rm-b", "location-b")));
        when(qdrantClient.searchSparse(any(), any(), anyInt())).thenReturn(List.of());
        when(locationRepository.findByLocationRefIn(anyList())).thenReturn(List.of(
                locationFor("paper-a", "rm-a", "location-a"),
                locationFor("paper-b", "rm-b", "location-b")
        ));
        when(readService.read(anyList(), anyList())).thenReturn(new CanonicalReadingLocationService.ReadBatch(
                List.of(
                        canonicalFor("paper-a", "rm-a", "location-a"),
                        canonicalFor("paper-b", "rm-b", "location-b")
                ),
                List.of()
        ));

        CorpusRetrievalService.LocationSearchResult result = service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L, scope, scope, "query", "", List.of(), null, null, 2));

        assertEquals(Set.of("location-a", "location-b"),
                result.locations().stream().map(CorpusRetrievalService.LocationCandidate::locationRef)
                        .collect(Collectors.toSet()));
        verify(qdrantClient).searchDense(any(float[].class), eq(globalFilter), anyInt());
        verify(qdrantClient).searchDense(any(float[].class), eq(paperAFilter), anyInt());
        verify(qdrantClient).searchDense(any(float[].class), eq(paperBFilter), anyInt());
    }

    @Test
    void multiPaperSearchUsesGlobalRelevanceWhenTopKCannotCoverEveryPaper() {
        PaperService paperService = mock(PaperService.class);
        PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
        PaperLocationRepository locationRepository = mock(PaperLocationRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        CanonicalReadingLocationService readService = mock(CanonicalReadingLocationService.class);
        CorpusRetrievalService service = new CorpusRetrievalService(
                paperService, modelRepository, locationRepository, embeddingClient, qdrantClient, readService);
        List<String> scope = List.of("paper-a", "paper-b", "paper-c");
        Map<String, String> generations = Map.of(
                "paper-a", "generation-paper-a",
                "paper-b", "generation-paper-b",
                "paper-c", "generation-paper-c"
        );
        Map<String, Object> globalFilter = Map.of("branch", "global");
        when(paperService.getAccessiblePapersByIds("7", scope)).thenReturn(scope.stream().map(CorpusRetrievalServiceTest::paper).toList());
        when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                scope, PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of(
                readyModel("paper-a", "rm-a"),
                readyModel("paper-b", "rm-b"),
                readyModel("paper-c", "rm-c")
        ));
        when(embeddingClient.embedWithUsage(
                anyList(), eq("7"), eq(EmbeddingClient.UsageType.QUERY), any(Duration.class)))
                .thenReturn(new EmbeddingClient.EmbeddingUsageResult(
                        List.of(new float[]{1.0f, 0.0f, 0.0f}), 1, "embedding-v1"));
        when(qdrantClient.indexVersion()).thenReturn("test-index");
        when(qdrantClient.filter(generations, null, null)).thenReturn(globalFilter);
        when(qdrantClient.searchDense(any(float[].class), eq(globalFilter), anyInt())).thenReturn(List.of(
                hitFor("paper-c", "rm-c", "location-c"),
                hitFor("paper-b", "rm-b", "location-b"),
                hitFor("paper-a", "rm-a", "location-a")
        ));
        when(qdrantClient.searchSparse(any(), any(), anyInt())).thenReturn(List.of());
        when(locationRepository.findByLocationRefIn(anyList())).thenReturn(List.of(
                locationFor("paper-a", "rm-a", "location-a"),
                locationFor("paper-b", "rm-b", "location-b"),
                locationFor("paper-c", "rm-c", "location-c")
        ));
        when(readService.read(anyList(), anyList())).thenReturn(new CanonicalReadingLocationService.ReadBatch(
                List.of(
                        canonicalFor("paper-a", "rm-a", "location-a"),
                        canonicalFor("paper-b", "rm-b", "location-b"),
                        canonicalFor("paper-c", "rm-c", "location-c")
                ),
                List.of()
        ));

        CorpusRetrievalService.LocationSearchResult result = service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L, scope, scope, "query", "", List.of(), null, null, 2));

        assertEquals(List.of("location-c", "location-b"),
                result.locations().stream().map(CorpusRetrievalService.LocationCandidate::locationRef).toList());
        verify(qdrantClient).filter(generations, null, null);
        verify(qdrantClient).searchDense(any(float[].class), eq(globalFilter), anyInt());
    }

    @Test
    void locationSearchRejectsEmbeddingContractMismatchBeforeQdrantSearch() {
        PaperService paperService = mock(PaperService.class);
        PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
        PaperLocationRepository locationRepository = mock(PaperLocationRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        CanonicalReadingLocationService readService = mock(CanonicalReadingLocationService.class);
        CorpusRetrievalService service = new CorpusRetrievalService(
                paperService, modelRepository, locationRepository, embeddingClient, qdrantClient, readService);
        List<String> scope = List.of("paper-a");
        PaperReadingModel model = readyModel("paper-a", "rm-1");
        model.setRetrievalEmbeddingContract("test-index|embedding-v1|3");
        when(paperService.getAccessiblePapersByIds("7", scope)).thenReturn(List.of(paper("paper-a")));
        when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                scope, PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of(model));
        when(embeddingClient.embedWithUsage(
                eq(List.of("query")),
                eq("7"),
                eq(EmbeddingClient.UsageType.QUERY),
                any(Duration.class)
        )).thenReturn(new EmbeddingClient.EmbeddingUsageResult(
                List.of(new float[]{1.0f, 0.0f, 0.0f}), 1, "embedding-v2"));
        when(qdrantClient.indexVersion()).thenReturn("test-index");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L, scope, scope, "query", "", List.of(), null, null, 8)));

        assertTrue(error.getMessage().contains("embedding contract"));
        verify(qdrantClient, never()).verifyCollection(anyInt());
        verify(qdrantClient, never()).searchDense(any(), any(), anyInt());
    }

    @Test
    void locationSearchRejectsMissingActiveIndexBeforeEmbeddingOrQdrant() {
        PaperService paperService = mock(PaperService.class);
        PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
        PaperLocationRepository locationRepository = mock(PaperLocationRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        CanonicalReadingLocationService readService = mock(CanonicalReadingLocationService.class);
        CorpusRetrievalService service = new CorpusRetrievalService(
                paperService, modelRepository, locationRepository, embeddingClient, qdrantClient, readService);
        List<String> scope = List.of("paper-a");
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper-a");
        model.setModelVersion("rm-1");
        model.setCurrent(true);
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        when(paperService.getAccessiblePapersByIds("7", scope)).thenReturn(List.of(paper("paper-a")));
        when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                scope, PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of(model));

        assertThrows(IllegalStateException.class, () -> service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L, scope, scope, "query", "", List.of(), null, null, 8)));

        verifyNoInteractions(embeddingClient, qdrantClient, locationRepository, readService);
    }

    @Test
    void elementTypesBoostMatchingCandidatesWithoutFilteringOtherTypes() {
        PaperService paperService = mock(PaperService.class);
        PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
        PaperLocationRepository locationRepository = mock(PaperLocationRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        CanonicalReadingLocationService readService = mock(CanonicalReadingLocationService.class);
        CorpusRetrievalService service = new CorpusRetrievalService(
                paperService, modelRepository, locationRepository, embeddingClient, qdrantClient, readService);
        List<String> scope = List.of("paper-a", "paper-b");
        List<String> requested = List.of("paper-a");
        when(paperService.getAccessiblePapersByIds("7", requested)).thenReturn(List.of(paper("paper-a")));
        when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                requested, PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of(readyModel("paper-a", "rm-1")));
        when(embeddingClient.embedWithUsage(
                anyList(), eq("7"), eq(EmbeddingClient.UsageType.QUERY), any(Duration.class)))
                .thenReturn(new EmbeddingClient.EmbeddingUsageResult(
                        List.of(new float[]{1.0f, 0.0f, 0.0f}), 1, "embedding-v1"));
        when(qdrantClient.filter(Map.of("paper-a", "generation-paper-a"), null, null)).thenReturn(Map.of());
        when(qdrantClient.searchDense(any(float[].class), any(), anyInt())).thenReturn(List.of(
                hit("paragraph-ref", "paragraph"),
                hit("table-ref", "table")
        ));
        when(qdrantClient.searchSparse(any(), any(), anyInt())).thenReturn(List.of());
        when(qdrantClient.indexVersion()).thenReturn("test-index");
        when(locationRepository.findByLocationRefIn(anyList())).thenReturn(List.of(
                location("paragraph-ref"),
                location("table-ref")
        ));
        when(readService.read(anyList(), anyList())).thenReturn(new CanonicalReadingLocationService.ReadBatch(
                List.of(
                        canonical("paragraph-ref", "paragraph"),
                        canonical("table-ref", "table")
                ),
                List.of()
        ));

        CorpusRetrievalService.LocationSearchResult result = service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L, scope, requested, "query", "", List.of("table"), null, null, 1));

        assertEquals(1, result.returnedCount());
        assertEquals("table-ref", result.locations().get(0).locationRef());
        assertEquals("table", result.locations().get(0).elementType());
    }

    @Test
    void paperSearchAuthorizesLockedScopeWithBatchQueries() {
        PaperService paperService = mock(PaperService.class);
        PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
        PaperLocationRepository locationRepository = mock(PaperLocationRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        CanonicalReadingLocationService readService = mock(CanonicalReadingLocationService.class);
        CorpusRetrievalService service = new CorpusRetrievalService(
                paperService, modelRepository, locationRepository, embeddingClient, qdrantClient, readService);
        Paper paperA = paper("paper-a");
        Paper paperB = paper("paper-b");
        PaperReadingModel modelA = readyModel("paper-a", "rm-a");
        PaperReadingModel modelB = readyModel("paper-b", "rm-b");
        List<String> scope = List.of("paper-a", "paper-b");
        when(paperService.getAccessiblePapersByIds("7", scope)).thenReturn(List.of(paperA, paperB));
        when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                scope, PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of(modelA, modelB));

        CorpusRetrievalService.PaperSearchResult result = service.searchPapers(
                new CorpusRetrievalService.PaperSearchQuery(
                        7L, scope, "", List.of(), List.of(), List.of(), null, null, 0, 20));

        assertEquals(2, result.returnedCount());
        verify(paperService).getAccessiblePapersByIds("7", scope);
        verify(paperService, never()).getAccessiblePapers("7", null);
        verify(modelRepository).findByPaperIdInAndIsCurrentTrueAndModelStatus(
                scope, PaperReadingModelStatus.READING_MODEL_READY);
        verify(modelRepository, never()).findFirstByPaperIdAndIsCurrentTrue(any());
    }

    @Test
    void locationSearchVerifiesExistingIndexWithoutProvisioning() {
        PaperService paperService = mock(PaperService.class);
        PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
        PaperLocationRepository locationRepository = mock(PaperLocationRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        CanonicalReadingLocationService readService = mock(CanonicalReadingLocationService.class);
        CorpusRetrievalService service = new CorpusRetrievalService(
                paperService, modelRepository, locationRepository, embeddingClient, qdrantClient, readService);
        List<String> scope = List.of("paper-a", "paper-b");
        List<String> requested = List.of("paper-a");
        when(paperService.getAccessiblePapersByIds("7", requested)).thenReturn(List.of(paper("paper-a")));
        when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                requested, PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of(readyModel("paper-a", "rm-1")));
        when(embeddingClient.embedWithUsage(
                eq(List.of("query")),
                eq("7"),
                eq(EmbeddingClient.UsageType.QUERY),
                any(Duration.class)
        )).thenReturn(new EmbeddingClient.EmbeddingUsageResult(
                List.of(new float[]{1.0f, 0.0f, 0.0f}), 1, "embedding-v1"));
        when(qdrantClient.filter(any(), any(), any())).thenReturn(Map.of());
        when(qdrantClient.searchDense(any(float[].class), any(), anyInt())).thenReturn(List.of());
        when(qdrantClient.searchSparse(any(), any(), anyInt())).thenReturn(List.of());
        when(qdrantClient.indexVersion()).thenReturn("test-index");
        when(readService.read(anyList(), anyList())).thenReturn(
                new CanonicalReadingLocationService.ReadBatch(List.of(), List.of()));

        service.searchLocations(new CorpusRetrievalService.LocationSearchQuery(
                7L, scope, requested, "query", "", List.of(), null, null, 8));

        verify(qdrantClient).verifyCollection(3);
        verify(qdrantClient, never()).ensureCollection(anyInt());
        verify(paperService).getAccessiblePapersByIds("7", requested);
    }

    @Test
    void locationSearchRejectsPaperOutsideLockedScopeBeforeRetrieval() {
        PaperService paperService = mock(PaperService.class);
        PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
        PaperLocationRepository locationRepository = mock(PaperLocationRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        CanonicalReadingLocationService readService = mock(CanonicalReadingLocationService.class);
        CorpusRetrievalService service = new CorpusRetrievalService(
                paperService, modelRepository, locationRepository, embeddingClient, qdrantClient, readService);

        assertThrows(IllegalArgumentException.class, () -> service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L,
                        List.of("paper-a"),
                        List.of("paper-b"),
                        "query",
                        "",
                        List.of(),
                        null,
                        null,
                        8
                )
        ));

        verifyNoInteractions(paperService, embeddingClient, qdrantClient, readService);
    }

    @Test
    void exactReadRejectsUnboundedLocationBatchBeforeAuthorizationQueries() {
        PaperService paperService = mock(PaperService.class);
        PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
        PaperLocationRepository locationRepository = mock(PaperLocationRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        CanonicalReadingLocationService readService = mock(CanonicalReadingLocationService.class);
        CorpusRetrievalService service = new CorpusRetrievalService(
                paperService, modelRepository, locationRepository, embeddingClient, qdrantClient, readService);
        List<String> refs = IntStream.range(0, 21).mapToObj(index -> "location-" + index).toList();

        assertThrows(IllegalArgumentException.class, () -> service.readLocations(
                new CorpusRetrievalService.LocationReadQuery(7L, List.of("paper-a"), refs)
        ));

        verifyNoInteractions(paperService, modelRepository, readService);
    }

    private static Paper paper(String paperId) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setOriginalFilename(paperId + ".pdf");
        return paper;
    }

    private static PaperReadingModel readyModel(String paperId, String modelVersion) {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId(paperId);
        model.setModelVersion(modelVersion);
        model.setCurrent(true);
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setRetrievalIndexStatus(PaperRetrievalIndexStatus.READY);
        model.setRetrievalIndexGeneration("generation-" + paperId);
        model.setRetrievalEmbeddingContract("test-index|embedding-v1|3");
        return model;
    }

    private static QdrantSearchHit hit(String locationRef, String elementType) {
        return new QdrantSearchHit(
                locationRef,
                0.9,
                Map.of(
                        "paper_id", "paper-a",
                        "model_version", "rm-1",
                        "location_ref", locationRef,
                        "index_generation", "generation-paper-a",
                        "element_type", "paragraph",
                        "element_types", List.of(elementType)
                )
        );
    }

    private static PaperLocation location(String locationRef) {
        PaperLocation location = new PaperLocation();
        location.setLocationRef(locationRef);
        location.setPaperId("paper-a");
        location.setModelVersion("rm-1");
        location.setLocationType(PaperLocationType.SECTION);
        location.setPageNumber(1);
        return location;
    }

    private static CanonicalReadingLocationService.CanonicalLocation canonical(
            String locationRef,
            String elementType
    ) {
        return new CanonicalReadingLocationService.CanonicalLocation(
                "paper-a",
                "Paper A",
                "rm-1",
                locationRef,
                elementType,
                1,
                1,
                "Methods",
                "Canonical " + elementType + " content.",
                "",
                "mineru",
                "1",
                locationRef
        );
    }

    private static QdrantSearchHit hitFor(String paperId, String modelVersion, String locationRef) {
        return new QdrantSearchHit(
                locationRef,
                0.9,
                Map.of(
                        "paper_id", paperId,
                        "model_version", modelVersion,
                        "location_ref", locationRef,
                        "index_generation", "generation-" + paperId,
                        "element_types", List.of("paragraph")
                )
        );
    }

    private static PaperLocation locationFor(String paperId, String modelVersion, String locationRef) {
        PaperLocation location = new PaperLocation();
        location.setLocationRef(locationRef);
        location.setPaperId(paperId);
        location.setModelVersion(modelVersion);
        location.setLocationType(PaperLocationType.SECTION);
        location.setPageNumber(1);
        return location;
    }

    private static CanonicalReadingLocationService.CanonicalLocation canonicalFor(
            String paperId,
            String modelVersion,
            String locationRef
    ) {
        return new CanonicalReadingLocationService.CanonicalLocation(
                paperId,
                paperId,
                modelVersion,
                locationRef,
                "paragraph",
                1,
                1,
                "Methods",
                "Canonical content for " + paperId,
                "",
                "mineru",
                "1",
                locationRef
        );
    }
}
