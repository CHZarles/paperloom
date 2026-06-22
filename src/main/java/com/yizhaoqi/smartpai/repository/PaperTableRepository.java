package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.PaperTable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperTableRepository extends JpaRepository<PaperTable, Long> {
    List<PaperTable> findByPaperIdOrderByPageNumberAscIdAsc(String paperId);

    Optional<PaperTable> findFirstByPaperIdAndTableId(String paperId, String tableId);

    long countByPaperId(String paperId);

    @Transactional
    void deleteByPaperId(String paperId);
}
