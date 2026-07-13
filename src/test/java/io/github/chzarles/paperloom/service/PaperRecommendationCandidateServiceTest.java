package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperLocationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperRecommendationCandidateServiceTest {

    @Mock
    private PaperCandidateSearchService paperCandidateSearchService;

    @Mock
    private ReadingModelGrepSearchService readingModelGrepSearchService;

    private PaperRecommendationCandidateService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PaperRecommendationCandidateService(paperCandidateSearchService, readingModelGrepSearchService);
    }

    @Test
    void queryReturnsPaperGroupedCandidatesWithPerPaperLocationLimit() {
        PaperCandidate unsupported = candidate("paper-no-evidence", "No Evidence", 10);
        PaperCandidate supported = candidate("paper-supported", "Supported", 20);
        when(paperCandidateSearchService.search(new PaperCandidateSearchRequest("agentic eval", "u1", "lab", 20)))
                .thenReturn(List.of(unsupported, supported));
        when(readingModelGrepSearchService.search(new ReadingModelGrepSearchRequest(
                List.of("paper-no-evidence", "paper-supported"),
                "agentic eval",
                List.of(),
                null,
                null,
                40
        ))).thenReturn(List.of(
                location("paper-supported", "section-ref-1"),
                location("paper-supported", "section-ref-2"),
                location("paper-supported", "section-ref-3")
        ));
        when(readingModelGrepSearchService.hasCurrentModel("paper-no-evidence")).thenReturn(true);
        when(readingModelGrepSearchService.hasCurrentModel("paper-supported")).thenReturn(true);

        List<PaperRecommendationCandidate> candidates = service.search(new PaperRecommendationSearchRequest(
                "agentic eval",
                "u1",
                "lab",
                20,
                2
        ));

        assertEquals(List.of("paper-supported", "paper-no-evidence"),
                candidates.stream().map(PaperRecommendationCandidate::paperId).toList());
        assertEquals("SUPPORTED", candidates.get(0).evidenceStatus());
        assertEquals(List.of("section-ref-1", "section-ref-2"),
                candidates.get(0).supportingLocations().stream().map(ReadingLocationCandidate::locationRef).toList());
        assertEquals("NO_READING_LOCATION_MATCH", candidates.get(1).evidenceStatus());
        assertEquals(List.of(), candidates.get(1).supportingLocations());

        ArgumentCaptor<ReadingModelGrepSearchRequest> requestCaptor =
                ArgumentCaptor.forClass(ReadingModelGrepSearchRequest.class);
        verify(readingModelGrepSearchService).search(requestCaptor.capture());
        assertEquals(List.of("paper-no-evidence", "paper-supported"), requestCaptor.getValue().paperIds());
        assertEquals(40, requestCaptor.getValue().limit());
    }

    @Test
    void marksCandidateWithoutCurrentModelSeparatelyFromCurrentModelWithoutHits() {
        PaperCandidate noModel = candidate("paper-no-model", "No Model", 10);
        PaperCandidate noHit = candidate("paper-no-hit", "No Hit", 20);
        when(paperCandidateSearchService.search(new PaperCandidateSearchRequest("agentic eval", "u1", "lab", 20)))
                .thenReturn(List.of(noModel, noHit));
        when(readingModelGrepSearchService.search(new ReadingModelGrepSearchRequest(
                List.of("paper-no-model", "paper-no-hit"),
                "agentic eval",
                List.of(),
                null,
                null,
                60
        ))).thenReturn(List.of());
        when(readingModelGrepSearchService.hasCurrentModel("paper-no-model")).thenReturn(false);
        when(readingModelGrepSearchService.hasCurrentModel("paper-no-hit")).thenReturn(true);

        List<PaperRecommendationCandidate> candidates = service.search(new PaperRecommendationSearchRequest(
                "agentic eval",
                "u1",
                "lab",
                20,
                3
        ));

        assertEquals("NO_CURRENT_READING_MODEL", candidates.get(0).evidenceStatus());
        assertEquals("NO_READING_LOCATION_MATCH", candidates.get(1).evidenceStatus());
    }

    private PaperCandidate candidate(String paperId, String title, int rank) {
        return new PaperCandidate(
                paperId,
                title,
                "Ada Lovelace",
                2025,
                "NeurIPS",
                "Preview",
                List.of("title"),
                "title matched all query tokens",
                rank
        );
    }

    private ReadingLocationCandidate location(String paperId, String locationRef) {
        return new ReadingLocationCandidate(
                paperId,
                "v1",
                locationRef,
                PaperLocationType.SECTION,
                2,
                2,
                "Evaluation",
                null,
                "Agentic eval appears here.",
                "SECTION",
                "SECTION_LOCATION",
                List.of("sectionText"),
                List.of()
        );
    }
}
