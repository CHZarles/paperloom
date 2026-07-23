package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperRetrievalIndexStatus;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CorpusRetrievalServiceTest {

    @Test
    void explicitSmallComparisonRecallsCandidatesFromEveryRequestedPaper() {
        Fixture fixture = new Fixture();
        List<String> scope = List.of("paper-a", "paper-b");
        fixture.authorize(scope);
        fixture.models(readyModel("paper-a", "rm-a"), readyModel("paper-b", "rm-b"));
        when(fixture.retriever.retrieve(any())).thenReturn(retrieval(
                candidate("paper-a", "rm-a", "location-a", 0.9),
                candidate("paper-b", "rm-b", "location-b", 0.8)
        ));
        fixture.locations(
                locationFor("paper-a", "rm-a", "location-a"),
                locationFor("paper-b", "rm-b", "location-b")
        );
        fixture.canonical(
                canonicalFor("paper-a", "rm-a", "location-a", "paragraph"),
                canonicalFor("paper-b", "rm-b", "location-b", "paragraph")
        );

        CorpusRetrievalService.LocationSearchResult result = fixture.service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L, scope, scope, "query", "", List.of(), null, null, 2));

        assertEquals(Set.of("location-a", "location-b"), result.locations().stream()
                .map(CorpusRetrievalService.LocationCandidate::locationRef)
                .collect(Collectors.toSet()));
        ArgumentCaptor<ReadingLocationRetriever.LocationRetrievalRequest> request =
                ArgumentCaptor.forClass(ReadingLocationRetriever.LocationRetrievalRequest.class);
        verify(fixture.retriever).retrieve(request.capture());
        assertEquals(Map.of("paper-a", "rm-a", "paper-b", "rm-b"), request.getValue().activeModels());
    }

    @Test
    void multiPaperSearchUsesGlobalRelevanceWhenTopKCannotCoverEveryPaper() {
        Fixture fixture = new Fixture();
        List<String> scope = List.of("paper-a", "paper-b", "paper-c");
        fixture.authorize(scope);
        fixture.models(
                readyModel("paper-a", "rm-a"),
                readyModel("paper-b", "rm-b"),
                readyModel("paper-c", "rm-c")
        );
        when(fixture.retriever.retrieve(any())).thenReturn(retrieval(
                candidate("paper-c", "rm-c", "location-c", 0.9),
                candidate("paper-b", "rm-b", "location-b", 0.8),
                candidate("paper-a", "rm-a", "location-a", 0.7)
        ));
        fixture.locations(
                locationFor("paper-a", "rm-a", "location-a"),
                locationFor("paper-b", "rm-b", "location-b"),
                locationFor("paper-c", "rm-c", "location-c")
        );
        fixture.canonical(
                canonicalFor("paper-a", "rm-a", "location-a", "paragraph"),
                canonicalFor("paper-b", "rm-b", "location-b", "paragraph"),
                canonicalFor("paper-c", "rm-c", "location-c", "paragraph")
        );

        CorpusRetrievalService.LocationSearchResult result = fixture.service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L, scope, scope, "query", "", List.of(), null, null, 2));

        assertEquals(List.of("location-c", "location-b"), result.locations().stream()
                .map(CorpusRetrievalService.LocationCandidate::locationRef).toList());
    }

    @Test
    void singlePaperSearchReservesTheCanonicalAbstractFromTheDeepCandidatePool() {
        Fixture fixture = new Fixture();
        List<String> scope = List.of("paper-a");
        fixture.authorize(scope);
        fixture.models(readyModel("paper-a", "rm-a"));
        when(fixture.retriever.retrieve(any())).thenReturn(retrieval(
                candidate("paper-a", "rm-a", "location-1", 0.9),
                candidate("paper-a", "rm-a", "location-2", 0.8),
                candidate("paper-a", "rm-a", "location-3", 0.7),
                leadCandidate("paper-a", "rm-a", "abstract-ref", 0.1)
        ));
        fixture.locations(
                locationFor("paper-a", "rm-a", "location-1"),
                locationFor("paper-a", "rm-a", "location-2"),
                locationFor("paper-a", "rm-a", "location-3"),
                locationFor("paper-a", "rm-a", "abstract-ref")
        );
        fixture.canonical(
                canonicalFor("paper-a", "rm-a", "location-1", "section"),
                canonicalFor("paper-a", "rm-a", "location-2", "section"),
                canonicalFor("paper-a", "rm-a", "location-3", "section"),
                canonicalFor("paper-a", "rm-a", "abstract-ref", "section")
        );

        CorpusRetrievalService.LocationSearchResult result = fixture.service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L, scope, scope, "benchmark structure task instances evaluation", "",
                        List.of(), null, null, 3));

        assertEquals(List.of("location-1", "location-2", "abstract-ref"), result.locations().stream()
                .map(CorpusRetrievalService.LocationCandidate::locationRef).toList());
    }

    @Test
    void shortMultiPaperSearchReservesOneLeadPerPaper() {
        Fixture fixture = new Fixture();
        List<String> scope = List.of("paper-a", "paper-b");
        fixture.authorize(scope);
        fixture.models(readyModel("paper-a", "rm-a"), readyModel("paper-b", "rm-b"));
        when(fixture.retriever.retrieve(any())).thenReturn(retrieval(
                candidate("paper-a", "rm-a", "a-hit", 0.9),
                candidate("paper-b", "rm-b", "b-hit", 0.8),
                candidate("paper-a", "rm-a", "a-second", 0.7),
                candidate("paper-b", "rm-b", "b-second", 0.6),
                leadCandidate("paper-a", "rm-a", "a-abstract", 0.2),
                leadCandidate("paper-b", "rm-b", "b-abstract", 0.1)
        ));
        fixture.locations(
                locationFor("paper-a", "rm-a", "a-hit"),
                locationFor("paper-b", "rm-b", "b-hit"),
                locationFor("paper-a", "rm-a", "a-second"),
                locationFor("paper-b", "rm-b", "b-second"),
                locationFor("paper-a", "rm-a", "a-abstract"),
                locationFor("paper-b", "rm-b", "b-abstract")
        );
        fixture.canonical(
                canonicalFor("paper-a", "rm-a", "a-hit", "section"),
                canonicalFor("paper-b", "rm-b", "b-hit", "section"),
                canonicalFor("paper-a", "rm-a", "a-second", "section"),
                canonicalFor("paper-b", "rm-b", "b-second", "section"),
                canonicalFor("paper-a", "rm-a", "a-abstract", "section"),
                canonicalFor("paper-b", "rm-b", "b-abstract", "section")
        );

        CorpusRetrievalService.LocationSearchResult result = fixture.service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L, scope, scope, "ReAct", "", List.of(), null, null, 4));

        assertEquals(Set.of("a-hit", "b-hit", "a-abstract", "b-abstract"), result.locations().stream()
                .map(CorpusRetrievalService.LocationCandidate::locationRef)
                .collect(Collectors.toSet()));
    }

    @Test
    void shortQueryDoesNotLetLeadCoverageConsumeTheWholeBudget() {
        Fixture fixture = new Fixture();
        List<String> scope = List.of("paper-a", "paper-b");
        fixture.authorize(scope);
        fixture.models(readyModel("paper-a", "rm-a"), readyModel("paper-b", "rm-b"));
        when(fixture.retriever.retrieve(any())).thenReturn(retrieval(
                candidate("paper-a", "rm-a", "a-hit", 0.9),
                candidate("paper-b", "rm-b", "b-hit", 0.8),
                leadCandidate("paper-a", "rm-a", "a-abstract", 0.2),
                leadCandidate("paper-b", "rm-b", "b-abstract", 0.1)
        ));
        fixture.locations(
                locationFor("paper-a", "rm-a", "a-hit"),
                locationFor("paper-b", "rm-b", "b-hit"),
                locationFor("paper-a", "rm-a", "a-abstract"),
                locationFor("paper-b", "rm-b", "b-abstract")
        );
        fixture.canonical(
                canonicalFor("paper-a", "rm-a", "a-hit", "section"),
                canonicalFor("paper-b", "rm-b", "b-hit", "section"),
                canonicalFor("paper-a", "rm-a", "a-abstract", "section"),
                canonicalFor("paper-b", "rm-b", "b-abstract", "section")
        );

        CorpusRetrievalService.LocationSearchResult result = fixture.service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L, scope, scope, "ReAct", "", List.of(), null, null, 2));

        assertEquals(List.of("a-hit", "b-hit"), result.locations().stream()
                .map(CorpusRetrievalService.LocationCandidate::locationRef).toList());
    }

    @Test
    void locationSearchRejectsMissingActiveIndexBeforeRetriever() {
        Fixture fixture = new Fixture();
        List<String> scope = List.of("paper-a");
        fixture.authorize(scope);
        fixture.models(readyModel("paper-a", "rm-1"));
        when(fixture.searchability.isSearchable(any(PaperReadingModel.class))).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> fixture.service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L, scope, scope, "query", "", List.of(), null, null, 8)));

        verifyNoInteractions(fixture.retriever, fixture.locationRepository, fixture.readService);
    }

    @Test
    void elementTypeHintsReachRetrieverWithoutChangingToolContract() {
        Fixture fixture = new Fixture();
        List<String> scope = List.of("paper-a");
        fixture.authorize(scope);
        fixture.models(readyModel("paper-a", "rm-1"));
        when(fixture.retriever.retrieve(any())).thenReturn(retrieval(
                candidate("paper-a", "rm-1", "table-ref", 0.9)
        ));
        fixture.locations(locationFor("paper-a", "rm-1", "table-ref"));
        fixture.canonical(canonicalFor("paper-a", "rm-1", "table-ref", "table"));

        CorpusRetrievalService.LocationSearchResult result = fixture.service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L, scope, scope, "query", "", List.of("table"), null, null, 1));

        ArgumentCaptor<ReadingLocationRetriever.LocationRetrievalRequest> request =
                ArgumentCaptor.forClass(ReadingLocationRetriever.LocationRetrievalRequest.class);
        verify(fixture.retriever).retrieve(request.capture());
        assertEquals(Set.of("table"), request.getValue().elementTypeHints());
        assertEquals("table-ref", result.locations().get(0).locationRef());
    }

    @Test
    void staleQdrantCandidateIsRejectedBeforeCanonicalHydration() {
        Fixture fixture = new Fixture();
        List<String> scope = List.of("paper-a");
        fixture.authorize(scope);
        fixture.models(readyModel("paper-a", "rm-current"));
        when(fixture.retriever.retrieve(any())).thenReturn(retrieval(
                candidate("paper-a", "rm-stale", "stale-ref", 0.9)
        ));
        fixture.locations(locationFor("paper-a", "rm-stale", "stale-ref"));
        fixture.canonical();

        CorpusRetrievalService.LocationSearchResult result = fixture.service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L, scope, scope, "query", "", List.of(), null, null, 8));

        assertEquals(0, result.returnedCount());
        verify(fixture.readService).read(List.of(), scope);
    }

    @Test
    void paperSearchAuthorizesLockedScopeWithBatchQueries() {
        Fixture fixture = new Fixture();
        List<String> scope = List.of("paper-a", "paper-b");
        fixture.authorize(scope);
        fixture.models(readyModel("paper-a", "rm-a"), readyModel("paper-b", "rm-b"));

        CorpusRetrievalService.PaperSearchResult result = fixture.service.searchPapers(
                new CorpusRetrievalService.PaperSearchQuery(
                        7L, scope, "", List.of(), List.of(), List.of(), null, null, 0, 20));

        assertEquals(2, result.returnedCount());
        verify(fixture.paperService).getAccessiblePapersByIds("7", scope);
        verify(fixture.paperService, never()).getAccessiblePapers("7", null);
        verify(fixture.modelRepository).findByPaperIdInAndIsCurrentTrueAndModelStatus(
                scope, PaperReadingModelStatus.READING_MODEL_READY);
    }

    @Test
    void locationSearchRejectsPaperOutsideLockedScopeBeforeRetrieval() {
        Fixture fixture = new Fixture();

        assertThrows(IllegalArgumentException.class, () -> fixture.service.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        7L, List.of("paper-a"), List.of("paper-b"), "query", "",
                        List.of(), null, null, 8)));

        verifyNoInteractions(fixture.paperService, fixture.retriever, fixture.readService);
    }

    @Test
    void exactReadRejectsUnboundedLocationBatchBeforeAuthorizationQueries() {
        Fixture fixture = new Fixture();
        List<String> refs = IntStream.range(0, 21).mapToObj(index -> "location-" + index).toList();

        assertThrows(IllegalArgumentException.class, () -> fixture.service.readLocations(
                new CorpusRetrievalService.LocationReadQuery(7L, List.of("paper-a"), refs)));

        verifyNoInteractions(fixture.paperService, fixture.modelRepository, fixture.readService);
    }

    private static ReadingLocationRetriever.RetrievalCandidates retrieval(
            ReadingLocationRetriever.RankedLocationCandidate... candidates) {
        return new ReadingLocationRetriever.RetrievalCandidates(
                List.of(candidates), candidates.length, "lexical-index-v1");
    }

    private static ReadingLocationRetriever.RankedLocationCandidate candidate(
            String paperId, String modelVersion, String locationRef, double score) {
        return new ReadingLocationRetriever.RankedLocationCandidate(
                locationRef,
                Map.of(
                        "paper_id", paperId,
                        "model_version", modelVersion,
                        "location_ref", locationRef,
                        "element_types", List.of("paragraph")
                ),
                score
        );
    }

    private static ReadingLocationRetriever.RankedLocationCandidate leadCandidate(
            String paperId, String modelVersion, String locationRef, double score) {
        return new ReadingLocationRetriever.RankedLocationCandidate(
                locationRef,
                Map.of(
                        "paper_id", paperId,
                        "model_version", modelVersion,
                        "location_ref", locationRef,
                        "location_type", "SECTION",
                        "section_path", "Abstract",
                        "page_number", 1,
                        "element_types", List.of("paragraph")
                ),
                score
        );
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
        model.setRetrievalIndexContract("active-index-contract");
        model.setRetrievalIndexedLocationCount(1);
        return model;
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
            String paperId, String modelVersion, String locationRef, String elementType) {
        return new CanonicalReadingLocationService.CanonicalLocation(
                paperId, paperId, modelVersion, locationRef, elementType, 1, 1, "Methods",
                "Canonical content for " + paperId, "", "mineru", "1", locationRef,
                false, false, false, false, List.of());
    }

    private static final class Fixture {
        private final PaperService paperService = mock(PaperService.class);
        private final PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
        private final PaperLocationRepository locationRepository = mock(PaperLocationRepository.class);
        private final CanonicalReadingLocationService readService = mock(CanonicalReadingLocationService.class);
        private final PaperSearchabilityService searchability = mock(PaperSearchabilityService.class);
        private final ReadingLocationRetriever retriever = mock(ReadingLocationRetriever.class);
        private final CorpusRetrievalService service = new CorpusRetrievalService(
                paperService, modelRepository, locationRepository, readService, searchability, retriever);

        private Fixture() {
            when(searchability.isSearchable(any(PaperReadingModel.class))).thenReturn(true);
        }

        private void authorize(List<String> paperIds) {
            when(paperService.getAccessiblePapersByIds("7", paperIds))
                    .thenReturn(paperIds.stream().map(CorpusRetrievalServiceTest::paper).toList());
        }

        private void models(PaperReadingModel... models) {
            List<String> paperIds = java.util.Arrays.stream(models)
                    .map(PaperReadingModel::getPaperId).toList();
            when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                    paperIds, PaperReadingModelStatus.READING_MODEL_READY)).thenReturn(List.of(models));
        }

        private void locations(PaperLocation... locations) {
            when(locationRepository.findByLocationRefIn(anyList())).thenReturn(List.of(locations));
        }

        private void canonical(CanonicalReadingLocationService.CanonicalLocation... locations) {
            when(readService.read(anyList(), anyList())).thenReturn(
                    new CanonicalReadingLocationService.ReadBatch(List.of(locations), List.of()));
        }
    }
}
