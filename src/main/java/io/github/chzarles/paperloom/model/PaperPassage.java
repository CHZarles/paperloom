package io.github.chzarles.paperloom.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_passages",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_paper_passages_ref", columnNames = "passage_ref"),
                @UniqueConstraint(name = "uk_paper_passages_document_ordinal", columnNames = {
                        "paper_id", "model_version", "document_ordinal"
                })
        },
        indexes = {
                @Index(name = "idx_paper_passages_section_ordinal", columnList = "paper_id,model_version,parent_section_id,section_ordinal"),
                @Index(name = "idx_paper_passages_section_order", columnList = "paper_id,model_version,parent_section_id,reading_order_from"),
                @Index(name = "idx_paper_passages_page_range", columnList = "paper_id,model_version,page_number_from,page_number_to")
        })
public class PaperPassage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "passage_ref", nullable = false, length = 96)
    private String passageRef;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion;

    @Column(name = "parent_section_id", length = 96)
    private String parentSectionId;

    @Column(name = "parent_section_ref", length = 96)
    private String parentSectionRef;

    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    @Column(name = "page_number_from", nullable = false)
    private Integer pageNumberFrom;

    @Column(name = "page_number_to", nullable = false)
    private Integer pageNumberTo;

    @Column(name = "reading_order_from", nullable = false)
    private Integer readingOrderFrom;

    @Column(name = "reading_order_to", nullable = false)
    private Integer readingOrderTo;

    @Column(name = "document_ordinal", nullable = false)
    private Integer documentOrdinal;

    @Column(name = "section_ordinal")
    private Integer sectionOrdinal;

    @Lob
    @Column(name = "content_text", nullable = false, columnDefinition = "TEXT")
    private String contentText;

    @Lob
    @Column(name = "index_text", nullable = false, columnDefinition = "TEXT")
    private String indexText;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "estimated_token_count", nullable = false)
    private Integer estimatedTokenCount;

    @Lob
    @Column(name = "source_span_json", nullable = false, columnDefinition = "TEXT")
    private String sourceSpanJson;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
