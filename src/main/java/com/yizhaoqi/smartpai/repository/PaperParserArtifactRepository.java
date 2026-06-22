package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.PaperParserArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperParserArtifactRepository extends JpaRepository<PaperParserArtifact, Long> {
    List<PaperParserArtifact> findByPaperId(String paperId);

    Optional<PaperParserArtifact> findFirstByPaperIdAndArtifactTypeOrderByCreatedAtDesc(String paperId, String artifactType);

    Optional<PaperParserArtifact> findFirstByPaperIdOrderByCreatedAtDesc(String paperId);

    long countByPaperId(String paperId);

    @Transactional
    void deleteByPaperId(String paperId);
}
