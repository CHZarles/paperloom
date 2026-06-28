package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.PaperChunkDocument;
import com.yizhaoqi.smartpai.entity.PaperSearchDocument;
import com.yizhaoqi.smartpai.eval.model.EvalChunk;
import com.yizhaoqi.smartpai.eval.model.EvalPaper;
import com.yizhaoqi.smartpai.eval.repository.EvalChunkRepository;
import com.yizhaoqi.smartpai.eval.repository.EvalPaperRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LitSearchPaperLoomImporterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private EvalPaperRepository evalPaperRepository;

    @Mock
    private EvalChunkRepository evalChunkRepository;

    @Mock
    private EvalCorpusIndexService evalCorpusIndexService;

    @TempDir
    private Path tempDir;

    @Test
    void importsLitSearchPaperRowsIntoEvalSchemaAndEvalSearchIndices() {
        when(evalPaperRepository.save(any(EvalPaper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(evalChunkRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LitSearchPaperLoomImporter importer = new LitSearchPaperLoomImporter(
                evalPaperRepository,
                evalChunkRepository,
                evalCorpusIndexService
        );
        LitSearchPaperDocument paper = new LitSearchPaperDocument(
                "gold-1",
                "Post-hoc Hallucination Detection",
                "Detects hallucinations after generation.",
                "The full paper discusses evidence retrieval and claim verification.",
                List.of()
        );

        LitSearchPaperLoomImporter.ImportSummary summary = importer.importPapers(
                List.of(paper),
                new LitSearchPaperLoomImporter.Options("dev-sample", 80, 500)
        );

        assertEquals(1, summary.paperCount());
        assertEquals(2, summary.chunkCount());

        ArgumentCaptor<EvalPaper> paperCaptor = ArgumentCaptor.forClass(EvalPaper.class);
        verify(evalPaperRepository).save(paperCaptor.capture());
        EvalPaper savedPaper = paperCaptor.getValue();
        assertEquals("litsearch", savedPaper.getCorpus());
        assertEquals("dev-sample", savedPaper.getSplit());
        assertEquals("gold-1", savedPaper.getExternalPaperId());
        assertEquals("litsearch:gold-1", savedPaper.getPaperId());
        assertEquals("Post-hoc Hallucination Detection", savedPaper.getTitle());
        assertEquals("Detects hallucinations after generation.", savedPaper.getAbstractText());
        assertEquals("The full paper discusses evidence retrieval and claim verification.", savedPaper.getFullText());
        assertTrue(savedPaper.getSourceJson().contains("gold-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(evalChunkRepository).saveAll(chunksCaptor.capture());
        List<EvalChunk> chunks = chunksCaptor.getValue();
        assertEquals(2, chunks.size());
        assertEquals("litsearch", chunks.get(0).getCorpus());
        assertEquals("dev-sample", chunks.get(0).getSplit());
        assertEquals("litsearch:gold-1", chunks.get(0).getPaperId());
        assertEquals(1, chunks.get(0).getChunkId());
        assertEquals("Abstract", chunks.get(0).getSectionTitle());
        assertEquals("PAPER_METADATA", chunks.get(0).getEvidenceRole());
        assertTrue(chunks.get(0).getTextContent().contains("Title: Post-hoc Hallucination Detection"));
        assertEquals(2, chunks.get(1).getChunkId());
        assertEquals("Full Text", chunks.get(1).getSectionTitle());
        assertEquals("FULL_TEXT", chunks.get(1).getEvidenceRole());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaperSearchDocument>> paperDocumentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(evalCorpusIndexService).bulkIndexPaperSearch(eq("litsearch"), paperDocumentsCaptor.capture());
        PaperSearchDocument paperSearchDocument = paperDocumentsCaptor.getValue().get(0);
        assertEquals("litsearch:gold-1", paperSearchDocument.getPaperId());
        assertEquals("Post-hoc Hallucination Detection", paperSearchDocument.getPaperTitle());
        assertTrue(paperSearchDocument.getSearchText().contains("abstract: Detects hallucinations"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaperChunkDocument>> chunkDocumentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(evalCorpusIndexService).bulkIndexChunks(eq("litsearch"), chunkDocumentsCaptor.capture());
        List<PaperChunkDocument> chunkDocuments = chunkDocumentsCaptor.getValue();
        assertEquals(2, chunkDocuments.size());
        assertEquals("litsearch:gold-1", chunkDocuments.get(0).getPaperId());
        assertEquals(2048, chunkDocuments.get(0).getVector().length);
        assertTrue(squaredMagnitude(chunkDocuments.get(0).getVector()) > 0.0f);
        assertTrue(chunkDocuments.get(0).getRetrievalTextContent().contains("title: Post-hoc Hallucination Detection"));
        assertTrue(chunkDocuments.get(1).getRetrievalTextContent().contains("evidence: FULL_TEXT"));
    }

    @Test
    void clearsExistingEvalRowsAndEvalSearchDocumentsBeforeImportingPaper() {
        when(evalPaperRepository.save(any(EvalPaper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(evalChunkRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LitSearchPaperLoomImporter importer = new LitSearchPaperLoomImporter(
                evalPaperRepository,
                evalChunkRepository,
                evalCorpusIndexService
        );
        LitSearchPaperDocument paper = new LitSearchPaperDocument(
                "gold-1",
                "Post-hoc Hallucination Detection",
                "Detects hallucinations after generation.",
                "The full paper discusses evidence retrieval and claim verification.",
                List.of()
        );

        importer.importPapers(
                List.of(paper),
                new LitSearchPaperLoomImporter.Options("dev-sample", 80, 500)
        );

        var inOrder = inOrder(evalCorpusIndexService, evalChunkRepository, evalPaperRepository);
        inOrder.verify(evalCorpusIndexService).deleteByPaperId("litsearch", "litsearch:gold-1");
        inOrder.verify(evalChunkRepository).deleteByCorpusAndPaperId("litsearch", "litsearch:gold-1");
        inOrder.verify(evalPaperRepository).deleteByCorpusAndPaperId("litsearch", "litsearch:gold-1");
        inOrder.verify(evalPaperRepository).save(any(EvalPaper.class));
    }

    @Test
    void indexesSearchAndChunkDocumentsInBatches() {
        when(evalPaperRepository.save(any(EvalPaper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(evalChunkRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LitSearchPaperLoomImporter importer = new LitSearchPaperLoomImporter(
                evalPaperRepository,
                evalChunkRepository,
                evalCorpusIndexService
        );

        importer.importPapers(
                List.of(
                        paper("p1"),
                        paper("p2"),
                        paper("p3")
                ),
                new LitSearchPaperLoomImporter.Options("service-slice", 80, 2)
        );

        verify(evalCorpusIndexService, times(2)).bulkIndexPaperSearch(any(), any());
        verify(evalCorpusIndexService, times(3)).bulkIndexChunks(any(), any());
    }

    @Test
    void importsJsonlCorpusFromPathWithoutPreloadingWholeDataset() throws Exception {
        when(evalPaperRepository.save(any(EvalPaper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(evalChunkRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Path corpus = tempDir.resolve("litsearch-corpus.jsonl");
        Files.write(corpus, List.of(
                OBJECT_MAPPER.writeValueAsString(paper("p1")),
                OBJECT_MAPPER.writeValueAsString(paper("p2"))
        ));
        LitSearchPaperLoomImporter importer = new LitSearchPaperLoomImporter(
                evalPaperRepository,
                evalChunkRepository,
                evalCorpusIndexService
        );

        LitSearchPaperLoomImporter.ImportSummary summary = importer.importJsonl(
                corpus,
                new LitSearchPaperLoomImporter.Options("full", 80, 500)
        );

        assertEquals(2, summary.paperCount());
        assertEquals(4, summary.chunkCount());
        verify(evalPaperRepository, times(2)).save(any(EvalPaper.class));
        verify(evalChunkRepository, times(2)).saveAll(any());
    }

    @Test
    void importsOnlyRequestedJsonlWindow() throws Exception {
        when(evalPaperRepository.save(any(EvalPaper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(evalChunkRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Path corpus = tempDir.resolve("litsearch-corpus-window.jsonl");
        Files.write(corpus, List.of(
                OBJECT_MAPPER.writeValueAsString(paper("p1")),
                OBJECT_MAPPER.writeValueAsString(paper("p2")),
                OBJECT_MAPPER.writeValueAsString(paper("p3"))
        ));
        LitSearchPaperLoomImporter importer = new LitSearchPaperLoomImporter(
                evalPaperRepository,
                evalChunkRepository,
                evalCorpusIndexService
        );

        LitSearchPaperLoomImporter.ImportSummary summary = importer.importJsonl(
                corpus,
                new LitSearchPaperLoomImporter.Options("full", 80, 500),
                1,
                1
        );

        assertEquals(1, summary.paperCount());
        ArgumentCaptor<EvalPaper> paperCaptor = ArgumentCaptor.forClass(EvalPaper.class);
        verify(evalPaperRepository).save(paperCaptor.capture());
        assertEquals("litsearch:p2", paperCaptor.getValue().getPaperId());
    }

    @Test
    void keepsFullTitleInEvalRowsAndSearchDocument() {
        when(evalPaperRepository.save(any(EvalPaper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(evalChunkRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LitSearchPaperLoomImporter importer = new LitSearchPaperLoomImporter(
                evalPaperRepository,
                evalChunkRepository,
                evalCorpusIndexService
        );
        String longTitle = "A".repeat(255) + " UNIQUE_LONG_TITLE_SUFFIX";

        importer.importPapers(
                List.of(new LitSearchPaperDocument(
                        "long-title",
                        longTitle,
                        "Abstract",
                        "Body",
                        List.of()
                )),
                new LitSearchPaperLoomImporter.Options("service-slice", 80, 500)
        );

        ArgumentCaptor<EvalPaper> paperCaptor = ArgumentCaptor.forClass(EvalPaper.class);
        verify(evalPaperRepository).save(paperCaptor.capture());
        assertTrue(paperCaptor.getValue().getTitle().contains("UNIQUE_LONG_TITLE_SUFFIX"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaperSearchDocument>> paperDocumentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(evalCorpusIndexService).bulkIndexPaperSearch(eq("litsearch"), paperDocumentsCaptor.capture());
        assertTrue(paperDocumentsCaptor.getValue().get(0).getSearchText().contains("UNIQUE_LONG_TITLE_SUFFIX"));
    }

    private static float squaredMagnitude(float[] vector) {
        float total = 0.0f;
        for (float value : vector) {
            total += value * value;
        }
        return total;
    }

    private static LitSearchPaperDocument paper(String paperId) {
        return new LitSearchPaperDocument(
                paperId,
                "Title " + paperId,
                "Abstract " + paperId,
                "Full text " + paperId,
                List.of()
        );
    }
}
