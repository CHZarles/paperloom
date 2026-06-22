package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.PaperFormula;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperFormulaRepository extends JpaRepository<PaperFormula, Long> {
    List<PaperFormula> findByPaperIdOrderByPageNumberAscIdAsc(String paperId);

    Optional<PaperFormula> findFirstByPaperIdAndFormulaId(String paperId, String formulaId);

    long countByPaperId(String paperId);

    @Transactional
    void deleteByPaperId(String paperId);
}
