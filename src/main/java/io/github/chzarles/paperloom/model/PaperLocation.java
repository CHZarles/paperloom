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

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_locations",
        indexes = {
                @Index(name = "idx_paper_locations_ref", columnList = "location_ref"),
                @Index(name = "idx_paper_locations_paper_model", columnList = "paper_id,model_version"),
                @Index(name = "idx_paper_locations_paper_model_page", columnList = "paper_id,model_version,page_number")
        })
public class PaperLocation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "location_ref", nullable = false, unique = true, length = 96)
    private String locationRef;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", nullable = false, length = 32)
    private PaperLocationType locationType;

    @Column(name = "page_number", nullable = false)
    private Integer pageNumber;

    @Column(name = "page_end_number")
    private Integer pageEndNumber;

    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    @Column(name = "source_object_id", length = 96)
    private String sourceObjectId;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Lob
    @Column(name = "source_span_json", nullable = false, columnDefinition = "TEXT")
    private String sourceSpanJson;

    @Column(name = "content_kind", nullable = false, length = 64)
    private String contentKind;

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
