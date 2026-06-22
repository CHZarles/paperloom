package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;

import java.sql.Blob;

/**
 * 论文文本 chunk 实体。
 * 存储 PDF 解析后的 chunk 文本、页码和证据锚点。
 */
@Data
@Entity
@Table(name = "paper_text_chunks")
public class PaperTextChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long vectorId;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(nullable = false)
    private Integer chunkId;

    @Lob
    private String textContent;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "anchor_text", length = 512)
    private String anchorText;

    @Column(name = "element_type", length = 64)
    private String elementType;

    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    @Column(name = "section_level")
    private Integer sectionLevel;

    @Lob
    @Column(name = "bbox_json", columnDefinition = "TEXT")
    private String bboxJson;

    @Column(name = "parser_name", length = 64)
    private String parserName;

    @Column(name = "parser_version", length = 64)
    private String parserVersion;

    @Lob
    @Column(name = "raw_provenance_json", columnDefinition = "TEXT")
    private String rawProvenanceJson;

    @Column(name = "source_kind", length = 32)
    private String sourceKind = "TEXT";

    @Column(name = "table_id", length = 64)
    private String tableId;

    @Column(name = "figure_id", length = 64)
    private String figureId;

    @Column(name = "formula_id", length = 64)
    private String formulaId;

    @Column(name = "evidence_role", length = 64)
    private String evidenceRole;

    @Column(length = 32)
    private String modelVersion;
    
    /**
     * 上传用户ID
     */
    @Column(nullable = false, name = "user_id", length = 64)
    private String userId;
    
    /**
     * 论文所属组织标签
     */
    @Column(name = "org_tag", length = 50)
    private String orgTag;
    
    /**
     * 论文是否公开
     */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;
}
