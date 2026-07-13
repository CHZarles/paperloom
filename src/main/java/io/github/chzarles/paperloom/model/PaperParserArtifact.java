package io.github.chzarles.paperloom.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_parser_artifacts",
        indexes = {
                @Index(name = "idx_parser_artifact_paper", columnList = "paper_id"),
                @Index(name = "idx_parser_artifact_type", columnList = "artifact_type")
        })
public class PaperParserArtifact {
    public static final String TYPE_OPENDATALOADER_JSON = "OPENDATALOADER_JSON";
    public static final String TYPE_MINERU_RESULT_ZIP = "MINERU_RESULT_ZIP";
    public static final String TYPE_MINERU_CONTENT_LIST = "MINERU_CONTENT_LIST";
    public static final String TYPE_MINERU_MIDDLE_JSON = "MINERU_MIDDLE_JSON";
    public static final String TYPE_MINERU_MODEL_JSON = "MINERU_MODEL_JSON";
    public static final String TYPE_MINERU_MARKDOWN = "MINERU_MARKDOWN";
    public static final String TYPE_MINERU_LAYOUT_PDF = "MINERU_LAYOUT_PDF";
    public static final String TYPE_MINERU_SPAN_PDF = "MINERU_SPAN_PDF";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "artifact_type", nullable = false, length = 64)
    private String artifactType;

    @Column(name = "parser_name", length = 64)
    private String parserName;

    @Column(name = "parser_version", length = 64)
    private String parserVersion;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

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
