package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.PaperSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaperSectionRepository extends JpaRepository<PaperSection, Long> {
    List<PaperSection> findByPaperIdAndModelVersionOrderByPageNumberFromAscDisplayOrderAsc(String paperId,
                                                                                           String modelVersion);

    long countByPaperIdAndModelVersion(String paperId, String modelVersion);
}
