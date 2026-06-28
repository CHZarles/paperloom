package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.entity.PaperChunkDocument;
import com.yizhaoqi.smartpai.entity.PaperSearchDocument;
import com.yizhaoqi.smartpai.eval.model.EvalChunk;
import com.yizhaoqi.smartpai.eval.model.EvalPaper;
import com.yizhaoqi.smartpai.eval.repository.EvalChunkRepository;
import com.yizhaoqi.smartpai.eval.repository.EvalPaperRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QasperPaperLoomImporterTest {

    @Mock
    private EvalPaperRepository evalPaperRepository;

    @Mock
    private EvalChunkRepository evalChunkRepository;

    @Mock
    private EvalCorpusIndexService evalCorpusIndexService;

    @Test
    void importsQasperChunksIntoEvalSchemaAndEvalSearchIndices() {
        when(evalPaperRepository.save(any(EvalPaper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(evalChunkRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        QasperPaperLoomImporter importer = new QasperPaperLoomImporter(
                evalPaperRepository,
                evalChunkRepository,
                evalCorpusIndexService
        );

        QasperPaperLoomImporter.ImportSummary summary = importer.importChunks(
                List.of(
                        new PaperPageChunk(
                                "1912.01214",
                                "Cross-lingual Pre-training",
                                "qasper:1912.01214.json",
                                1,
                                1,
                                "Abstract",
                                "TEXT",
                                null,
                                null,
                                "This paper studies zero-shot neural machine translation."
                        ),
                        new PaperPageChunk(
                                "1912.01214",
                                "Cross-lingual Pre-training",
                                "qasper:1912.01214.json",
                                2,
                                2,
                                "Experiments",
                                "TEXT",
                                null,
                                null,
                                "We compare pivoting, multilingual NMT, and cross-lingual transfer."
                        )
                ),
                new QasperPaperLoomImporter.Options("dev")
        );

        assertEquals(1, summary.paperCount());
        assertEquals(2, summary.chunkCount());

        ArgumentCaptor<EvalPaper> paperCaptor = ArgumentCaptor.forClass(EvalPaper.class);
        verify(evalPaperRepository).save(paperCaptor.capture());
        EvalPaper savedPaper = paperCaptor.getValue();
        assertEquals("qasper", savedPaper.getCorpus());
        assertEquals("dev", savedPaper.getSplit());
        assertEquals("1912.01214", savedPaper.getExternalPaperId());
        assertEquals("qasper:1912.01214", savedPaper.getPaperId());
        assertEquals("qasper:1912.01214.json", savedPaper.getSourceJson());
        assertEquals("Cross-lingual Pre-training", savedPaper.getTitle());
        assertTrue(savedPaper.getAbstractText().contains("zero-shot neural machine translation"));
        assertEquals("1912.01214", savedPaper.getArxivId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(evalChunkRepository).saveAll(chunksCaptor.capture());
        List<EvalChunk> chunks = chunksCaptor.getValue();
        assertEquals(2, chunks.size());
        assertEquals("qasper", chunks.get(0).getCorpus());
        assertEquals("dev", chunks.get(0).getSplit());
        assertEquals("qasper:1912.01214", chunks.get(0).getPaperId());
        assertEquals(1, chunks.get(0).getPageNumber());
        assertEquals(1, chunks.get(0).getChunkId());
        assertEquals("Abstract", chunks.get(0).getSectionTitle());
        assertEquals("PAPER_METADATA", chunks.get(0).getEvidenceRole());
        assertEquals("qasper:1912.01214", chunks.get(1).getPaperId());
        assertEquals("Experiments", chunks.get(1).getSectionTitle());
        assertEquals("FULL_TEXT", chunks.get(1).getEvidenceRole());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaperSearchDocument>> paperDocumentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(evalCorpusIndexService).bulkIndexPaperSearch(eq("qasper"), paperDocumentsCaptor.capture());
        PaperSearchDocument paperSearchDocument = paperDocumentsCaptor.getValue().get(0);
        assertEquals("qasper:1912.01214", paperSearchDocument.getPaperId());
        assertEquals("Cross-lingual Pre-training", paperSearchDocument.getPaperTitle());
        assertTrue(paperSearchDocument.getSearchText().contains("arxiv: 1912.01214"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaperChunkDocument>> chunkDocumentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(evalCorpusIndexService).bulkIndexChunks(eq("qasper"), chunkDocumentsCaptor.capture());
        List<PaperChunkDocument> chunkDocuments = chunkDocumentsCaptor.getValue();
        assertEquals(2, chunkDocuments.size());
        assertEquals("qasper:1912.01214", chunkDocuments.get(0).getPaperId());
        assertEquals(2048, chunkDocuments.get(0).getVector().length);
        assertTrue(squaredMagnitude(chunkDocuments.get(0).getVector()) > 0.0f);
        assertTrue(chunkDocuments.get(0).getRetrievalTextContent().contains("title: Cross-lingual Pre-training"));
        assertTrue(chunkDocuments.get(1).getRetrievalTextContent().contains("section: Experiments"));
    }

    @Test
    void clearsExistingImportedQasperRowsBeforeImportingPaper() {
        when(evalPaperRepository.save(any(EvalPaper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(evalChunkRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        QasperPaperLoomImporter importer = new QasperPaperLoomImporter(
                evalPaperRepository,
                evalChunkRepository,
                evalCorpusIndexService
        );

        importer.importChunks(
                List.of(new PaperPageChunk(
                        "1912.01214",
                        "Cross-lingual Pre-training",
                        "qasper:1912.01214.json",
                        1,
                        1,
                        "Abstract",
                        "TEXT",
                        null,
                        null,
                        "This paper studies zero-shot neural machine translation."
                )),
                new QasperPaperLoomImporter.Options("dev")
        );

        var inOrder = inOrder(evalCorpusIndexService, evalChunkRepository, evalPaperRepository);
        inOrder.verify(evalCorpusIndexService).deleteByPaperId("qasper", "qasper:1912.01214");
        inOrder.verify(evalChunkRepository).deleteByCorpusAndPaperId("qasper", "qasper:1912.01214");
        inOrder.verify(evalPaperRepository).deleteByCorpusAndPaperId("qasper", "qasper:1912.01214");
        inOrder.verify(evalPaperRepository).save(any(EvalPaper.class));
    }

    @Test
    void rewritesBenchmarkCasesToImportedQasperPaperIds() {
        RagBenchmarkCase sourceCase = new RagBenchmarkCase(
                "q1",
                "which baselines?",
                "en",
                "QASPER_EVIDENCE_QA",
                "MANUAL_SOURCE",
                new RagBenchmarkCase.Scope(
                        List.of("1912.01214"),
                        List.of("Cross-lingual Pre-training")
                ),
                "MANUAL_SOURCE_QA",
                List.of("pivoting"),
                List.of("multilingual NMT"),
                List.of(),
                List.of(),
                List.of("1912.01214"),
                true
        );

        List<RagBenchmarkCase> rewritten = QasperPaperLoomImporter.rewriteCasesToImportedPaperIds(
                List.of(sourceCase),
                QasperPaperLoomImporter.Options.defaults()
        );

        assertEquals(1, rewritten.size());
        assertEquals(List.of("qasper:1912.01214"), rewritten.get(0).scope().paperIds());
        assertEquals(List.of("qasper:1912.01214"), rewritten.get(0).expectedPaperIds());
        assertEquals(List.of("Cross-lingual Pre-training"), rewritten.get(0).scope().paperTitles());
    }

    private static float squaredMagnitude(float[] vector) {
        float total = 0.0f;
        for (float value : vector) {
            total += value * value;
        }
        return total;
    }
}
