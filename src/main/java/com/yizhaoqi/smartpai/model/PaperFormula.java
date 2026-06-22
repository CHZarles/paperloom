package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_formulas",
        indexes = {
                @Index(name = "idx_paper_formulas_paper", columnList = "paper_id"),
                @Index(name = "idx_paper_formulas_formula", columnList = "formula_id")
        })
public class PaperFormula {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "formula_id", nullable = false, length = 64)
    private String formulaId;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Lob
    @Column(name = "latex", columnDefinition = "TEXT")
    private String latex;

    @Lob
    @Column(name = "context_text", columnDefinition = "TEXT")
    private String contextText;

    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    @Lob
    @Column(name = "bbox_json", columnDefinition = "TEXT")
    private String bboxJson;

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
