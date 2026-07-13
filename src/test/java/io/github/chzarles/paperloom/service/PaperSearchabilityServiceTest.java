package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.repository.PaperTextChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class PaperSearchabilityServiceTest {

    @Mock
    private PaperTextChunkRepository chunkRepository;

    private PaperSearchabilityService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PaperSearchabilityService(chunkRepository);
    }

    @Test
    void completedPaperWithIndexedChunksIsSearchable() {
        Paper paper = paper("paper-a", "COMPLETED", 1);
        when(chunkRepository.countByPaperId("paper-a")).thenReturn(12L);

        assertTrue(service.isSearchable(paper));
    }

    @Test
    void completedPaperWithoutChunksIsNotSearchable() {
        Paper paper = paper("paper-a", "COMPLETED", 1);
        when(chunkRepository.countByPaperId("paper-a")).thenReturn(0L);

        assertFalse(service.isSearchable(paper));
    }

    @Test
    void failedPaperIsNotSearchable() {
        Paper paper = paper("paper-a", "FAILED", 0);
        when(chunkRepository.countByPaperId("paper-a")).thenReturn(12L);

        assertFalse(service.isSearchable(paper));
    }

    @Test
    void failedVectorizationStatusOverridesCompletedUploadStatus() {
        Paper paper = paper("paper-a", "FAILED", Paper.STATUS_COMPLETED);
        when(chunkRepository.countByPaperId("paper-a")).thenReturn(12L);

        assertFalse(service.isSearchable(paper));
    }

    @Test
    void processingVectorizationStatusOverridesCompletedUploadStatus() {
        Paper paper = paper("paper-a", "PROCESSING", Paper.STATUS_COMPLETED);
        when(chunkRepository.countByPaperId("paper-a")).thenReturn(12L);

        assertFalse(service.isSearchable(paper));
    }

    @Test
    void nullPaperIsNotSearchable() {
        assertFalse(service.isSearchable(null));
    }

    @Test
    void blankPaperIdIsNotSearchable() {
        Paper paper = paper(" ", "COMPLETED", Paper.STATUS_COMPLETED);

        assertFalse(service.isSearchable(paper));
    }

    @Test
    void nullVectorizationStatusFallsBackToCompletedUploadStatusForLegacyPapers() {
        Paper paper = paper("paper-a", null, Paper.STATUS_COMPLETED);
        when(chunkRepository.countByPaperId("paper-a")).thenReturn(12L);

        assertTrue(service.isSearchable(paper));
    }

    @Test
    void blankVectorizationStatusFallsBackToCompletedUploadStatusForLegacyPapers() {
        Paper paper = paper("paper-a", " ", Paper.STATUS_COMPLETED);
        when(chunkRepository.countByPaperId("paper-a")).thenReturn(12L);

        assertTrue(service.isSearchable(paper));
    }

    private Paper paper(String paperId, String vectorizationStatus, int status) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setVectorizationStatus(vectorizationStatus);
        paper.setStatus(status);
        return paper;
    }
}
