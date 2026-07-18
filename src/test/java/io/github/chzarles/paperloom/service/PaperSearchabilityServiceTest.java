package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperReadingModel;
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
        when(contractService.isActive("active-index-contract")).thenReturn(true);
        service = new PaperSearchabilityService(modelRepository, contractService);
    }

    @Test
    void readyQdrantIndexMakesPaperSearchable() {
        when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                List.of("paper-a"), PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of(indexedModel("paper-a")));

        assertTrue(service.isSearchable(paper("paper-a")));
    }

    @Test
    void legacyCompletionWithoutReadyIndexIsNotSearchable() {
        when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                List.of("paper-a"), PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of());

        assertFalse(service.isSearchable(paper("paper-a")));
    }

    @Test
    void incompleteRetrievalContractIsNotSearchable() {
        PaperReadingModel model = indexedModel("paper-a");
        model.setRetrievalIndexContract(null);
        when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                List.of("paper-a"), PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of(model));

        assertFalse(service.isSearchable(paper("paper-a")));
    }

    @Test
    void unavailableRetrievalIndexIsNotSearchable() {
        PaperReadingModel model = indexedModel("paper-a");
        model.setRetrievalIndexStatus(PaperRetrievalIndexStatus.FAILED);
        when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                List.of("paper-a"), PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of(model));

        assertFalse(service.isSearchable(paper("paper-a")));
    }

    @Test
    void nullOrBlankPaperIsNotSearchable() {
        assertFalse(service.isSearchable((Paper) null));
        assertFalse(service.isSearchable(paper(" ")));
    }

    private Paper paper(String paperId) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setStatus(Paper.STATUS_COMPLETED);
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_COMPLETED);
        return paper;
    }

    private PaperReadingModel indexedModel(String paperId) {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId(paperId);
        model.setModelVersion("rm-1");
        model.setCurrent(true);
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setRetrievalIndexStatus(PaperRetrievalIndexStatus.READY);
        model.setRetrievalIndexContract("active-index-contract");
        model.setRetrievalIndexedLocationCount(1);
        return model;
    }
}
