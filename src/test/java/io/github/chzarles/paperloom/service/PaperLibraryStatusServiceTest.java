package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.repository.PaperParserArtifactRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperTextChunkRepository;
import io.github.chzarles.paperloom.repository.PaperVisualAssetRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperLibraryStatusServiceTest {

    @Test
    void statusCountsAccessibleSearchableAndScopedPapersFromMetadata() {
        PaperService paperService = mock(PaperService.class);
        PaperSearchabilityService searchabilityService = mock(PaperSearchabilityService.class);
        PaperRepository paperRepository = mock(PaperRepository.class);
        PaperTextChunkRepository chunkRepository = mock(PaperTextChunkRepository.class);
        PaperVisualAssetRepository visualAssetRepository = mock(PaperVisualAssetRepository.class);
        PaperParserArtifactRepository artifactRepository = mock(PaperParserArtifactRepository.class);
        Paper searchable = paper("paper-searchable", Paper.VECTORIZATION_STATUS_COMPLETED);
        Paper failed = paper("paper-failed", Paper.VECTORIZATION_STATUS_FAILED);
        Paper parsing = paper("paper-parsing", Paper.VECTORIZATION_STATUS_MINERU_RUNNING);
        when(paperService.getAccessiblePapers("u1", null)).thenReturn(List.of(searchable, failed, parsing));
        when(searchabilityService.isSearchable(searchable)).thenReturn(true);
        when(searchabilityService.isSearchable(failed)).thenReturn(false);
        when(searchabilityService.isSearchable(parsing)).thenReturn(false);
        when(paperRepository.findDistinctPaperIds()).thenReturn(List.of("paper-searchable", "paper-failed", "paper-parsing"));
        when(chunkRepository.findDistinctPaperIds()).thenReturn(List.of("paper-searchable"));
        when(visualAssetRepository.findDistinctPaperIds()).thenReturn(List.of("paper-searchable"));
        when(artifactRepository.findDistinctPaperIds()).thenReturn(List.of("paper-searchable"));
        PaperLibraryStatusService service = new PaperLibraryStatusService(
                paperService,
                searchabilityService,
                paperRepository,
                chunkRepository,
                visualAssetRepository,
                artifactRepository,
                null
        );

        PaperLibraryStatus status = service.statusFor("u1", SourceScope.manual(List.of("paper-searchable")));

        assertEquals(3, status.accessibleCount());
        assertEquals(1, status.searchableCount());
        assertEquals(1, status.selectedScopeCount());
        assertEquals(1, status.failedCount());
        assertEquals(1, status.parsingCount());
        assertEquals(0, status.indexingCount());
        assertEquals(List.of(), status.consistencyWarnings());
    }

    @Test
    void statusReportsDbDerivedRowsWithoutProductPaperRows() {
        PaperService paperService = mock(PaperService.class);
        PaperSearchabilityService searchabilityService = mock(PaperSearchabilityService.class);
        PaperRepository paperRepository = mock(PaperRepository.class);
        PaperTextChunkRepository chunkRepository = mock(PaperTextChunkRepository.class);
        PaperVisualAssetRepository visualAssetRepository = mock(PaperVisualAssetRepository.class);
        PaperParserArtifactRepository artifactRepository = mock(PaperParserArtifactRepository.class);
        Paper searchable = paper("paper-searchable", Paper.VECTORIZATION_STATUS_COMPLETED);
        when(paperService.getAccessiblePapers("u1", null)).thenReturn(List.of(searchable));
        when(searchabilityService.isSearchable(searchable)).thenReturn(true);
        when(paperRepository.findDistinctPaperIds()).thenReturn(List.of("paper-searchable"));
        when(chunkRepository.findDistinctPaperIds()).thenReturn(List.of("paper-searchable", "orphan-chunk-paper"));
        when(visualAssetRepository.findDistinctPaperIds()).thenReturn(List.of("orphan-asset-paper"));
        when(artifactRepository.findDistinctPaperIds()).thenReturn(List.of("orphan-artifact-paper"));
        PaperLibraryStatusService service = new PaperLibraryStatusService(
                paperService,
                searchabilityService,
                paperRepository,
                chunkRepository,
                visualAssetRepository,
                artifactRepository,
                null
        );

        PaperLibraryStatus status = service.statusFor("u1", SourceScope.auto());

        assertEquals(3, status.consistencyWarnings().size());
        assertTrue(status.consistencyWarnings().stream()
                .anyMatch(warning -> warning.contains("paper_text_chunks") && warning.contains("1 个")));
        assertTrue(status.consistencyWarnings().stream()
                .anyMatch(warning -> warning.contains("paper_visual_assets") && warning.contains("1 个")));
        assertTrue(status.consistencyWarnings().stream()
                .anyMatch(warning -> warning.contains("paper_parser_artifacts") && warning.contains("1 个")));
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
