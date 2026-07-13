package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperParserArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperParserArtifactRepository extends JpaRepository<PaperParserArtifact, Long> {
    List<PaperParserArtifact> findByPaperId(String paperId);

    @Query("SELECT DISTINCT a.paperId FROM PaperParserArtifact a WHERE a.paperId IS NOT NULL AND a.paperId <> ''")
    List<String> findDistinctPaperIds();

    Optional<PaperParserArtifact> findFirstByPaperIdAndArtifactTypeOrderByCreatedAtDesc(String paperId, String artifactType);

    Optional<PaperParserArtifact> findFirstByPaperIdOrderByCreatedAtDesc(String paperId);

    long countByPaperId(String paperId);

    @Transactional
    void deleteByPaperId(String paperId);
}
