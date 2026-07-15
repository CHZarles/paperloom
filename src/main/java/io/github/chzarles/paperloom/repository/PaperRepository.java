package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.Paper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    List<Paper> findAllByVectorizationStatusIsNull();

    List<Paper> findAllByPaperIdAndUserIdOrderByCreatedAtDesc(String paperId, String userId);

    Optional<Paper> findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(String paperId, String userId);

    Optional<Paper> findFirstByPaperIdAndIsPublicTrueOrderByCreatedAtDesc(String paperId);

    Optional<Paper> findFirstByOriginalFilenameAndIsPublicTrueOrderByCreatedAtDesc(String originalFilename);

    Optional<Paper> findFirstByOrderByMergedAtDesc();

    long countByPaperId(String paperId);

    long countByPaperIdAndUserId(String paperId, String userId);

    @Transactional
    void deleteByPaperId(String paperId);

    @Transactional
    void deleteByPaperIdAndUserId(String paperId, String userId);

    List<Paper> findByUserIdOrIsPublicTrue(String userId);

    Page<Paper> findByUserIdOrIsPublicTrue(String userId, Pageable pageable);

    @Query("SELECT f FROM Paper f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    List<Paper> findAccessiblePapersWithTags(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);

    @Query("""
            SELECT f FROM Paper f
            WHERE f.paperId IN :paperIds
              AND (f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false))
            """)
    List<Paper> findAccessiblePapersByPaperIdInWithTags(@Param("userId") String userId,
                                                       @Param("orgTagList") List<String> orgTagList,
                                                       @Param("paperIds") List<String> paperIds);

    @Query("""
            SELECT f FROM Paper f
            WHERE f.paperId IN :paperIds
              AND (f.userId = :userId OR f.isPublic = true)
            """)
    List<Paper> findAccessiblePapersByPaperIdIn(@Param("userId") String userId,
                                                @Param("paperIds") List<String> paperIds);

    @Query("SELECT f FROM Paper f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    Page<Paper> findAccessiblePapersPageWithTags(@Param("userId") String userId,
                                                 @Param("orgTagList") List<String> orgTagList,
                                                 Pageable pageable);

    @Query("""
            SELECT f FROM Paper f
            WHERE (f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false))
              AND (
                :query IS NULL OR :query = '' OR
                LOWER(COALESCE(f.paperTitle, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.originalFilename, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.authors, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.venue, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.abstractText, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.doi, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.arxivId, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.paperId, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                CAST(f.publicationYear AS string) LIKE CONCAT('%', :query, '%')
              )
            """)
    Page<Paper> searchAccessiblePaperCandidates(@Param("userId") String userId,
                                                @Param("orgTagList") List<String> orgTagList,
                                                @Param("query") String query,
                                                Pageable pageable);

    @Query("""
            SELECT f FROM Paper f
            WHERE (f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false))
              AND f.paperId IS NOT NULL AND f.paperId <> ''
              AND EXISTS (
                SELECT model.id FROM PaperReadingModel model
                WHERE model.paperId = f.paperId
                  AND model.isCurrent = true
                  AND model.modelStatus = io.github.chzarles.paperloom.model.PaperReadingModelStatus.READING_MODEL_READY
                  AND model.retrievalIndexStatus = io.github.chzarles.paperloom.model.PaperRetrievalIndexStatus.READY
                  AND model.retrievalIndexGeneration IS NOT NULL
                  AND TRIM(model.retrievalIndexGeneration) <> ''
                  AND model.retrievalEmbeddingContract IS NOT NULL
                  AND TRIM(model.retrievalEmbeddingContract) <> ''
                  AND model.retrievalIndexedLocationCount > 0
              )
              AND (
                :query IS NULL OR :query = '' OR
                LOWER(COALESCE(f.paperTitle, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.originalFilename, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.authors, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.venue, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.abstractText, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.doi, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.arxivId, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.paperId, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                CAST(f.publicationYear AS string) LIKE CONCAT('%', :query, '%')
              )
            """)
    Page<Paper> searchAccessibleSearchablePaperCandidates(@Param("userId") String userId,
                                                          @Param("orgTagList") List<String> orgTagList,
                                                          @Param("query") String query,
                                                          Pageable pageable);

    @Query("""
            SELECT f FROM Paper f
            WHERE (f.userId = :userId OR f.isPublic = true)
              AND (
                :query IS NULL OR :query = '' OR
                LOWER(COALESCE(f.paperTitle, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.originalFilename, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.authors, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.venue, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.abstractText, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.doi, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.arxivId, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.paperId, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                CAST(f.publicationYear AS string) LIKE CONCAT('%', :query, '%')
              )
            """)
    Page<Paper> searchAccessiblePaperCandidatesWithoutOrgTags(@Param("userId") String userId,
                                                              @Param("query") String query,
                                                              Pageable pageable);

    @Query("""
            SELECT f FROM Paper f
            WHERE (f.userId = :userId OR f.isPublic = true)
              AND f.paperId IS NOT NULL AND f.paperId <> ''
              AND EXISTS (
                SELECT model.id FROM PaperReadingModel model
                WHERE model.paperId = f.paperId
                  AND model.isCurrent = true
                  AND model.modelStatus = io.github.chzarles.paperloom.model.PaperReadingModelStatus.READING_MODEL_READY
                  AND model.retrievalIndexStatus = io.github.chzarles.paperloom.model.PaperRetrievalIndexStatus.READY
                  AND model.retrievalIndexGeneration IS NOT NULL
                  AND TRIM(model.retrievalIndexGeneration) <> ''
                  AND model.retrievalEmbeddingContract IS NOT NULL
                  AND TRIM(model.retrievalEmbeddingContract) <> ''
                  AND model.retrievalIndexedLocationCount > 0
              )
              AND (
                :query IS NULL OR :query = '' OR
                LOWER(COALESCE(f.paperTitle, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.originalFilename, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.authors, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.venue, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.abstractText, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.doi, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.arxivId, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(f.paperId, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                CAST(f.publicationYear AS string) LIKE CONCAT('%', :query, '%')
              )
            """)
    Page<Paper> searchAccessibleSearchablePaperCandidatesWithoutOrgTags(
            @Param("userId") String userId,
            @Param("query") String query,
            Pageable pageable);

    @Query("SELECT f FROM Paper f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    List<Paper> findAccessiblePapers(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);

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
