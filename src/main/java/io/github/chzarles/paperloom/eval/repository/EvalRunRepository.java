package io.github.chzarles.paperloom.eval.repository;

import io.github.chzarles.paperloom.eval.model.EvalRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EvalRunRepository extends JpaRepository<EvalRun, Long> {

    Optional<EvalRun> findTopByCorpusAndSplitAndStrategyOrderByCreatedAtDesc(String corpus,
                                                                              String split,
                                                                              String strategy);
}
