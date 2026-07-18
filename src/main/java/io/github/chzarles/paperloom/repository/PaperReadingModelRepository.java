package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperRetrievalIndexStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaperReadingModelRepository extends JpaRepository<PaperReadingModel, Long> {
    Optional<PaperReadingModel> findFirstByPaperIdAndIsCurrentTrue(String paperId);

    List<PaperReadingModel> findByPaperIdOrderByCreatedAtDesc(String paperId);

    List<PaperReadingModel> findByIsCurrentTrueAndModelStatusOrderByPaperIdAsc(PaperReadingModelStatus modelStatus);

    List<PaperReadingModel> findByPaperIdInAndIsCurrentTrueAndModelStatus(
            List<String> paperIds,
            PaperReadingModelStatus modelStatus
    );

    long countByIsCurrentTrueAndRetrievalIndexStatusIn(List<PaperRetrievalIndexStatus> statuses);

    void deleteByPaperId(String paperId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE paper_reading_models
            SET retrieval_index_status = 'BUILDING',
                retrieval_index_job_id = :jobId,
                retrieval_index_started_at = :startedAt,
                retrieval_index_error_type = NULL,
                retrieval_index_error_message = NULL
            WHERE paper_id = :paperId
              AND model_version = :modelVersion
              AND is_current = true
              AND model_status = 'READING_MODEL_READY'
              AND (retrieval_index_status IS NULL OR retrieval_index_status IN ('PENDING', 'FAILED'))
              AND NOT EXISTS (
                  SELECT 1 FROM paper_retrieval_control
                  WHERE control_name = 'QDRANT_FULL_REBUILD'
                    AND full_rebuild_status = 'RUNNING'
              )
            """, nativeQuery = true)
    int claimInitialIndex(@Param("paperId") String paperId,
                          @Param("modelVersion") String modelVersion,
                          @Param("jobId") String jobId,
                          @Param("startedAt") LocalDateTime startedAt);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE paper_reading_models
            SET retrieval_index_status = 'REBUILDING',
                retrieval_index_job_id = :jobId,
                retrieval_index_started_at = :startedAt,
                retrieval_index_error_type = NULL,
                retrieval_index_error_message = NULL
            WHERE paper_id = :paperId
              AND model_version = :modelVersion
              AND is_current = true
              AND model_status = 'READING_MODEL_READY'
              AND retrieval_index_status IN ('READY', 'FAILED')
              AND NOT EXISTS (
                  SELECT 1 FROM paper_retrieval_control
                  WHERE control_name = 'QDRANT_FULL_REBUILD'
                    AND full_rebuild_status = 'RUNNING'
              )
            """, nativeQuery = true)
    int claimRebuild(@Param("paperId") String paperId,
                     @Param("modelVersion") String modelVersion,
                     @Param("jobId") String jobId,
                     @Param("startedAt") LocalDateTime startedAt);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE paper_reading_models
            SET retrieval_index_status = 'REBUILDING',
                retrieval_index_job_id = :jobId,
                retrieval_index_started_at = :startedAt,
                retrieval_index_error_type = NULL,
                retrieval_index_error_message = NULL
            WHERE paper_id = :paperId
              AND model_version = :modelVersion
              AND is_current = true
              AND model_status = 'READING_MODEL_READY'
              AND (retrieval_index_status IS NULL OR retrieval_index_status NOT IN ('BUILDING', 'REBUILDING'))
            """, nativeQuery = true)
    int claimFullRebuildPaper(@Param("paperId") String paperId,
                              @Param("modelVersion") String modelVersion,
                              @Param("jobId") String jobId,
                              @Param("startedAt") LocalDateTime startedAt);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE paper_reading_models
            SET retrieval_index_status = 'READY',
                retrieval_index_job_id = NULL,
                retrieval_index_contract = :indexContract,
                retrieval_indexed_location_count = :indexedLocationCount,
                retrieval_indexed_at = :indexedAt,
                retrieval_index_error_type = NULL,
                retrieval_index_error_message = NULL
            WHERE paper_id = :paperId
              AND model_version = :modelVersion
              AND is_current = true
              AND model_status = 'READING_MODEL_READY'
              AND retrieval_index_status = :runningStatus
              AND retrieval_index_job_id = :jobId
            """, nativeQuery = true)
    int finishRetrievalIndexReady(@Param("paperId") String paperId,
                                  @Param("modelVersion") String modelVersion,
                                  @Param("runningStatus") String runningStatus,
                                  @Param("jobId") String jobId,
                                  @Param("indexContract") String indexContract,
                                  @Param("indexedLocationCount") int indexedLocationCount,
                                  @Param("indexedAt") LocalDateTime indexedAt);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE paper_reading_models
            SET retrieval_index_status = 'FAILED',
                retrieval_index_job_id = NULL,
                retrieval_indexed_location_count = 0,
                retrieval_index_error_type = :errorType,
                retrieval_index_error_message = :errorMessage
            WHERE paper_id = :paperId
              AND model_version = :modelVersion
              AND is_current = true
              AND retrieval_index_status = :runningStatus
              AND retrieval_index_job_id = :jobId
            """, nativeQuery = true)
    int finishRetrievalIndexFailed(@Param("paperId") String paperId,
                                   @Param("modelVersion") String modelVersion,
                                   @Param("runningStatus") String runningStatus,
                                   @Param("jobId") String jobId,
                                   @Param("errorType") String errorType,
                                   @Param("errorMessage") String errorMessage);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PaperReadingModel model
            SET model.isCurrent = false
            WHERE model.paperId = :paperId
              AND model.isCurrent = true
              AND model.modelVersion <> :exceptModelVersion
            """)
    int clearCurrentModels(@Param("paperId") String paperId,
                           @Param("exceptModelVersion") String exceptModelVersion);
}
