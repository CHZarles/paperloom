package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.PaperPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaperPageRepository extends JpaRepository<PaperPage, Long> {
    List<PaperPage> findByPaperIdAndModelVersionOrderByPageNumberAsc(String paperId, String modelVersion);

    long countByPaperIdAndModelVersion(String paperId, String modelVersion);
}
