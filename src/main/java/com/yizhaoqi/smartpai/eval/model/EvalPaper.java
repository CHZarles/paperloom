package com.yizhaoqi.smartpai.eval.model;

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
@Table(
        name = "eval_papers",
        catalog = "paperloom_eval",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_eval_papers_corpus_paper", columnNames = {"corpus", "paper_id"})
        },
        indexes = {
                @Index(name = "idx_eval_papers_corpus_split", columnList = "corpus,split"),
                @Index(name = "idx_eval_papers_external", columnList = "corpus,external_paper_id")
        }
)
public class EvalPaper {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String corpus;

    @Column(nullable = false, length = 64)
    private String split;

    @Column(name = "external_paper_id", nullable = false, length = 128)
    private String externalPaperId;

    @Column(name = "paper_id", nullable = false, length = 160)
    private String paperId;

    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(name = "abstract_text", columnDefinition = "TEXT")
    private String abstractText;

    @Column(columnDefinition = "TEXT")
    private String authors;

    private String venue;

    @Column(name = "publication_year")
    private Integer year;

    private String doi;

    @Column(name = "arxiv_id")
    private String arxivId;

    @Column(name = "full_text", columnDefinition = "LONGTEXT")
    private String fullText;

    @Column(name = "source_json", columnDefinition = "LONGTEXT")
    private String sourceJson;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
