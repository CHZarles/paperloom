package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.yizhaoqi.smartpai.config.PaperSearchIndex;
import com.yizhaoqi.smartpai.entity.PaperChunkDocument;
import com.yizhaoqi.smartpai.entity.PaperSearchDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

// Elasticsearch操作封装服务
@Service
public class ElasticsearchService {

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    /**
     * 批量索引论文 chunk 到 Elasticsearch。
     */
    public void bulkIndex(List<PaperChunkDocument> documents) {
        try {
            logger.info("开始批量索引论文 chunk 到 Elasticsearch，数量: {}", documents.size());

            // 将文档列表转换为批量操作列表，每个文档都对应一个索引操作
            List<BulkOperation> bulkOperations = documents.stream()
                    .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                            .index(PaperSearchIndex.INDEX_NAME)
                            .id(doc.getId())
                            .document(doc)
                    )))
                    .toList();

            // 创建BulkRequest对象，并将批量操作列表添加到请求中
            BulkRequest request = BulkRequest.of(b -> b.operations(bulkOperations));

            // 执行批量索引操作
            BulkResponse response = esClient.bulk(request);

            // 检查响应结果
            if (response.errors()) {
                logger.error("批量索引过程中发生错误:");
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        logger.error("文档索引失败 - ID: {}, 错误: {}", item.id(), item.error().reason());
                    }
                }
                throw new RuntimeException("批量索引部分失败，请检查日志");
            } else {
                logger.info("批量索引成功完成，论文 chunk 数量: {}", documents.size());
            }
        } catch (Exception e) {
            logger.error("批量索引失败，论文 chunk 数量: {}", documents.size(), e);
            throw new RuntimeException("批量索引失败", e);
        }
    }

    /**
     * 批量索引论文元数据到 paper_search，用于文献搜索候选召回。
     */
    public void bulkIndexPaperSearch(List<PaperSearchDocument> documents) {
        try {
            logger.info("开始批量索引论文元数据到 Elasticsearch，数量: {}", documents.size());
            List<BulkOperation> bulkOperations = documents.stream()
                    .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                            .index(PaperSearchIndex.PAPER_INDEX_NAME)
                            .id(paperSearchDocumentId(doc))
                            .document(doc)
                    )))
                    .toList();

            BulkRequest request = BulkRequest.of(b -> b.operations(bulkOperations));
            BulkResponse response = esClient.bulk(request);
            if (response.errors()) {
                logger.error("批量索引论文元数据过程中发生错误:");
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        logger.error("论文元数据索引失败 - ID: {}, 错误: {}", item.id(), item.error().reason());
                    }
                }
                throw new RuntimeException("批量索引论文元数据部分失败，请检查日志");
            }
            logger.info("批量索引论文元数据成功完成，数量: {}", documents.size());
        } catch (Exception e) {
            logger.error("批量索引论文元数据失败，数量: {}", documents.size(), e);
            throw new RuntimeException("批量索引论文元数据失败", e);
        }
    }

    /**
     * 根据 paperId 删除论文 chunk。
     */
    public void deleteByPaperId(String paperId) {
        try {
            deleteByPaperIdFromIndex(PaperSearchIndex.INDEX_NAME, paperId);
            deleteByPaperIdFromIndex(PaperSearchIndex.PAPER_INDEX_NAME, paperId);
        } catch (Exception e) {
            throw new RuntimeException("删除论文搜索索引失败", e);
        }
    }

    public long countByPaperId(String paperId) {
        try {
            CountResponse response = esClient.count(c -> c
                    .index(PaperSearchIndex.INDEX_NAME)
                    .query(q -> q.term(t -> t.field("paperId").value(paperId)))
            );
            return response.count();
        } catch (Exception e) {
            throw new RuntimeException("统计论文 chunk 失败", e);
        }
    }

    private void deleteByPaperIdFromIndex(String indexName, String paperId) throws Exception {
        DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                .index(indexName)
                .query(q -> q.term(t -> t.field("paperId").value(paperId)))
        );
        esClient.deleteByQuery(request);
    }

    private String paperSearchDocumentId(PaperSearchDocument document) {
        if (document.getId() != null && !document.getId().isBlank()) {
            return document.getId();
        }
        return document.getPaperId();
    }
}
