package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperPublication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface PaperPublicationRepository extends JpaRepository<PaperPublication, String> {

    boolean existsByPaperId(String paperId);

    @Transactional
    void deleteByPaperId(String paperId);
}
