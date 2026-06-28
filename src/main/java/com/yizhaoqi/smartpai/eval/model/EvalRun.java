package com.yizhaoqi.smartpai.eval.model;

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

@Data
@Entity
@Table(
        name = "eval_runs",
        schema = "paperloom_eval",
        indexes = {
                @Index(name = "idx_eval_runs_corpus_split", columnList = "corpus,split"),
                @Index(name = "idx_eval_runs_created_at", columnList = "created_at")
        }
)
public class EvalRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String corpus;

    @Column(nullable = false, length = 64)
    private String split;

    @Column(nullable = false, length = 128)
    private String strategy;

    @Column(name = "run_config_json", columnDefinition = "LONGTEXT")
    private String runConfigJson;

    @Column(name = "metrics_json", columnDefinition = "LONGTEXT")
    private String metricsJson;

    @Column(name = "artifact_path", length = 1000)
    private String artifactPath;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
