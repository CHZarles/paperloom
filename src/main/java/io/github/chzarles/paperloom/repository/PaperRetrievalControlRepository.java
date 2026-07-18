package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperRetrievalControl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface PaperRetrievalControlRepository extends JpaRepository<PaperRetrievalControl, String> {

    boolean existsByControlNameAndFullRebuildStatus(String controlName, String status);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE paper_retrieval_control
            SET full_rebuild_status = 'RUNNING',
                job_id = :jobId,
                requested_by = :requesterId,
                snapshot_paper_count = :snapshotCount,
                completed_paper_count = 0,
                failed_paper_count = 0,
                started_at = :startedAt,
                finished_at = NULL,
                last_error = NULL
            WHERE control_name = 'QDRANT_FULL_REBUILD'
              AND full_rebuild_status <> 'RUNNING'
            """, nativeQuery = true)
    int claimFullRebuild(@Param("jobId") String jobId,
                         @Param("requesterId") String requesterId,
                         @Param("snapshotCount") int snapshotCount,
                         @Param("startedAt") LocalDateTime startedAt);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE paper_retrieval_control
            SET completed_paper_count = :completedCount,
                failed_paper_count = :failedCount
            WHERE control_name = 'QDRANT_FULL_REBUILD'
              AND full_rebuild_status = 'RUNNING'
              AND job_id = :jobId
            """, nativeQuery = true)
    int updateFullRebuildProgress(@Param("jobId") String jobId,
                                  @Param("completedCount") int completedCount,
                                  @Param("failedCount") int failedCount);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE paper_retrieval_control
            SET full_rebuild_status = :status,
                completed_paper_count = :completedCount,
                failed_paper_count = :failedCount,
                finished_at = :finishedAt,
                last_error = :lastError
            WHERE control_name = 'QDRANT_FULL_REBUILD'
              AND full_rebuild_status = 'RUNNING'
              AND job_id = :jobId
            """, nativeQuery = true)
    int finishFullRebuild(@Param("jobId") String jobId,
                          @Param("status") String status,
                          @Param("completedCount") int completedCount,
                          @Param("failedCount") int failedCount,
                          @Param("finishedAt") LocalDateTime finishedAt,
                          @Param("lastError") String lastError);
}
