package io.github.chzarles.paperloom.entity;

import lombok.Data;

import java.util.List;

@Data
public class SearchResult {
    private String paperId;    // 论文标识，当前使用内容哈希生成
    private Integer chunkId;   // 论文文本分块序号
    private String textContent; // 文本内容
    private Double score;      // 搜索得分
    private String paperTitle; // 论文标题，当前使用上传 PDF 文件名
    private String originalFilename; // 上传时的原始 PDF 文件名
    private String userId;     // 上传用户ID
    private String orgTag;     // 组织标签
    private Boolean isPublic;  // 是否公开
    private Integer pageNumber; // PDF 页码
    private String anchorText; // 页内定位锚点
    private String elementType;
    private String sectionTitle;
    private Integer sectionLevel;
    private String bboxJson;
    private String parserName;
    private String parserVersion;
    private String retrievalMode; // 召回方式
    private String matchedChunkText; // 命中的 chunk 原文
    private String sourceKind;
    private String tableId;
    private String figureId;
    private String formulaId;
    private String evidenceRole;
    private String retrievalQuery;
    private String originalQuery;
    private String retrievalRoute;
    private String intent;
    private String rankReason;
    private String tableText;
    private String tableMarkdown;
    private Boolean tableScreenshotAvailable;
    private String sourceType;
    private String evidenceAssetLevel;
    private Boolean pdfEvidenceAvailable;
    private Boolean pageScreenshotAvailable;
    private Boolean figureScreenshotAvailable;
    private List<String> assetWarnings;

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score) {
        this(paperId, chunkId, textContent, score, null, null, false, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score, String paperTitle) {
        this(paperId, chunkId, textContent, score, null, null, false, paperTitle, null, null, null, null,
                null, null, null, null, null, null);
    }

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic) {
        this(paperId, chunkId, textContent, score, userId, orgTag, isPublic, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic, String paperTitle) {
        this(paperId, chunkId, textContent, score, userId, orgTag, isPublic, paperTitle, null, null, null, null,
                null, null, null, null, null, null);
    }

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String paperTitle, Integer pageNumber, String anchorText) {
        this(paperId, chunkId, textContent, score, userId, orgTag, isPublic, paperTitle, pageNumber, anchorText, null, textContent,
                null, null, null, null, null, null);
    }

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String paperTitle, Integer pageNumber, String anchorText,
                        String retrievalMode, String matchedChunkText) {
        this(paperId, chunkId, textContent, score, userId, orgTag, isPublic, paperTitle, pageNumber, anchorText,
                retrievalMode, matchedChunkText, null, null, null, null, null, null);
    }

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String paperTitle, Integer pageNumber, String anchorText,
                        String retrievalMode, String matchedChunkText,
                        String elementType, String sectionTitle, Integer sectionLevel,
                        String bboxJson, String parserName, String parserVersion) {
        this(paperId, chunkId, textContent, score, userId, orgTag, isPublic, paperTitle, null, pageNumber, anchorText,
                retrievalMode, matchedChunkText, elementType, sectionTitle, sectionLevel, bboxJson, parserName,
                parserVersion);
    }

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String paperTitle, Integer pageNumber, String anchorText,
                        String retrievalMode, String matchedChunkText,
                        String elementType, String sectionTitle, Integer sectionLevel,
                        String bboxJson, String parserName, String parserVersion,
                        String sourceKind, String tableId, String tableText, String tableMarkdown,
                        Boolean tableScreenshotAvailable) {
        this(paperId, chunkId, textContent, score, userId, orgTag, isPublic, paperTitle, null, pageNumber, anchorText,
                retrievalMode, matchedChunkText, elementType, sectionTitle, sectionLevel, bboxJson, parserName,
                parserVersion, sourceKind, tableId, tableText, tableMarkdown, tableScreenshotAvailable);
    }

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String paperTitle, String originalFilename,
                        Integer pageNumber, String anchorText, String retrievalMode, String matchedChunkText,
                        String elementType, String sectionTitle, Integer sectionLevel,
                        String bboxJson, String parserName, String parserVersion) {
        this(paperId, chunkId, textContent, score, userId, orgTag, isPublic, paperTitle, originalFilename,
                pageNumber, anchorText, retrievalMode, matchedChunkText, elementType, sectionTitle, sectionLevel,
                bboxJson, parserName, parserVersion, "TEXT", null, null, null, false);
    }

    public SearchResult(String paperId, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String paperTitle, String originalFilename,
                        Integer pageNumber, String anchorText, String retrievalMode, String matchedChunkText,
                        String elementType, String sectionTitle, Integer sectionLevel,
                        String bboxJson, String parserName, String parserVersion,
                        String sourceKind, String tableId, String tableText, String tableMarkdown,
                        Boolean tableScreenshotAvailable) {
        this.paperId = paperId;
        this.chunkId = chunkId;
        this.textContent = textContent;
        this.score = score;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.paperTitle = paperTitle;
        this.originalFilename = originalFilename;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.retrievalMode = retrievalMode;
        this.matchedChunkText = matchedChunkText != null ? matchedChunkText : textContent;
        this.elementType = elementType;
        this.sectionTitle = sectionTitle;
        this.sectionLevel = sectionLevel;
        this.bboxJson = bboxJson;
        this.parserName = parserName;
        this.parserVersion = parserVersion;
        this.sourceKind = sourceKind;
        this.tableId = tableId;
        this.tableText = tableText;
        this.tableMarkdown = tableMarkdown;
        this.tableScreenshotAvailable = tableScreenshotAvailable;
    }
}
