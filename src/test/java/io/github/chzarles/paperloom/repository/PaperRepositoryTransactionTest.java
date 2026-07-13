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
        paper.setOrgTag("eval-litsearch");
        paper.setPublic(false);
        paper.setStatus(Paper.STATUS_COMPLETED);

        paperRepository.saveAndFlush(paper);

        paperRepository.deleteByPaperId("litsearch:gold-1");

        assertEquals(0, paperRepository.countByPaperId("litsearch:gold-1"));
    }
}
