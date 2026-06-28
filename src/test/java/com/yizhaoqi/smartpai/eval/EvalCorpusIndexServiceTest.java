package com.yizhaoqi.smartpai.eval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import com.yizhaoqi.smartpai.entity.PaperChunkDocument;
import com.yizhaoqi.smartpai.entity.PaperSearchDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class EvalCorpusIndexServiceTest {

    @Mock
    private ElasticsearchClient esClient;

    @Test
    void bulkIndexPaperSearchWritesOnlyToEvalCorpusPaperIndex() throws Exception {
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(successfulBulkResponse());
        EvalCorpusIndexService indexService = new EvalCorpusIndexService(esClient);
        PaperSearchDocument document = new PaperSearchDocument();
        document.setPaperId("litsearch:paper-1");

        indexService.bulkIndexPaperSearch("litsearch", List.of(document));

        ArgumentCaptor<BulkRequest> requestCaptor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(esClient).bulk(requestCaptor.capture());
        assertEquals("eval_litsearch_paper_search", requestCaptor.getValue().operations().get(0).index().index());
        assertEquals("litsearch:paper-1", requestCaptor.getValue().operations().get(0).index().id());
    }

    @Test
    void bulkIndexChunksWritesOnlyToEvalCorpusChunkIndex() throws Exception {
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(successfulBulkResponse());
        EvalCorpusIndexService indexService = new EvalCorpusIndexService(esClient);
        PaperChunkDocument document = new PaperChunkDocument();
        document.setId("chunk-doc-1");
        document.setPaperId("qasper:1912.01214");
        document.setChunkId(1);

        indexService.bulkIndexChunks("qasper", List.of(document));

        ArgumentCaptor<BulkRequest> requestCaptor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(esClient).bulk(requestCaptor.capture());
        assertEquals("eval_qasper_chunks", requestCaptor.getValue().operations().get(0).index().index());
        assertEquals("chunk-doc-1", requestCaptor.getValue().operations().get(0).index().id());
    }

    @Test
    void deleteByPaperIdRemovesOnlyEvalCorpusDocuments() throws Exception {
        DeleteByQueryResponse response = DeleteByQueryResponse.of(delete -> delete
                .deleted(1L)
                .total(1L)
                .took(1L)
        );
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(response);
        EvalCorpusIndexService indexService = new EvalCorpusIndexService(esClient);

        indexService.deleteByPaperId("litsearch", "litsearch:paper-1");

        ArgumentCaptor<DeleteByQueryRequest> requestCaptor = ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        verify(esClient, times(2)).deleteByQuery(requestCaptor.capture());
        List<String> targetIndices = requestCaptor.getAllValues().stream()
                .flatMap(request -> request.index().stream())
                .toList();
        assertTrue(targetIndices.contains("eval_litsearch_paper_search"));
        assertTrue(targetIndices.contains("eval_litsearch_chunks"));
    }

    private BulkResponse successfulBulkResponse() {
        return BulkResponse.of(response -> response
                .errors(false)
                .items(List.of())
                .took(1)
        );
    }
}
