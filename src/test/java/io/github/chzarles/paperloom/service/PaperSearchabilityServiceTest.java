package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperRetrievalIndexStatus;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperSearchabilityServiceTest {

    @Mock
    private PaperReadingModelRepository modelRepository;

    @Mock
    private RetrievalIndexContractService contractService;

    private PaperSearchabilityService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(contractService.activeContract()).thenReturn("active-index-contract");
        service = new PaperSearchabilityService(modelRepository, contractService);
    }

    @Test
    void readyQdrantIndexMakesPaperSearchable() {
        when(modelRepository.findSearchableCurrentPaperIds(
                List.of("paper-a"),
                PaperReadingModelStatus.READING_MODEL_READY,
                PaperRetrievalIndexStatus.READY,
                "active-index-contract"
        )).thenReturn(List.of("paper-a"));

        assertTrue(service.isSearchable(paper("paper-a")));
    }

    @Test
    void legacyCompletionWithoutReadyIndexIsNotSearchable() {
        when(modelRepository.findSearchableCurrentPaperIds(
                List.of("paper-a"),
                PaperReadingModelStatus.READING_MODEL_READY,
                PaperRetrievalIndexStatus.READY,
                "active-index-contract"
        )).thenReturn(List.of());

        assertFalse(service.isSearchable(paper("paper-a")));
    }

    @Test
    void incompleteRetrievalContractIsNotSearchable() {
        when(modelRepository.findSearchableCurrentPaperIds(
                List.of("paper-a"),
                PaperReadingModelStatus.READING_MODEL_READY,
                PaperRetrievalIndexStatus.READY,
                "active-index-contract"
        )).thenReturn(List.of());

        assertFalse(service.isSearchable(paper("paper-a")));
    }

    @Test
    void unavailableRetrievalIndexIsNotSearchable() {
        when(modelRepository.findSearchableCurrentPaperIds(
                List.of("paper-a"),
                PaperReadingModelStatus.READING_MODEL_READY,
                PaperRetrievalIndexStatus.READY,
                "active-index-contract"
        )).thenReturn(List.of());

        assertFalse(service.isSearchable(paper("paper-a")));
    }

    @Test
    void nullOrBlankPaperIsNotSearchable() {
        assertFalse(service.isSearchable((Paper) null));
        assertFalse(service.isSearchable(paper(" ")));
    }

    @Test
    void batchSearchabilityReadsActiveContractOnce() {
        when(modelRepository.findSearchableCurrentPaperIds(
                List.of("paper-a", "paper-b"),
                PaperReadingModelStatus.READING_MODEL_READY,
                PaperRetrievalIndexStatus.READY,
                "active-index-contract"
        )).thenReturn(List.of("paper-a", "paper-b"));

        assertTrue(service.searchablePaperIdsById(List.of("paper-a", "paper-b")).containsAll(List.of("paper-a", "paper-b")));
        verify(contractService, times(1)).activeContract();
    }

    private Paper paper(String paperId) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setStatus(Paper.STATUS_COMPLETED);
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_COMPLETED);
        return paper;
    }

}
