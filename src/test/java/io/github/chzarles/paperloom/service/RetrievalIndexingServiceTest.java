package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.repository.PaperRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalIndexingServiceTest {

    @Test
    void reportsLexicalIndexMetricsAndUpdatesPipelineStatus() {
        ReadingModelQdrantIndexService indexService = mock(ReadingModelQdrantIndexService.class);
        PaperRepository paperRepository = mock(PaperRepository.class);
        Paper paper = new Paper();
        paper.setPaperId("paper-1");
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-1"))
                .thenReturn(Optional.of(paper));
        when(indexService.indexCurrentModel(eq("paper-1"), eq("user-1"), any()))
                .thenReturn(new ReadingModelQdrantIndexService.IndexResult(
                        120,
                        8,
                        0,
                        "lexical-contract",
                        "model-v1"
                ));

        RetrievalIndexingService.IndexingResult result =
                new RetrievalIndexingService(indexService, paperRepository)
                        .indexWithMetrics("paper-1", "user-1");

        assertEquals(120, result.indexedTokenCount());
        assertEquals(8, result.indexedLocationCount());
        assertEquals("lexical-contract", result.retrievalIndexContract());
        assertEquals(Paper.VECTORIZATION_STATUS_INDEXING, paper.getVectorizationStatus());
        verify(paperRepository).save(paper);
    }

    @Test
    void rejectsReadingModelWithoutIndexableLocations() {
        ReadingModelQdrantIndexService indexService = mock(ReadingModelQdrantIndexService.class);
        PaperRepository paperRepository = mock(PaperRepository.class);
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-1"))
                .thenReturn(Optional.empty());
        when(indexService.indexCurrentModel(eq("paper-1"), eq("user-1"), any()))
                .thenReturn(new ReadingModelQdrantIndexService.IndexResult(
                        0,
                        0,
                        0,
                        "lexical-contract",
                        "model-v1"
                ));

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> new RetrievalIndexingService(indexService, paperRepository)
                        .indexWithMetrics("paper-1", "user-1")
        );

        assertEquals(true, error.getMessage().contains("no indexable locations"));
    }
}
