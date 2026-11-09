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

import java.time.LocalDateTime;

/**
 * 智能探索聊天的单例 thread 行。
 *
 * <p>每用户只有一个 thread：消息按 id 排序，没有会话表。role=CUTOFF 的行是 /clear 的记忆分界，
 * 窗口组装只取最后一条 CUTOFF 之后的消息。ASSISTANT 行承载 turn 的状态、候选卡片（入库门窗口
 * 事实源）、入库结果与用量。
 */
@Data
@Entity
@Table(name = "discovery_chat_message", indexes = @Index(name = "idx_dchat_user", columnList = "user_id, id"))
public class DiscoveryChatMessage {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String ROLE_SYSTEM = "SYSTEM";
    public static final String ROLE_CUTOFF = "CUTOFF";

    public static final String TURN_STATUS_RUNNING = "RUNNING";
    public static final String TURN_STATUS_COMPLETED = "COMPLETED";
    public static final String TURN_STATUS_FAILED = "FAILED";
    public static final String TURN_STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** USER / ASSISTANT / SYSTEM / CUTOFF。 */
    @Column(nullable = false, length = 16)
    private String role;

    /** CHAT / DISTILL，仅 ASSISTANT turn 行有。 */
    @Column(length = 16)
    private String kind;

    @Column(name = "turn_id", length = 64)
    private String turnId;

    @Column(name = "turn_status", length = 16)
    private String turnStatus;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(columnDefinition = "LONGTEXT")
    private String cards;

    @Column(name = "import_results", columnDefinition = "LONGTEXT")
    private String importResults;

    /** 当轮检索动作 JSON 数组 [{"q":"...","hits":12}]（ASSISTANT 行；历史注入用） */
    @Column(name = "search_log", columnDefinition = "TEXT")
    private String searchLog;

    @Column(name = "token_usage", columnDefinition = "TEXT")
    private String tokenUsage;

    @Column(length = 64)
    private String model;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
