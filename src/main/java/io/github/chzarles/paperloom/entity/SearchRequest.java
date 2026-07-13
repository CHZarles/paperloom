package io.github.chzarles.paperloom.entity;

import lombok.Data;

@Data
public class SearchRequest {
    private String query; // 搜索关键词
    private int pageBatchSize; // ES 技术分页批次
}
