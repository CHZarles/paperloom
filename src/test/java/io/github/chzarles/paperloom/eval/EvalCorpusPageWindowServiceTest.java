package io.github.chzarles.paperloom.eval;

import io.github.chzarles.paperloom.entity.SearchResult;
import io.github.chzarles.paperloom.eval.model.EvalChunk;
import io.github.chzarles.paperloom.eval.model.EvalPaper;
import io.github.chzarles.paperloom.eval.repository.EvalChunkRepository;
import io.github.chzarles.paperloom.eval.repository.EvalPaperRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvalCorpusPageWindowServiceTest {

    @Test
    void readsQasperPaperFromEvalTablesAndMapsSearchResults() {
        EvalPaperRepository paperRepository = mock(EvalPaperRepository.class);
        EvalChunkRepository chunkRepository = mock(EvalChunkRepository.class);
        EvalCorpusPageWindowService service = new EvalCorpusPageWindowService(paperRepository, chunkRepository);
        EvalPaper paper = paper("qasper:paper-a", "QASPER Paper", "{\"source\":\"fixture\"}");
        EvalChunk laterChunk = chunk("qasper:paper-a", 20, 2, "", "Fallback text", null, "answer");
        EvalChunk earlierChunk = chunk(
                "qasper:paper-a",
                10,
                1,
                "Retrieval text",
                "Raw text",
                "TABLE",
                "supporting"
        );
        when(paperRepository.findByCorpusAndPaperId("qasper", "qasper:paper-a"))
                .thenReturn(Optional.of(paper));
        when(chunkRepository.findByCorpusAndPaperIdOrderByChunkIdAsc("qasper", "qasper:paper-a"))
                .thenReturn(List.of(laterChunk, earlierChunk));

        List<SearchResult> results = service.inspectPaper(RetrievalCorpus.EVAL_QASPER, "qasper:paper-a");

        assertEquals(List.of(10, 20), results.stream().map(SearchResult::getChunkId).toList());
        SearchResult first = results.get(0);
        assertEquals("qasper:paper-a", first.getPaperId());
        assertEquals("Retrieval text", first.getTextContent());
        assertEquals("QASPER Paper", first.getPaperTitle());
        assertEquals("{\"source\":\"fixture\"}", first.getOriginalFilename());
        assertEquals(1, first.getPageNumber());
        assertEquals("TABLE", first.getSourceKind());
        assertEquals("supporting", first.getEvidenceRole());
        assertEquals("EVAL_PAGE_WINDOW_INSPECT", first.getRetrievalRoute());
        assertEquals("eval-qasper", first.getOrgTag());
    }

    @Test
    void readsEvalPageWindowWithBoundedRadius() {
        EvalPaperRepository paperRepository = mock(EvalPaperRepository.class);
        EvalChunkRepository chunkRepository = mock(EvalChunkRepository.class);
        EvalCorpusPageWindowService service = new EvalCorpusPageWindowService(paperRepository, chunkRepository);
        EvalChunk evidence = chunk(
                "litsearch:paper-a",
                7,
                5,
                "Evidence text",
                "Raw text",
                "TEXT",
                "answer"
        );
        when(paperRepository.findByCorpusAndPaperId("litsearch", "litsearch:paper-a"))
                .thenReturn(Optional.empty());
        when(chunkRepository.findByCorpusAndPaperIdAndPageNumberBetweenOrderByPageNumberAscChunkIdAsc(
                "litsearch",
                "litsearch:paper-a",
                1,
                15
        )).thenReturn(List.of(evidence));

        List<SearchResult> results = service.inspectPageWindow(
                RetrievalCorpus.EVAL_LITSEARCH,
                "litsearch:paper-a",
                5,
                99
        );

        assertEquals(1, results.size());
        assertEquals("litsearch:paper-a", results.get(0).getPaperId());
        assertEquals(5, results.get(0).getPageNumber());
        assertEquals("litsearch:paper-a.json", results.get(0).getOriginalFilename());
        verify(chunkRepository).findByCorpusAndPaperIdAndPageNumberBetweenOrderByPageNumberAscChunkIdAsc(
                "litsearch",
                "litsearch:paper-a",
                1,
                15
        );
    }

    @Test
    void rejectsProductCorpus() {
        EvalCorpusPageWindowService service = new EvalCorpusPageWindowService(
                mock(EvalPaperRepository.class),
                mock(EvalChunkRepository.class)
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.inspectPaper(RetrievalCorpus.PRODUCT_LIBRARY, "paper-a")
        );

        assertEquals("eval page-window inspection requires an eval retrieval corpus", error.getMessage());
    }

    private EvalPaper paper(String paperId, String title, String sourceJson) {
        EvalPaper paper = new EvalPaper();
        paper.setCorpus("qasper");
        paper.setSplit("dev");
        paper.setExternalPaperId(paperId);
        paper.setPaperId(paperId);
        paper.setTitle(title);
        paper.setSourceJson(sourceJson);
        return paper;
    }

    private EvalChunk chunk(String paperId,
                            int chunkId,
                            int pageNumber,
                            String retrievalText,
                            String text,
                            String sourceKind,
                            String evidenceRole) {
        EvalChunk chunk = new EvalChunk();
        chunk.setCorpus(paperId.startsWith("litsearch:") ? "litsearch" : "qasper");
        chunk.setSplit("dev");
        chunk.setPaperId(paperId);
        chunk.setChunkId(chunkId);
        chunk.setPageNumber(pageNumber);
        chunk.setRetrievalTextContent(retrievalText);
        chunk.setTextContent(text);
        chunk.setSectionTitle("Methods");
        chunk.setSourceKind(sourceKind);
        chunk.setEvidenceRole(evidenceRole);
        return chunk;
    }
}
