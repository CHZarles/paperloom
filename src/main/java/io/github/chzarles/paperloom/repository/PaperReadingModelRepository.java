package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
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

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE paper_reading_models
            SET retrieval_index_status = 'READY',
                retrieval_index_generation = :newGeneration,
                retrieval_embedding_contract = :embeddingContract,
                retrieval_indexed_location_count = :indexedLocationCount,
                retrieval_indexed_at = :indexedAt
            WHERE paper_id = :paperId
              AND model_version = :modelVersion
              AND is_current = true
              AND model_status = 'READING_MODEL_READY'
              AND (
                    (:previousGeneration IS NULL AND retrieval_index_generation IS NULL)
                    OR retrieval_index_generation = :previousGeneration
                  )
            """, nativeQuery = true)
    int activateRetrievalIndex(@Param("paperId") String paperId,
                               @Param("modelVersion") String modelVersion,
                               @Param("previousGeneration") String previousGeneration,
                               @Param("newGeneration") String newGeneration,
                               @Param("embeddingContract") String embeddingContract,
                               @Param("indexedLocationCount") int indexedLocationCount,
                               @Param("indexedAt") LocalDateTime indexedAt);

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
