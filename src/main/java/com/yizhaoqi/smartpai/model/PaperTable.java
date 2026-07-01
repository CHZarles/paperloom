package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_tables",
        indexes = {
                @Index(name = "idx_paper_tables_paper", columnList = "paper_id"),
                @Index(name = "idx_paper_tables_table", columnList = "table_id")
        })
public class PaperTable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "table_id", nullable = false, length = 64)
    private String tableId;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Lob
    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "column_count")
    private Integer columnCount;

    @Lob
    @Column(name = "table_text", columnDefinition = "TEXT")
    private String tableText;

    @Lob
    @Column(name = "table_markdown", columnDefinition = "TEXT")
    private String tableMarkdown;

    @Lob
    @Column(name = "bbox_json", columnDefinition = "TEXT")
    private String bboxJson;

    @Column(name = "screenshot_object_key", length = 500)
    private String screenshotObjectKey;

    @Column(name = "parser_name", length = 64)
    private String parserName;

    @Column(name = "parser_version", length = 64)
    private String parserVersion;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "org_tag", length = 50)
    private String orgTag;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
