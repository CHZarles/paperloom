package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperPassage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperPassageRepository extends JpaRepository<PaperPassage, Long> {
    Optional<PaperPassage> findFirstByPassageRef(String passageRef);

    List<PaperPassage> findByPaperIdAndModelVersionOrderByDocumentOrdinalAsc(String paperId, String modelVersion);

    List<PaperPassage> findByPaperIdAndModelVersionAndParentSectionIdOrderBySectionOrdinalAsc(
            String paperId,
            String modelVersion,
            String parentSectionId
    );

    void deleteByPaperIdAndModelVersion(String paperId, String modelVersion);

    void deleteByPaperId(String paperId);
}
