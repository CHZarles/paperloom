package com.yizhaoqi.smartpai.entity;

import lombok.Data;

@Data
public class SearchResult {
    private String paperId;    // 论文标识，当前使用内容哈希生成
    private Integer chunkId;   // 论文文本分块序号
    private String textContent; // 文本内容
    private Double score;      // 搜索得分
    private String paperTitle; // 论文标题，当前使用上传 PDF 文件名
    private String userId;     // 上传用户ID
    private String orgTag;     // 组织标签
    private Boolean isPublic;  // 是否公开
    private Integer pageNumber; // PDF 页码
    private String anchorText; // 页内定位锚点
    private String retrievalMode; // 召回方式
    private String matchedChunkText; // 命中的 chunk 原文

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score) {
        this(paperId, chunkId, textContent, score, null, null, false, null, null, null, null, null);
    }

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score, String paperTitle) {
        this(paperId, chunkId, textContent, score, null, null, false, paperTitle, null, null, null, null);
    }

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic) {
        this(paperId, chunkId, textContent, score, userId, orgTag, isPublic, null, null, null, null, null);
    }

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic, String paperTitle) {
        this(paperId, chunkId, textContent, score, userId, orgTag, isPublic, paperTitle, null, null, null, null);
    }

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String paperTitle, Integer pageNumber, String anchorText) {
        this(paperId, chunkId, textContent, score, userId, orgTag, isPublic, paperTitle, pageNumber, anchorText, null, textContent);
    }

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String paperTitle, Integer pageNumber, String anchorText,
                        String retrievalMode, String matchedChunkText) {
        this.paperId = paperId;
        this.chunkId = chunkId;
        this.textContent = textContent;
        this.score = score;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.paperTitle = paperTitle;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.retrievalMode = retrievalMode;
        this.matchedChunkText = matchedChunkText != null ? matchedChunkText : textContent;
    }
}
