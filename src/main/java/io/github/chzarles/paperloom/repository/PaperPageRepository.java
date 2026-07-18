package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperPageRepository extends JpaRepository<PaperPage, Long> {
    List<PaperPage> findByPaperIdAndModelVersionOrderByPageNumberAsc(String paperId, String modelVersion);

    Optional<PaperPage> findFirstByPaperIdAndModelVersionAndPageNumber(String paperId,
                                                                       String modelVersion,
                                                                       Integer pageNumber);

    long countByPaperIdAndModelVersion(String paperId, String modelVersion);

    void deleteByPaperId(String paperId);
}
