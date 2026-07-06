package com.yizhaoqi.smartpai.model;

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
@Table(name = "paper_source_quotes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_paper_source_quotes_ref", columnNames = "source_quote_ref"),
                @UniqueConstraint(name = "uk_paper_source_quotes_idempotency", columnNames = {
                        "paper_id",
                        "model_version",
                        "location_ref",
                        "split_policy_version",
                        "split_index",
                        "content_hash"
                })
        },
        indexes = {
                @Index(name = "idx_paper_source_quotes_paper_model", columnList = "paper_id,model_version"),
                @Index(name = "idx_paper_source_quotes_location", columnList = "location_ref")
        })
public class PaperSourceQuote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_quote_ref", nullable = false, length = 96)
    private String sourceQuoteRef;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion;

    @Column(name = "location_ref", nullable = false, length = 96)
    private String locationRef;

    @Column(name = "location_type", nullable = false, length = 32)
    private String locationType;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "page_end_number")
    private Integer pageEndNumber;

    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    @Column(name = "content_kind", nullable = false, length = 64)
    private String contentKind;

    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "split_policy_version", nullable = false, length = 64)
    private String splitPolicyVersion;

    @Column(name = "split_index", nullable = false)
    private Integer splitIndex;

    @Lob
    @Column(name = "source_span_json", nullable = false, columnDefinition = "TEXT")
    private String sourceSpanJson;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
