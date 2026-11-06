package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.Paper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@ActiveProfiles("test")
class PaperRepositoryTransactionTest {

    @Autowired
    private PaperRepository paperRepository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deleteByPaperIdCanRunWithoutCallerTransaction() {
        Paper paper = new Paper();
        paper.setPaperId("litsearch:gold-1");
        paper.setOriginalFilename("litsearch:gold-1.json");
        paper.setUserId("eval-user");
        paper.setStatus(Paper.STATUS_COMPLETED);

        paperRepository.saveAndFlush(paper);

        paperRepository.deleteByPaperId("litsearch:gold-1");

        assertEquals(0, paperRepository.countByPaperId("litsearch:gold-1"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void backfillArxivIdOnlyWritesTheIdentifier() {
        Paper paper = new Paper();
        paper.setPaperId("md5-backfill");
        paper.setOriginalFilename("manual.pdf");
        paper.setUserId("u1");
        paper.setStatus(Paper.STATUS_COMPLETED);
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_PROCESSING);
        paperRepository.saveAndFlush(paper);

        int updated = paperRepository.backfillArxivIdIfAbsent("md5-backfill", "u1", "2409.04701");

        assertEquals(1, updated);
        Paper reloaded = paperRepository
                .findFirstByPaperIdAndUserIdOrderByCreatedAtDesc("md5-backfill", "u1")
                .orElseThrow();
        assertEquals("2409.04701", reloaded.getArxivId());
        assertEquals(Paper.VECTORIZATION_STATUS_PROCESSING, reloaded.getVectorizationStatus());
    }
}
