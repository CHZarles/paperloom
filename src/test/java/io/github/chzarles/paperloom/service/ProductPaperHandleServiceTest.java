package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class ProductPaperHandleServiceTest {

    @Mock
    private PaperService paperService;

    @Mock
    private PaperReadingModelRepository modelRepository;

    @Mock
    private PaperRepository paperRepository;

    private ProductPaperHandleService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ProductPaperHandleService(paperService, modelRepository, paperRepository);
    }

    @Test
    void samePaperIdReturnsOpaqueResolvableHandle() {
        String handle = service.handleForPaperId("raw-paper-id-123");

        assertEquals(handle, service.handleForPaperId("raw-paper-id-123"));
        assertTrue(handle.startsWith("paper_handle_"));
        assertFalse(handle.contains("raw-paper-id-123"));
        assertEquals(Optional.of("raw-paper-id-123"), service.resolvePaperHandle(handle));
        assertEquals(Optional.empty(), service.resolvePaperHandle("paper_handle_unknown"));
    }

    @Test
    void resolvesDeterministicHandleAfterCacheColdStart() {
        String handle = service.handleForPaperId("durable-paper-id");
        ProductPaperHandleService coldService = new ProductPaperHandleService(paperService, modelRepository, paperRepository);
        when(paperRepository.findDistinctPaperIds()).thenReturn(List.of("other-paper-id", "durable-paper-id"));

        assertEquals(Optional.of("durable-paper-id"), coldService.resolvePaperHandle(handle));
    }

    @Test
    void visibilityRequiresPermissionAndLockedScope() {
        when(paperService.getAccessiblePapers("7", null)).thenReturn(List.of(paper("paper-in-scope")));

        assertTrue(service.isPaperVisibleToUser("paper-in-scope", 7L, SourceScope.manual(List.of("paper-in-scope"))));
        assertFalse(service.isPaperVisibleToUser("paper-in-scope", 7L, SourceScope.manual(List.of("other-paper"))));
        assertFalse(service.isPaperVisibleToUser("paper-missing", 7L, SourceScope.auto()));
    }

    @Test
    void readyRequiresCurrentReadingModelReadyStatus() {
        PaperReadingModel ready = model("ready-paper", PaperReadingModelStatus.READING_MODEL_READY);
        PaperReadingModel failed = model("failed-paper", PaperReadingModelStatus.READING_MODEL_FAILED);
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-paper")).thenReturn(Optional.of(ready));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("failed-paper")).thenReturn(Optional.of(failed));

        assertTrue(service.hasCurrentReadyReadingModel("ready-paper"));
        assertFalse(service.hasCurrentReadyReadingModel("failed-paper"));
        assertFalse(service.hasCurrentReadyReadingModel("missing-paper"));
    }

    private Paper paper(String paperId) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        return paper;
    }

    private PaperReadingModel model(String paperId, PaperReadingModelStatus status) {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId(paperId);
        model.setModelVersion("v1");
        model.setCurrent(true);
        model.setModelStatus(status);
        return model;
    }
}
