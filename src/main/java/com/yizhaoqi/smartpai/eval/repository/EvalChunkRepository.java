package com.yizhaoqi.smartpai.eval.repository;

import com.yizhaoqi.smartpai.eval.model.EvalChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface EvalChunkRepository extends JpaRepository<EvalChunk, Long> {

    List<EvalChunk> findByCorpusAndPaperIdOrderByChunkIdAsc(String corpus, String paperId);

    List<EvalChunk> findByCorpusAndPaperIdIn(String corpus, List<String> paperIds);

    long countByCorpusAndSplit(String corpus, String split);

    @Transactional
    void deleteByCorpusAndPaperId(String corpus, String paperId);
}
