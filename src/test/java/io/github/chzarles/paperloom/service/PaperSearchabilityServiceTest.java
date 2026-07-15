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

    private PaperSearchabilityService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PaperSearchabilityService(modelRepository);
    }

    @Test
    void activeQdrantGenerationMakesPaperSearchable() {
        when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                List.of("paper-a"), PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of(indexedModel("paper-a")));

        assertTrue(service.isSearchable(paper("paper-a")));
    }

    @Test
    void legacyCompletionWithoutActiveQdrantGenerationIsNotSearchable() {
        when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                List.of("paper-a"), PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of());

        assertFalse(service.isSearchable(paper("paper-a")));
    }

    @Test
    void incompleteRetrievalContractIsNotSearchable() {
        PaperReadingModel model = indexedModel("paper-a");
        model.setRetrievalEmbeddingContract(null);
        when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                List.of("paper-a"), PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of(model));

        assertFalse(service.isSearchable(paper("paper-a")));
    }

    @Test
    void unavailableRetrievalIndexIsNotSearchable() {
        PaperReadingModel model = indexedModel("paper-a");
        model.setRetrievalIndexStatus(PaperRetrievalIndexStatus.UNAVAILABLE);
        when(modelRepository.findByPaperIdInAndIsCurrentTrueAndModelStatus(
                List.of("paper-a"), PaperReadingModelStatus.READING_MODEL_READY
        )).thenReturn(List.of(model));

        assertFalse(service.isSearchable(paper("paper-a")));
    }

    @Test
    void nullOrBlankPaperIsNotSearchable() {
        assertFalse(service.isSearchable(null));
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
        model.setRetrievalIndexGeneration("generation-1");
        model.setRetrievalEmbeddingContract("collection|embedding-v1|3");
        model.setRetrievalIndexedLocationCount(1);
        return model;
    }
}
