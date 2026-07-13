package io.github.chzarles.paperloom.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_sections",
        indexes = {
                @Index(name = "idx_paper_sections_paper_model_page", columnList = "paper_id,model_version,page_number_from"),
                @Index(name = "idx_paper_sections_paper_model_section", columnList = "paper_id,model_version,section_id")
        })
public class PaperSection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion;

    @Column(name = "section_id", nullable = false, length = 96)
    private String sectionId;

    @Column(name = "section_title", nullable = false, length = 500)
    private String sectionTitle;

    @Column(name = "section_level")
    private Integer sectionLevel;

    @Column(name = "page_number_from", nullable = false)
    private Integer pageNumberFrom;

    @Column(name = "page_number_to", nullable = false)
    private Integer pageNumberTo;

    @Column(name = "reading_order_from")
    private Integer readingOrderFrom;

    @Column(name = "reading_order_to")
    private Integer readingOrderTo;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Lob
    @Column(name = "section_text", nullable = false, columnDefinition = "TEXT")
    private String sectionText;

    @Column(name = "text_hash", nullable = false, length = 64)
    private String textHash;

    @Column(name = "char_count", nullable = false)
    private Integer charCount;

    @Lob
    @Column(name = "source_span_json", nullable = false, columnDefinition = "TEXT")
    private String sourceSpanJson;

    @Column(name = "parser_name", length = 64)
    private String parserName;

    @Column(name = "parser_version", length = 64)
    private String parserVersion;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "org_tag", length = 50)
    private String orgTag;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
