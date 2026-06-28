package com.yizhaoqi.smartpai.eval.repository;

import com.yizhaoqi.smartpai.eval.model.EvalPaper;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface EvalPaperRepository extends JpaRepository<EvalPaper, Long> {

    List<EvalPaper> findByCorpusAndSplit(String corpus, String split);

    List<EvalPaper> findByCorpusAndSplitAndPaperIdIn(String corpus, String split, List<String> paperIds);

    Optional<EvalPaper> findByCorpusAndPaperId(String corpus, String paperId);

    long countByCorpusAndSplit(String corpus, String split);

    @Transactional
    void deleteByCorpusAndPaperId(String corpus, String paperId);
}
