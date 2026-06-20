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
