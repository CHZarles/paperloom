package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperVisualAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperVisualAssetRepository extends JpaRepository<PaperVisualAsset, Long> {
    List<PaperVisualAsset> findByPaperId(String paperId);

    @Query("SELECT DISTINCT a.paperId FROM PaperVisualAsset a WHERE a.paperId IS NOT NULL AND a.paperId <> ''")
    List<String> findDistinctPaperIds();

    Optional<PaperVisualAsset> findFirstByPaperIdAndAssetTypeAndPageNumber(
            String paperId,
            String assetType,
            Integer pageNumber
    );

    Optional<PaperVisualAsset> findFirstByPaperIdAndAssetTypeAndParserImagePath(
            String paperId,
            String assetType,
            String parserImagePath
    );

    Optional<PaperVisualAsset> findFirstByPaperIdAndAssetTypeAndReadingElementId(
            String paperId,
            String assetType,
            String readingElementId
    );

    List<PaperVisualAsset> findByPaperIdAndSourceObjectId(String paperId, String sourceObjectId);

    List<PaperVisualAsset> findByPaperIdAndReadingElementId(String paperId, String readingElementId);

    long countByPaperIdAndAssetType(String paperId, String assetType);

    @Transactional
    void deleteByPaperId(String paperId);
}
