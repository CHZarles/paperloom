package io.github.chzarles.paperloom.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import io.github.chzarles.paperloom.config.PaperSearchIndex;
import io.github.chzarles.paperloom.entity.PaperSearchDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ElasticsearchServiceTest {

    @Mock
    private ElasticsearchClient esClient;

    @InjectMocks
    private ElasticsearchService elasticsearchService;

    @Test
    void bulkIndexPaperSearchWritesMetadataDocumentsToDedicatedIndex() throws Exception {
        BulkResponse bulkResponse = BulkResponse.of(response -> response
                .errors(false)
                .items(List.of())
                .took(1)
        );
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);

        PaperSearchDocument document = new PaperSearchDocument();
        document.setPaperId("paper-a");
        document.setPaperTitle("Paper Search");

        elasticsearchService.bulkIndexPaperSearch(List.of(document));

        ArgumentCaptor<BulkRequest> requestCaptor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(esClient).bulk(requestCaptor.capture());

        assertEquals(1, requestCaptor.getValue().operations().size());
        assertEquals(PaperSearchIndex.PAPER_INDEX_NAME, requestCaptor.getValue().operations().get(0).index().index());
        assertEquals("paper-a", requestCaptor.getValue().operations().get(0).index().id());
    }

    @Test
    void deleteByPaperIdRemovesChunkAndPaperMetadataDocuments() throws Exception {
        DeleteByQueryResponse response = DeleteByQueryResponse.of(delete -> delete
                .deleted(1L)
                .total(1L)
                .took(1L)
        );
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(response);

        elasticsearchService.deleteByPaperId("paper-a");

        ArgumentCaptor<DeleteByQueryRequest> requestCaptor = ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        verify(esClient, times(2)).deleteByQuery(requestCaptor.capture());

        List<String> targetIndices = requestCaptor.getAllValues().stream()
                .flatMap(request -> request.index().stream())
                .toList();
        assertTrue(targetIndices.contains(PaperSearchIndex.INDEX_NAME));
        assertTrue(targetIndices.contains(PaperSearchIndex.PAPER_INDEX_NAME));
    }
}
