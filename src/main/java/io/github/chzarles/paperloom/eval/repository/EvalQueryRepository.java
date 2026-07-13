package io.github.chzarles.paperloom.eval.repository;

import io.github.chzarles.paperloom.eval.model.EvalQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvalQueryRepository extends JpaRepository<EvalQuery, Long> {

    List<EvalQuery> findByCorpusAndSplit(String corpus, String split);

    Optional<EvalQuery> findByCorpusAndSplitAndQueryId(String corpus, String split, String queryId);

    long countByCorpusAndSplit(String corpus, String split);
}
