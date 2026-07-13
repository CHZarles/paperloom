package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperSourceQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaperSourceQuoteRepository extends JpaRepository<PaperSourceQuote, Long> {
    Optional<PaperSourceQuote> findFirstBySourceQuoteRef(String sourceQuoteRef);

    Optional<PaperSourceQuote> findFirstByPaperIdAndModelVersionAndLocationRefAndSplitPolicyVersionAndSplitIndexAndContentHash(
            String paperId,
            String modelVersion,
            String locationRef,
            String splitPolicyVersion,
            Integer splitIndex,
            String contentHash
    );
}
