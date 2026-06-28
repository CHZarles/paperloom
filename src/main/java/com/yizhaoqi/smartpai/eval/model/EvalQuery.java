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

@Data
@Entity
@Table(
        name = "eval_queries",
        schema = "paperloom_eval",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_eval_queries_corpus_query", columnNames = {"corpus", "split", "query_id"})
        },
        indexes = {
                @Index(name = "idx_eval_queries_corpus_split", columnList = "corpus,split")
        }
)
public class EvalQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String corpus;

    @Column(nullable = false, length = 64)
    private String split;

    @Column(name = "query_id", nullable = false, length = 160)
    private String queryId;

    @Column(name = "query_text", nullable = false, columnDefinition = "LONGTEXT")
    private String queryText;

    @Column(name = "expected_paper_ids_json", columnDefinition = "LONGTEXT")
    private String expectedPaperIdsJson;

    @Column(name = "expected_evidence_json", columnDefinition = "LONGTEXT")
    private String expectedEvidenceJson;

    @Column(name = "source_json", columnDefinition = "LONGTEXT")
    private String sourceJson;
}
