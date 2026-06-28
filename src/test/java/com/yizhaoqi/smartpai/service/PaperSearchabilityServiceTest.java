package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
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

    private Paper paper(String paperId, String vectorizationStatus, int status) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setVectorizationStatus(vectorizationStatus);
        paper.setStatus(status);
        return paper;
    }
}
