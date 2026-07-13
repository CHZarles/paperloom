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
@Table(name = "paper_reading_elements",
        indexes = {
                @Index(name = "idx_paper_reading_elements_paper_model_page", columnList = "paper_id,model_version,page_number"),
                @Index(name = "idx_paper_reading_elements_paper_model_type", columnList = "paper_id,model_version,element_type"),
                @Index(name = "idx_paper_reading_elements_source", columnList = "paper_id,model_version,source_object_id"),
                @Index(name = "idx_paper_reading_elements_parent", columnList = "paper_id,model_version,parent_reading_element_id")
        })
public class PaperReadingElement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion;

    @Column(name = "reading_element_id", nullable = false, length = 96)
    private String readingElementId;

    @Column(name = "content_list_index")
    private Integer contentListIndex;

    @Column(name = "parser_element_id", length = 96)
    private String parserElementId;

    @Column(name = "source_object_id", length = 96)
    private String sourceObjectId;

    @Column(name = "element_type", nullable = false, length = 32)
    private String elementType;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "reading_order")
    private Integer readingOrder;

    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    @Column(name = "parent_reading_element_id", length = 96)
    private String parentReadingElementId;

    @Column(name = "attachment_role", length = 64)
    private String attachmentRole;

    @Column(name = "association_status", nullable = false, length = 32)
    private String associationStatus;

    @Column(name = "location_ref", length = 96)
    private String locationRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", length = 32)
    private PaperLocationType locationType;

    @Column(name = "location_not_created_reason", length = 64)
    private String locationNotCreatedReason;

    @Lob
    @Column(name = "caption_text", columnDefinition = "TEXT")
    private String captionText;

    @Lob
    @Column(name = "body_text", columnDefinition = "TEXT")
    private String bodyText;

    @Lob
    @Column(name = "searchable_text", columnDefinition = "TEXT")
    private String searchableText;

    @Column(name = "caption_source", length = 64)
    private String captionSource;

    @Column(name = "parser_image_path", length = 500)
    private String parserImagePath;

    @Lob
    @Column(name = "bbox_json", columnDefinition = "TEXT")
    private String bboxJson;

    @Lob
    @Column(name = "source_span_json", columnDefinition = "TEXT")
    private String sourceSpanJson;

    @Lob
    @Column(name = "structured_payload_json", columnDefinition = "TEXT")
    private String structuredPayloadJson;

    @Lob
    @Column(name = "raw_attributes_json", columnDefinition = "TEXT")
    private String rawAttributesJson;

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
