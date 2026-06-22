package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_visual_assets",
        indexes = {
                @Index(name = "idx_visual_asset_paper", columnList = "paper_id"),
                @Index(name = "idx_visual_asset_table", columnList = "table_id"),
                @Index(name = "idx_visual_asset_figure", columnList = "figure_id"),
                @Index(name = "idx_visual_asset_page", columnList = "paper_id,page_number")
        })
public class PaperVisualAsset {
    public static final String TYPE_PAGE_SCREENSHOT = "PAGE_SCREENSHOT";
    public static final String TYPE_TABLE_CROP = "TABLE_CROP";
    public static final String TYPE_FIGURE_CROP = "FIGURE_CROP";
    public static final String TYPE_CHART_CROP = "CHART_CROP";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "asset_type", nullable = false, length = 64)
    private String assetType;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "table_id", length = 64)
    private String tableId;

    @Column(name = "figure_id", length = 64)
    private String figureId;

    @Lob
    @Column(name = "bbox_json", columnDefinition = "TEXT")
    private String bboxJson;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "width_px")
    private Integer widthPx;

    @Column(name = "height_px")
    private Integer heightPx;

    @Column(name = "sha256", length = 64)
    private String sha256;

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
