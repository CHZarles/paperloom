package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperSectionRepository extends JpaRepository<PaperSection, Long> {
    List<PaperSection> findByPaperIdAndModelVersionOrderByPageNumberFromAscDisplayOrderAsc(String paperId,
                                                                                           String modelVersion);

    Optional<PaperSection> findFirstByPaperIdAndModelVersionAndSectionId(String paperId,
                                                                         String modelVersion,
                                                                         String sectionId);

    long countByPaperIdAndModelVersion(String paperId, String modelVersion);
}
