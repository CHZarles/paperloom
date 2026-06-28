package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.Paper;
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

    @Query("SELECT f FROM Paper f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    Page<Paper> findAccessiblePapersPageWithTags(@Param("userId") String userId,
                                                 @Param("orgTagList") List<String> orgTagList,
                                                 Pageable pageable);

    @Query("SELECT f FROM Paper f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    List<Paper> findAccessiblePapers(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);

    List<Paper> findByUserId(String userId);

    List<Paper> findByUserIdAndOriginalFilenameOrderByCreatedAtDesc(String userId, String originalFilename);

    List<Paper> findByPaperIdIn(List<String> md5List);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Paper f SET f.status = :newStatus WHERE f.id = :id AND f.status = :currentStatus")
    int updateStatusIfCurrent(@Param("id") Long id,
                              @Param("currentStatus") int currentStatus,
                              @Param("newStatus") int newStatus);
}
