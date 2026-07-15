package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductPaperCorpusTest {

    @Test
    void autoScopeResolvesOnlyAccessibleSearchableProductPaperIds() {
        PaperService paperService = mock(PaperService.class);
        PaperSearchabilityService searchabilityService = mock(PaperSearchabilityService.class);
        Paper searchable = paper("paper-searchable", Paper.VECTORIZATION_STATUS_COMPLETED);
        Paper failed = paper("paper-failed", Paper.VECTORIZATION_STATUS_FAILED);
        List<Paper> accessible = List.of(searchable, failed);
        when(paperService.getAccessiblePapers("u1", null)).thenReturn(accessible);
        when(searchabilityService.searchablePaperIds(accessible)).thenReturn(Set.of("paper-searchable"));
        ProductPaperCorpus corpus = new ProductPaperCorpus(paperService, searchabilityService);

        ProductPaperCorpus.ProductPaperSet resolved = corpus.resolveAccessibleSearchablePaperIds(
                "u1",
                SourceScope.auto()
        );

        assertEquals(List.of("paper-searchable"), resolved.paperIds());
        assertEquals(2, resolved.accessibleCount());
        assertEquals(1, resolved.searchableCount());
    }

    @Test
    void manualScopeIntersectsRequestedPaperIdsWithAccessibleSearchableProductPapers() {
        PaperService paperService = mock(PaperService.class);
        PaperSearchabilityService searchabilityService = mock(PaperSearchabilityService.class);
        Paper allowed = paper("paper-allowed", Paper.VECTORIZATION_STATUS_COMPLETED);
        Paper inaccessibleRequest = paper("paper-other", Paper.VECTORIZATION_STATUS_COMPLETED);
        List<Paper> accessible = List.of(allowed, inaccessibleRequest);
        when(paperService.getAccessiblePapers("u1", null)).thenReturn(accessible);
        when(searchabilityService.searchablePaperIds(accessible)).thenReturn(Set.of("paper-allowed"));
        ProductPaperCorpus corpus = new ProductPaperCorpus(paperService, searchabilityService);

        ProductPaperCorpus.ProductPaperSet resolved = corpus.resolveAccessibleSearchablePaperIds(
                "u1",
                SourceScope.manual(List.of("paper-allowed", "orphan-paper"))
        );

        assertEquals(List.of("paper-allowed"), resolved.paperIds());
        assertEquals(List.of("orphan-paper"), resolved.rejectedRequestedPaperIds());
    }

    private Paper paper(String paperId, String vectorizationStatus) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setOriginalFilename(paperId + ".pdf");
        paper.setStatus(Paper.STATUS_COMPLETED);
        paper.setVectorizationStatus(vectorizationStatus);
        return paper;
    }
}
