package com.yizhaoqi.smartpai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "conversation_source_quotes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_conversation_source_quotes_conversation_ref",
                        columnNames = {"conversation_id", "source_quote_ref"})
        },
        indexes = {
                @Index(name = "idx_conversation_source_quotes_ref", columnList = "source_quote_ref")
        })
public class ConversationSourceQuote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, length = 64)
    private String conversationId;

    @Column(name = "source_quote_ref", nullable = false, length = 96)
    private String sourceQuoteRef;

    @Column(name = "first_seen_turn_id", nullable = false, length = 64)
    private String firstSeenTurnId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
