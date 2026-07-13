package io.github.chzarles.paperloom.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_reading_models",
        indexes = {
                @Index(name = "idx_paper_reading_models_paper_current", columnList = "paper_id,is_current"),
                @Index(name = "idx_paper_reading_models_paper_version", columnList = "paper_id,model_version")
        })
public class PaperReadingModel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_status", nullable = false, length = 64)
    private PaperReadingModelStatus modelStatus;

    @Column(name = "is_current", nullable = false)
    private boolean isCurrent;

    @Column(name = "parser_name", length = 64)
    private String parserName;

    @Column(name = "parser_version", length = 64)
    private String parserVersion;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "readable_page_count")
    private Integer readablePageCount;

    @Column(name = "readable_char_count")
    private Integer readableCharCount;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Lob
    @Column(name = "diagnostics_json", columnDefinition = "TEXT")
    private String diagnosticsJson;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
