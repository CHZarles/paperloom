package io.github.chzarles.paperloom.entity;

import lombok.Getter;
import lombok.Setter;

// 文件分块内容实体类
@Setter
@Getter
public class TextChunk {

    // Getters/Setters
    private int chunkId;       // 分块序号
    private String content;    // 分块内容
    private Integer pageNumber; // PDF 页码
    private String anchorText; // 页内定位锚点
    private String elementType;
    private String sectionTitle;
    private Integer sectionLevel;
    private String bboxJson;
    private String parserName;
    private String parserVersion;
    private String sourceKind;
    private String tableId;
    private String figureId;
    private String formulaId;
    private String evidenceRole;

    // 构造方法
    public TextChunk(int chunkId, String content) {
        this(chunkId, content, null, null);
    }

    public TextChunk(int chunkId, String content, Integer pageNumber, String anchorText) {
        this(chunkId, content, pageNumber, anchorText, null, null, null, null, null, null);
    }

    public TextChunk(int chunkId, String content, Integer pageNumber, String anchorText,
                     String elementType, String sectionTitle, Integer sectionLevel,
                     String bboxJson, String parserName, String parserVersion) {
        this(chunkId, content, pageNumber, anchorText, elementType, sectionTitle, sectionLevel,
                bboxJson, parserName, parserVersion, "TEXT", null);
    }

    public TextChunk(int chunkId, String content, Integer pageNumber, String anchorText,
                     String elementType, String sectionTitle, Integer sectionLevel,
                     String bboxJson, String parserName, String parserVersion,
                     String sourceKind, String tableId) {
        this(chunkId, content, pageNumber, anchorText, elementType, sectionTitle, sectionLevel,
                bboxJson, parserName, parserVersion, sourceKind, tableId, null, null, "NORMAL_TEXT");
    }

    public TextChunk(int chunkId, String content, Integer pageNumber, String anchorText,
                     String elementType, String sectionTitle, Integer sectionLevel,
                     String bboxJson, String parserName, String parserVersion,
                     String sourceKind, String tableId, String figureId, String formulaId, String evidenceRole) {
        this.chunkId = chunkId;
        this.content = content;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.elementType = elementType;
        this.sectionTitle = sectionTitle;
        this.sectionLevel = sectionLevel;
        this.bboxJson = bboxJson;
        this.parserName = parserName;
        this.parserVersion = parserVersion;
        this.sourceKind = sourceKind;
        this.tableId = tableId;
        this.figureId = figureId;
        this.formulaId = formulaId;
        this.evidenceRole = evidenceRole;
    }
}
