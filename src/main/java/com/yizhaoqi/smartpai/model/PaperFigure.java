package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_figures",
        indexes = {
                @Index(name = "idx_paper_figures_paper", columnList = "paper_id"),
                @Index(name = "idx_paper_figures_figure", columnList = "figure_id")
        })
public class PaperFigure {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "figure_id", nullable = false, length = 64)
    private String figureId;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "caption", length = 1000)
    private String caption;

    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    @Lob
    @Column(name = "figure_text", columnDefinition = "TEXT")
    private String figureText;

    @Lob
    @Column(name = "bbox_json", columnDefinition = "TEXT")
    private String bboxJson;

    @Column(name = "detection_source", length = 64)
    private String detectionSource;

    @Column(name = "confidence", length = 32)
    private String confidence;

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
