package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.PaperTextChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface PaperTextChunkRepository extends JpaRepository<PaperTextChunk, Long> {
    List<PaperTextChunk> findByPaperId(String paperId);

    List<PaperTextChunk> findByPaperIdOrderByChunkIdAsc(String paperId);

    List<PaperTextChunk> findByPaperIdAndPageNumberBetweenOrderByPageNumberAscChunkIdAsc(String paperId,
                                                                                        Integer startPage,
                                                                                        Integer endPage);

    @Query("SELECT DISTINCT c.paperId FROM PaperTextChunk c WHERE c.paperId IS NOT NULL AND c.paperId <> ''")
    List<String> findDistinctPaperIds();

    long countByPaperId(String paperId);

    long countByPaperIdAndPageNumberIsNotNull(String paperId);
    
    /**
     * 删除指定论文的所有文本 chunk。
     * 
     * @param paperId 论文标识
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM paper_text_chunks WHERE paper_id = ?1", nativeQuery = true)
    void deleteByPaperId(String paperId);
}
