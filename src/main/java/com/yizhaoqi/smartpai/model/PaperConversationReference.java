package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_conversation_reference",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pcr_conversation_ref", columnNames = {"conversation_id", "ref_id"})
        },
        indexes = {
                @Index(name = "idx_pcr_conversation_id", columnList = "conversation_id"),
                @Index(name = "idx_pcr_ref_type", columnList = "ref_type"),
                @Index(name = "idx_pcr_scope_snapshot_id", columnList = "scope_snapshot_id")
        })
public class PaperConversationReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ref_id", nullable = false, length = 96)
    private String refId;

    @Column(name = "conversation_id", nullable = false, length = 64)
    private String conversationId;

    @Column(name = "scope_snapshot_id", length = 128)
    private String scopeSnapshotId;

    @Column(name = "turn_id", length = 128)
    private String turnId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_type", nullable = false, length = 32)
    private RefType refType;

    @Column(name = "source_entity_id", length = 128)
    private String sourceEntityId;

    @Column(name = "source_payload_json", columnDefinition = "LONGTEXT")
    private String sourcePayloadJson;

    @Column(name = "display_payload_json", columnDefinition = "LONGTEXT")
    private String displayPayloadJson;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public enum RefType {
        PAPER,
        EVIDENCE,
        PAGE,
        CITATION
    }
}
