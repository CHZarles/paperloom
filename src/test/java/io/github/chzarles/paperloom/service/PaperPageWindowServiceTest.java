package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.entity.SearchResult;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperTextChunk;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperTextChunkRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperPageWindowServiceTest {

    @Test
    void inspectPageWindowReturnsLedgerReadyChunksWithPaperMetadata() {
        PaperTextChunkRepository chunkRepository = mock(PaperTextChunkRepository.class);
        PaperRepository paperRepository = mock(PaperRepository.class);
        Paper paper = paper("paper-a", "Adaptive Retrieval", "adaptive.pdf");
        PaperTextChunk tableChunk = chunk(8, 4, "Experiments", "TABLE", "table-2", "Accuracy improves with page windows.");
        tableChunk.setBboxJson("{\"pageNumber\":4}");
        PaperTextChunk textChunk = chunk(9, 5, "Discussion", "TEXT", null, "The limitation is extra context budget.");

        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-a")).thenReturn(Optional.of(paper));
        when(chunkRepository.findByPaperIdAndPageNumberBetweenOrderByPageNumberAscChunkIdAsc("paper-a", 3, 5))
                .thenReturn(List.of(tableChunk, textChunk));

        PaperPageWindowService service = new PaperPageWindowService(chunkRepository, paperRepository);

        List<SearchResult> results = service.inspectPageWindow("paper-a", 4, 1);

        assertEquals(List.of(8, 9), results.stream().map(SearchResult::getChunkId).toList());
        SearchResult first = results.get(0);
        assertEquals("paper-a", first.getPaperId());
        assertEquals("Adaptive Retrieval", first.getPaperTitle());
        assertEquals("adaptive.pdf", first.getOriginalFilename());
        assertEquals(4, first.getPageNumber());
        assertEquals("Experiments", first.getSectionTitle());
        assertEquals("TABLE", first.getSourceKind());
        assertEquals("table-2", first.getTableId());
        assertEquals("{\"pageNumber\":4}", first.getBboxJson());
        assertEquals("PAGE_WINDOW", first.getRetrievalMode());
        assertEquals("PAGE_WINDOW_INSPECT", first.getRetrievalRoute());
        verify(chunkRepository).findByPaperIdAndPageNumberBetweenOrderByPageNumberAscChunkIdAsc("paper-a", 3, 5);
    }

    @Test
    void inspectPaperReturnsAllLedgerReadyChunksForScopedPageLocation() {
        PaperTextChunkRepository chunkRepository = mock(PaperTextChunkRepository.class);
        PaperRepository paperRepository = mock(PaperRepository.class);
        Paper paper = paper("paper-a", "Adaptive Retrieval", "adaptive.pdf");
        PaperTextChunk introChunk = chunk(1, 1, "Introduction", "TEXT", null, "The paper introduces retrieval agents.");
        PaperTextChunk resultChunk = chunk(9, 5, "Experiments", "TABLE", "table-2", "Accuracy improves with page windows.");

        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-a")).thenReturn(Optional.of(paper));
        when(chunkRepository.findByPaperIdOrderByChunkIdAsc("paper-a"))
                .thenReturn(List.of(resultChunk, introChunk));

        PaperPageWindowService service = new PaperPageWindowService(chunkRepository, paperRepository);

        List<SearchResult> results = service.inspectPaper("paper-a");

        assertEquals(List.of(1, 9), results.stream().map(SearchResult::getChunkId).toList());
        assertEquals(List.of(1, 5), results.stream().map(SearchResult::getPageNumber).toList());
        assertEquals("Adaptive Retrieval", results.get(0).getPaperTitle());
        assertEquals("adaptive.pdf", results.get(0).getOriginalFilename());
        assertEquals("PAGE_WINDOW_INSPECT", results.get(1).getRetrievalRoute());
        verify(chunkRepository).findByPaperIdOrderByChunkIdAsc("paper-a");
    }

    private Paper paper(String paperId, String title, String filename) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setPaperTitle(title);
        paper.setOriginalFilename(filename);
        return paper;
    }

    private PaperTextChunk chunk(int chunkId,
                                 int pageNumber,
                                 String sectionTitle,
                                 String sourceKind,
                                 String tableId,
                                 String text) {
        PaperTextChunk chunk = new PaperTextChunk();
        chunk.setPaperId("paper-a");
        chunk.setChunkId(chunkId);
        chunk.setPageNumber(pageNumber);
        chunk.setSectionTitle(sectionTitle);
        chunk.setSourceKind(sourceKind);
        chunk.setTableId(tableId);
        chunk.setTextContent(text);
        return chunk;
    }
}
