package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ChunkInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChunkInfoRepository extends JpaRepository<ChunkInfo, Long> {
    List<ChunkInfo> findByPaperIdOrderByChunkIndexAsc(String paperId);

    boolean existsByPaperIdAndChunkIndex(String paperId, int chunkIndex);

    @Query("select c.chunkIndex from ChunkInfo c where c.paperId = :paperId order by c.chunkIndex asc")
    List<Integer> findChunkIndexesByPaperId(@Param("paperId") String paperId);
}
