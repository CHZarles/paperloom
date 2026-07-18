package io.github.chzarles.paperloom.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_retrieval_control")
public class PaperRetrievalControl {

    public static final String FULL_REBUILD = "QDRANT_FULL_REBUILD";
    public static final String IDLE = "IDLE";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    @Id
    @Column(name = "control_name", length = 64)
    private String controlName;

    @Column(name = "full_rebuild_status", nullable = false, length = 32)
    private String fullRebuildStatus;

    @Column(name = "job_id", length = 64)
    private String jobId;

    @Column(name = "requested_by", length = 64)
    private String requestedBy;

    @Column(name = "snapshot_paper_count", nullable = false)
    private int snapshotPaperCount;

    @Column(name = "completed_paper_count", nullable = false)
    private int completedPaperCount;

    @Column(name = "failed_paper_count", nullable = false)
    private int failedPaperCount;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "active_index_contract", length = 255)
    private String activeIndexContract;

    @Column(name = "lexical_average_document_length")
    private Double lexicalAverageDocumentLength;
}
