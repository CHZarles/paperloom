package io.github.chzarles.paperloom.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_visual_assets",
        indexes = {
                @Index(name = "idx_visual_asset_paper", columnList = "paper_id"),
                @Index(name = "idx_visual_asset_source", columnList = "paper_id,source_object_id"),
                @Index(name = "idx_visual_asset_reading_element", columnList = "paper_id,reading_element_id"),
                @Index(name = "idx_visual_asset_page", columnList = "paper_id,page_number")
        })
public class PaperVisualAsset {
    public static final String TYPE_PAGE_SCREENSHOT = "PAGE_SCREENSHOT";
    public static final String TYPE_TABLE_CROP = "TABLE_CROP";
    public static final String TYPE_FIGURE_CROP = "FIGURE_CROP";
    public static final String TYPE_CHART_CROP = "CHART_CROP";
    public static final String TYPE_PARSER_IMAGE = "PARSER_IMAGE";
    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_MISSING_IN_ARTIFACT = "MISSING_IN_ARTIFACT";
    public static final String STATUS_STORAGE_FAILED = "STORAGE_FAILED";
    public static final String STATUS_RENDER_FAILED = "RENDER_FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "asset_type", nullable = false, length = 64)
    private String assetType;

    @Column(name = "asset_status", nullable = false, length = 64)
    private String assetStatus;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "source_object_id", length = 96)
    private String sourceObjectId;

    @Column(name = "reading_element_id", length = 96)
    private String readingElementId;

    @Column(name = "parser_element_id", length = 96)
    private String parserElementId;

    @Column(name = "parser_image_path", length = 500)
    private String parserImagePath;

    @Lob
    @Column(name = "bbox_json", columnDefinition = "TEXT")
    private String bboxJson;

    @Column(name = "object_key", length = 500)
    private String objectKey;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "width_px")
    private Integer widthPx;

    @Column(name = "height_px")
    private Integer heightPx;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

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
