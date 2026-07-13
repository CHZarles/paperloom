package io.github.chzarles.paperloom.eval.model;

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
        name = "eval_chunks",
        catalog = "paperloom_eval",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_eval_chunks_corpus_paper_chunk", columnNames = {"corpus", "paper_id", "chunk_id"})
        },
        indexes = {
                @Index(name = "idx_eval_chunks_corpus_split", columnList = "corpus,split"),
                @Index(name = "idx_eval_chunks_paper", columnList = "corpus,paper_id,chunk_id")
        }
)
public class EvalChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String corpus;

    @Column(nullable = false, length = 64)
    private String split;

    @Column(name = "paper_id", nullable = false, length = 160)
    private String paperId;

    @Column(name = "chunk_id", nullable = false)
    private Integer chunkId;

    @Column(name = "text_content", columnDefinition = "LONGTEXT")
    private String textContent;

    @Column(name = "retrieval_text_content", columnDefinition = "LONGTEXT")
    private String retrievalTextContent;

    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "source_kind", length = 64)
    private String sourceKind;

    @Column(name = "evidence_role", length = 64)
    private String evidenceRole;

    @Column(name = "source_json", columnDefinition = "LONGTEXT")
    private String sourceJson;
}
