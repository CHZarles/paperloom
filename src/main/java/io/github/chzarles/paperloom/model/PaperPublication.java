package io.github.chzarles.paperloom.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_publications")
public class PaperPublication {

    @Id
    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "published_by", nullable = false, length = 64)
    private String publishedBy;

    @CreationTimestamp
    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;
}
