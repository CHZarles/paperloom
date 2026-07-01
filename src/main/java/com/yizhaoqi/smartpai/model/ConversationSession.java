package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "conversation_sessions", indexes = {
        @Index(name = "idx_cs_user_id", columnList = "user_id"),
        @Index(name = "idx_cs_conversation_id", columnList = "conversation_id", unique = true),
        @Index(name = "idx_cs_status", columnList = "status")
})
public class ConversationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "conversation_id", length = 64, nullable = false, unique = true)
    private String conversationId;

    @Column(length = 255)
    private String title;

    @Column(length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private SessionStatus status = SessionStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_mode", nullable = false, length = 32)
    private ConversationScopeMode scopeMode = ConversationScopeMode.AUTO_LIBRARY;

    @Column(name = "scope_locked", nullable = false)
    private boolean scopeLocked = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_status", nullable = false, length = 32)
    private ConversationScopeStatus scopeStatus = ConversationScopeStatus.READY;

    @Column(name = "source_label", length = 255)
    private String sourceLabel;

    @Column(name = "source_recipe_json", columnDefinition = "LONGTEXT")
    private String sourceRecipeJson;

    @Column(name = "source_snapshot_json", columnDefinition = "LONGTEXT")
    private String sourceSnapshotJson;

    @Column(name = "source_paper_count")
    private Integer sourcePaperCount;

    @Column(name = "conversation_memory_json", columnDefinition = "LONGTEXT")
    private String conversationMemoryJson;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum SessionStatus {
        ACTIVE, ARCHIVED
    }
}
