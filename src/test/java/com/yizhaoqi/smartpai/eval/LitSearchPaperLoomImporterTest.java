package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.PaperChunkDocument;
import com.yizhaoqi.smartpai.entity.PaperSearchDocument;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperTextChunk;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LitSearchPaperLoomImporterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private PaperTextChunkRepository paperTextChunkRepository;

    @Mock
    private ElasticsearchService elasticsearchService;

    @TempDir
    private Path tempDir;

    @Test
    void importsLitSearchPaperRowsAsEvalScopedPaperLoomRowsAndSearchDocuments() {
        when(paperRepository.save(any(Paper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paperTextChunkRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LitSearchPaperLoomImporter importer = new LitSearchPaperLoomImporter(
                paperRepository,
                paperTextChunkRepository,
                elasticsearchService
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
                new LitSearchPaperLoomImporter.Options("eval-user", "eval-litsearch", true, 80, "dev-sample")
        );

        assertEquals(1, summary.paperCount());
        assertEquals(2, summary.chunkCount());

        ArgumentCaptor<Paper> paperCaptor = ArgumentCaptor.forClass(Paper.class);
        verify(paperRepository).save(paperCaptor.capture());
        Paper savedPaper = paperCaptor.getValue();
        assertEquals("litsearch:gold-1", savedPaper.getPaperId());
        assertEquals("litsearch:gold-1.json", savedPaper.getOriginalFilename());
        assertEquals("Post-hoc Hallucination Detection", savedPaper.getPaperTitle());
        assertEquals("Detects hallucinations after generation.", savedPaper.getAbstractText());
        assertEquals("eval-user", savedPaper.getUserId());
        assertEquals("eval-litsearch", savedPaper.getOrgTag());
        assertTrue(savedPaper.isPublic());
        assertTrue(savedPaper.isEval());
        assertEquals("litsearch", savedPaper.getSourceDataset());
        assertEquals("gold-1", savedPaper.getExternalCorpusId());
        assertEquals("dev-sample", savedPaper.getEvalSplit());
        assertEquals(Paper.STATUS_COMPLETED, savedPaper.getStatus());
        assertEquals(Paper.VECTORIZATION_STATUS_COMPLETED, savedPaper.getVectorizationStatus());
        assertEquals(2, savedPaper.getActualChunkCount());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaperTextChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(paperTextChunkRepository).saveAll(chunksCaptor.capture());
        List<PaperTextChunk> chunks = chunksCaptor.getValue();
        assertEquals(2, chunks.size());
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
        verify(elasticsearchService).bulkIndexPaperSearch(paperDocumentsCaptor.capture());
        PaperSearchDocument paperSearchDocument = paperDocumentsCaptor.getValue().get(0);
        assertEquals("litsearch:gold-1", paperSearchDocument.getPaperId());
        assertEquals("Post-hoc Hallucination Detection", paperSearchDocument.getPaperTitle());
        assertTrue(paperSearchDocument.getSearchText().contains("abstract: Detects hallucinations"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaperChunkDocument>> chunkDocumentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(elasticsearchService).bulkIndex(chunkDocumentsCaptor.capture());
        List<PaperChunkDocument> chunkDocuments = chunkDocumentsCaptor.getValue();
        assertEquals(2, chunkDocuments.size());
        assertEquals("litsearch:gold-1", chunkDocuments.get(0).getPaperId());
        assertEquals(2048, chunkDocuments.get(0).getVector().length);
        assertTrue(squaredMagnitude(chunkDocuments.get(0).getVector()) > 0.0f);
        assertTrue(chunkDocuments.get(0).getRetrievalTextContent().contains("title: Post-hoc Hallucination Detection"));
        assertTrue(chunkDocuments.get(1).getRetrievalTextContent().contains("evidence: FULL_TEXT"));
    }

    @Test
    void clearsExistingEvalRowsAndSearchDocumentsBeforeImportingPaper() {
        when(paperRepository.save(any(Paper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paperTextChunkRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LitSearchPaperLoomImporter importer = new LitSearchPaperLoomImporter(
                paperRepository,
                paperTextChunkRepository,
                elasticsearchService
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
                new LitSearchPaperLoomImporter.Options("eval-user", "eval-litsearch", true, 80, "dev-sample")
        );

        var inOrder = inOrder(elasticsearchService, paperTextChunkRepository, paperRepository);
        inOrder.verify(elasticsearchService).deleteByPaperId("litsearch:gold-1");
        inOrder.verify(paperTextChunkRepository).deleteByPaperId("litsearch:gold-1");
        inOrder.verify(paperRepository).deleteByPaperId("litsearch:gold-1");
        inOrder.verify(paperRepository).save(any(Paper.class));
    }

    @Test
    void indexesSearchAndChunkDocumentsInBatches() {
        when(paperRepository.save(any(Paper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paperTextChunkRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LitSearchPaperLoomImporter importer = new LitSearchPaperLoomImporter(
                paperRepository,
                paperTextChunkRepository,
                elasticsearchService
        );

        importer.importPapers(
                List.of(
                        paper("p1"),
                        paper("p2"),
                        paper("p3")
                ),
                new LitSearchPaperLoomImporter.Options(
                        "eval-user",
                        "eval-litsearch",
                        true,
                        80,
                        "service-slice",
                        2
                )
        );

        verify(elasticsearchService, times(2)).bulkIndexPaperSearch(any());
        verify(elasticsearchService, times(3)).bulkIndex(any());
    }

    @Test
    void importsJsonlCorpusFromPathWithoutPreloadingWholeDataset() throws Exception {
        when(paperRepository.save(any(Paper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paperTextChunkRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Path corpus = tempDir.resolve("litsearch-corpus.jsonl");
        Files.write(corpus, List.of(
                OBJECT_MAPPER.writeValueAsString(paper("p1")),
                OBJECT_MAPPER.writeValueAsString(paper("p2"))
        ));
        LitSearchPaperLoomImporter importer = new LitSearchPaperLoomImporter(
                paperRepository,
                paperTextChunkRepository,
                elasticsearchService
        );

        LitSearchPaperLoomImporter.ImportSummary summary = importer.importJsonl(
                corpus,
                new LitSearchPaperLoomImporter.Options("eval-user", "eval-litsearch", true, 80, "full")
        );

        assertEquals(2, summary.paperCount());
        assertEquals(4, summary.chunkCount());
        verify(paperRepository, times(2)).save(any(Paper.class));
        verify(paperTextChunkRepository, times(2)).saveAll(any());
    }

    @Test
    void importsOnlyRequestedJsonlWindow() throws Exception {
        when(paperRepository.save(any(Paper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paperTextChunkRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Path corpus = tempDir.resolve("litsearch-corpus-window.jsonl");
        Files.write(corpus, List.of(
                OBJECT_MAPPER.writeValueAsString(paper("p1")),
                OBJECT_MAPPER.writeValueAsString(paper("p2")),
                OBJECT_MAPPER.writeValueAsString(paper("p3"))
        ));
        LitSearchPaperLoomImporter importer = new LitSearchPaperLoomImporter(
                paperRepository,
                paperTextChunkRepository,
                elasticsearchService
        );

        LitSearchPaperLoomImporter.ImportSummary summary = importer.importJsonl(
                corpus,
                new LitSearchPaperLoomImporter.Options("eval-user", "eval-litsearch", true, 80, "full"),
                1,
                1
        );

        assertEquals(1, summary.paperCount());
        ArgumentCaptor<Paper> paperCaptor = ArgumentCaptor.forClass(Paper.class);
        verify(paperRepository).save(paperCaptor.capture());
        assertEquals("litsearch:p2", paperCaptor.getValue().getPaperId());
    }

    @Test
    void truncatesDatabaseTitleWhileKeepingFullTitleInSearchDocument() {
        when(paperRepository.save(any(Paper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paperTextChunkRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LitSearchPaperLoomImporter importer = new LitSearchPaperLoomImporter(
                paperRepository,
                paperTextChunkRepository,
                elasticsearchService
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
                new LitSearchPaperLoomImporter.Options("eval-user", "eval-litsearch", true, 80, "service-slice")
        );

        ArgumentCaptor<Paper> paperCaptor = ArgumentCaptor.forClass(Paper.class);
        verify(paperRepository).save(paperCaptor.capture());
        assertEquals(255, paperCaptor.getValue().getPaperTitle().length());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaperSearchDocument>> paperDocumentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(elasticsearchService).bulkIndexPaperSearch(paperDocumentsCaptor.capture());
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
