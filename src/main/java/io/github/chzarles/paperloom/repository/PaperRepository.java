package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.Paper;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperRepository extends JpaRepository<Paper, Long> {
    Optional<Paper> findFirstByPaperIdOrderByCreatedAtDesc(String paperId);

    List<Paper> findAllByPaperId(String paperId);

    List<Paper> findAllByIsPublicTrue();

    List<Paper> findAllByPaperIdAndUserIdOrderByCreatedAtDesc(String paperId, String userId);

    List<Paper> findAllByUserIdAndPaperIdIn(String userId, List<String> paperIds);

    Optional<Paper> findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(String paperId, String userId);

    Optional<Paper> findFirstByOrderByMergedAtDesc();

    long countByPaperId(String paperId);

    long countByPaperIdAndUserId(String paperId, String userId);

    @Transactional
    void deleteByPaperId(String paperId);

    @Transactional
    void deleteByPaperIdAndUserId(String paperId, String userId);

    List<Paper> findByUserId(String userId);

    List<Paper> findByUserIdAndOriginalFilenameOrderByCreatedAtDesc(String userId, String originalFilename);

    List<Paper> findByPaperIdIn(List<String> md5List);

    @Query("SELECT DISTINCT f.paperId FROM Paper f WHERE f.paperId IS NOT NULL AND f.paperId <> ''")
    List<String> findDistinctPaperIds();

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Paper f SET f.status = :newStatus WHERE f.id = :id AND f.status = :currentStatus")
    int updateStatusIfCurrent(@Param("id") Long id,
                              @Param("currentStatus") int currentStatus,
                              @Param("newStatus") int newStatus);
}
