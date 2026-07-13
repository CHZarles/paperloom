package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperCollectionPaper;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface PaperCollectionPaperRepository extends JpaRepository<PaperCollectionPaper, Long> {

    @Query("SELECT p FROM PaperCollectionPaper p WHERE p.collection.id = :collectionId ORDER BY p.createdAt ASC")
    List<PaperCollectionPaper> findByCollectionIdOrderByCreatedAtAsc(@Param("collectionId") Long collectionId);

    @Query("""
            SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
            FROM PaperCollectionPaper p
            WHERE p.collection.id = :collectionId AND p.paperId = :paperId
            """)
    boolean existsByCollectionIdAndPaperId(@Param("collectionId") Long collectionId, @Param("paperId") String paperId);

    @Transactional
    @Modifying
    @Query("DELETE FROM PaperCollectionPaper p WHERE p.collection.id = :collectionId AND p.paperId = :paperId")
    void deleteByCollectionIdAndPaperId(@Param("collectionId") Long collectionId, @Param("paperId") String paperId);

    @Transactional
    @Modifying
    @Query("DELETE FROM PaperCollectionPaper p WHERE p.collection.id = :collectionId")
    void deleteByCollectionId(@Param("collectionId") Long collectionId);
}
