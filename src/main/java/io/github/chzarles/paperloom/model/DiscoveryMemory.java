package io.github.chzarles.paperloom.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 智能探索的长期记忆条目（PRD v3 §3.1）。
 *
 * <p>kind：ROLE 角色定位 / PREF 研究偏好 / INSIGHT 沉淀洞察；source：USER 面板手写 / DISTILLED
 * 沉淀生成。active=false 的条目不参与 system prompt 组装。
 */
@Data
@Entity
@Table(name = "discovery_memory", indexes = @Index(name = "idx_dmem_user_kind", columnList = "user_id, kind"))
public class DiscoveryMemory {

    public static final String KIND_ROLE = "ROLE";
    public static final String KIND_PREF = "PREF";
    public static final String KIND_INSIGHT = "INSIGHT";
    public static final String SOURCE_USER = "USER";
    public static final String SOURCE_DISTILLED = "DISTILLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
