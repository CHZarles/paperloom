package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperReadingModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperReadingModelRepository extends JpaRepository<PaperReadingModel, Long> {
    Optional<PaperReadingModel> findFirstByPaperIdAndIsCurrentTrue(String paperId);

    List<PaperReadingModel> findByPaperIdOrderByCreatedAtDesc(String paperId);

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
