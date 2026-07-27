package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperPublication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface PaperPublicationRepository extends JpaRepository<PaperPublication, String> {

    boolean existsByPaperId(String paperId);

    @Query("SELECT p.paperId FROM PaperPublication p")
    List<String> findAllPaperIds();

    @Transactional
    void deleteByPaperId(String paperId);
}
