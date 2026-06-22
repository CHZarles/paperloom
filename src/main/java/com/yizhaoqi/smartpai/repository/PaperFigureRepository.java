package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.PaperFigure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperFigureRepository extends JpaRepository<PaperFigure, Long> {
    List<PaperFigure> findByPaperIdOrderByPageNumberAscIdAsc(String paperId);

    Optional<PaperFigure> findFirstByPaperIdAndFigureId(String paperId, String figureId);

    long countByPaperId(String paperId);

    @Transactional
    void deleteByPaperId(String paperId);
}
