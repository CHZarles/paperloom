package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QdrantReadingLocationRetrieverTest {

    @Test
    void performsOneLexicalSearchAndUsesHintsOnlyAsDeterministicTieBreaks() {
        QdrantClient qdrant = mock(QdrantClient.class);
        Map<String, Object> filter = Map.of("scope", "active-models");
        when(qdrant.filter(Map.of("paper-a", "rm-1"), 2, 4)).thenReturn(filter);
        when(qdrant.searchLexical(any(), eq(filter), eq(100))).thenReturn(List.of(
                hit("paragraph-ref", "paragraph", 0.8),
                hit("table-ref", "table", 0.8),
                hit("best-ref", "paragraph", 0.9)
        ));
        when(qdrant.indexVersion()).thenReturn("lexical-index-v1");
        QdrantReadingLocationRetriever retriever = new QdrantReadingLocationRetriever(qdrant);

        ReadingLocationRetriever.RetrievalCandidates result = retriever.retrieve(
                new ReadingLocationRetriever.LocationRetrievalRequest(
                        Map.of("paper-a", "rm-1"),
                        "Alpha alpha",
                        "Beta",
                        Set.of("table"),
                        2,
                        4,
                        5
                ));

        assertEquals(List.of("best-ref", "table-ref", "paragraph-ref"), result.ranked().stream()
                .map(ReadingLocationRetriever.RankedLocationCandidate::locationRef).toList());
        assertEquals("lexical-index-v1", result.indexVersion());
        ArgumentCaptor<QdrantSparseVector> query = ArgumentCaptor.forClass(QdrantSparseVector.class);
        verify(qdrant).searchLexical(query.capture(), eq(filter), eq(100));
        assertEquals(List.of(1.0f, 1.0f), query.getValue().values());
        verify(qdrant).verifyCollection();
    }

    private QdrantSearchHit hit(String locationRef, String elementType, double score) {
        return new QdrantSearchHit(
                locationRef,
                score,
                Map.of(
                        "paper_id", "paper-a",
                        "model_version", "rm-1",
                        "location_ref", locationRef,
                        "element_types", List.of(elementType)
                )
        );
    }
}
