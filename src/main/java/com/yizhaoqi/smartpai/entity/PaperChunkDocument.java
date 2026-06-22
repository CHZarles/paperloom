package com.yizhaoqi.smartpai.entity;

import lombok.Data;

/**
 * Elasticsearch 中的论文 chunk 文档。
 */
@Data
public class PaperChunkDocument {

    private String id;
    private String paperId;
    private Integer chunkId;
    private String textContent;
    private Integer pageNumber;
    private String anchorText;
    private String elementType;
    private String sectionTitle;
    private Integer sectionLevel;
    private String bboxJson;
    private String parserName;
    private String parserVersion;
    private float[] vector;
    private String modelVersion;
    private String userId;
    private String orgTag;
    private boolean isPublic;

    public PaperChunkDocument() {
    }

    public PaperChunkDocument(String id, String paperId, int chunkId, String content,
                              Integer pageNumber, String anchorText,
                              String elementType, String sectionTitle, Integer sectionLevel,
                              String bboxJson, String parserName, String parserVersion,
                              float[] vector, String modelVersion,
                              String userId, String orgTag, boolean isPublic) {
        this.id = id;
        this.paperId = paperId;
        this.chunkId = chunkId;
        this.textContent = content;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.elementType = elementType;
        this.sectionTitle = sectionTitle;
        this.sectionLevel = sectionLevel;
        this.bboxJson = bboxJson;
        this.parserName = parserName;
        this.parserVersion = parserVersion;
        this.vector = vector;
        this.modelVersion = modelVersion;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
    }
}
