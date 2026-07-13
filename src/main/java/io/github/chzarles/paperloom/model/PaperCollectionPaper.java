package io.github.chzarles.paperloom.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_collection_papers",
        uniqueConstraints = @UniqueConstraint(name = "uk_pcp_collection_paper", columnNames = {"collection_id", "paper_id"}),
        indexes = {
                @Index(name = "idx_pcp_collection", columnList = "collection_id"),
                @Index(name = "idx_pcp_paper", columnList = "paper_id")
        })
public class PaperCollectionPaper {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_id", nullable = false)
    private PaperCollection collection;

    @Column(name = "paper_id", nullable = false, length = 160)
    private String paperId;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
