package com.yizhaoqi.smartpai.eval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.yizhaoqi.smartpai.entity.PaperChunkDocument;
import com.yizhaoqi.smartpai.entity.PaperSearchDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class EvalCorpusIndexService {

    private static final Logger logger = LoggerFactory.getLogger(EvalCorpusIndexService.class);

    private final ElasticsearchClient esClient;

    public EvalCorpusIndexService() {
        this.esClient = null;
    }

    @Autowired
    public EvalCorpusIndexService(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    public EvalIndices indicesFor(String corpus) {
        String normalizedCorpus = normalizeCorpus(corpus);
        return new EvalIndices(
                "eval_" + normalizedCorpus + "_paper_search",
                "eval_" + normalizedCorpus + "_chunks"
        );
    }

    private String normalizeCorpus(String corpus) {
        if (corpus == null || corpus.isBlank()) {
            throw new IllegalArgumentException("corpus is required");
        }
        String normalized = corpus.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("corpus must contain only lowercase letters, numbers, or underscores");
        }
        return normalized;
    }

    public void bulkIndexPaperSearch(String corpus, List<PaperSearchDocument> documents) {
        List<PaperSearchDocument> safeDocuments = documents == null ? List.of() : documents;
        if (safeDocuments.isEmpty()) {
            return;
        }
        EvalIndices indices = indicesFor(corpus);
        List<BulkOperation> operations = safeDocuments.stream()
                .map(document -> BulkOperation.of(op -> op.index(index -> index
                        .index(indices.paperSearchIndex())
                        .id(paperSearchDocumentId(document))
                        .document(document)
                )))
                .toList();
        executeBulk(indices.paperSearchIndex(), operations);
    }

    public void bulkIndexChunks(String corpus, List<PaperChunkDocument> documents) {
        List<PaperChunkDocument> safeDocuments = documents == null ? List.of() : documents;
        if (safeDocuments.isEmpty()) {
            return;
        }
        EvalIndices indices = indicesFor(corpus);
        List<BulkOperation> operations = safeDocuments.stream()
                .map(document -> BulkOperation.of(op -> op.index(index -> index
                        .index(indices.chunksIndex())
                        .id(chunkDocumentId(document))
                        .document(document)
                )))
                .toList();
        executeBulk(indices.chunksIndex(), operations);
    }

    public void deleteByPaperId(String corpus, String paperId) {
        if (paperId == null || paperId.isBlank()) {
            return;
        }
        EvalIndices indices = indicesFor(corpus);
        try {
            deleteByPaperIdFromIndex(indices.paperSearchIndex(), paperId);
            deleteByPaperIdFromIndex(indices.chunksIndex(), paperId);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to delete eval corpus search documents for paper " + paperId, exception);
        }
    }

    private void executeBulk(String indexName, List<BulkOperation> operations) {
        ElasticsearchClient client = requireClient();
        try {
            BulkResponse response = client.bulk(BulkRequest.of(request -> request.operations(operations)));
            if (response.errors()) {
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        logger.error("Eval corpus index failure - index: {}, id: {}, reason: {}",
                                indexName, item.id(), item.error().reason());
                    }
                }
                throw new RuntimeException("Eval corpus bulk index failed for " + indexName);
            }
        } catch (Exception exception) {
            throw new RuntimeException("Eval corpus bulk index failed for " + indexName, exception);
        }
    }

    private void deleteByPaperIdFromIndex(String indexName, String paperId) throws Exception {
        requireClient().deleteByQuery(DeleteByQueryRequest.of(request -> request
                .index(indexName)
                .query(query -> query.term(term -> term.field("paperId").value(paperId)))
        ));
    }

    private ElasticsearchClient requireClient() {
        if (esClient == null) {
            throw new IllegalStateException("ElasticsearchClient is required for eval corpus indexing");
        }
        return esClient;
    }

    private String paperSearchDocumentId(PaperSearchDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("paper search document is required");
        }
        if (document.getId() != null && !document.getId().isBlank()) {
            return document.getId();
        }
        return document.getPaperId();
    }

    private String chunkDocumentId(PaperChunkDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("chunk document is required");
        }
        if (document.getId() != null && !document.getId().isBlank()) {
            return document.getId();
        }
        return document.getPaperId() + ":" + document.getChunkId();
    }

    public record EvalIndices(String paperSearchIndex, String chunksIndex) {
    }
}
