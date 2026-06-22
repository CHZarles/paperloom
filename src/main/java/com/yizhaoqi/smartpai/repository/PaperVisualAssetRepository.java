package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.PaperVisualAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperVisualAssetRepository extends JpaRepository<PaperVisualAsset, Long> {
    List<PaperVisualAsset> findByPaperId(String paperId);

    Optional<PaperVisualAsset> findFirstByPaperIdAndAssetTypeAndPageNumber(
            String paperId,
            String assetType,
            Integer pageNumber
    );

    Optional<PaperVisualAsset> findFirstByPaperIdAndAssetTypeAndTableId(
            String paperId,
            String assetType,
            String tableId
    );

    Optional<PaperVisualAsset> findFirstByPaperIdAndAssetTypeAndFigureId(
            String paperId,
            String assetType,
            String figureId
    );

    long countByPaperIdAndAssetType(String paperId, String assetType);

    @Transactional
    void deleteByPaperId(String paperId);
}
