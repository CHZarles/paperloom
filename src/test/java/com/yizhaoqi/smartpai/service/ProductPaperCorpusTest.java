package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.Paper;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        when(paperService.getAccessiblePapers("u1", null)).thenReturn(List.of(searchable, failed));
        when(searchabilityService.isSearchable(searchable)).thenReturn(true);
        when(searchabilityService.isSearchable(failed)).thenReturn(false);
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
        when(paperService.getAccessiblePapers("u1", null)).thenReturn(List.of(allowed, inaccessibleRequest));
        when(searchabilityService.isSearchable(allowed)).thenReturn(true);
        when(searchabilityService.isSearchable(inaccessibleRequest)).thenReturn(false);
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
