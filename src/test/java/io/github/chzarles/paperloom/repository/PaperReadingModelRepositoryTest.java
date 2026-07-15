package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperPage;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperRetrievalIndexStatus;
import io.github.chzarles.paperloom.model.PaperSection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
class PaperReadingModelRepositoryTest {

    @Autowired
    private PaperReadingModelRepository modelRepository;

    @Autowired
    private PaperPageRepository pageRepository;

    @Autowired
    private PaperSectionRepository sectionRepository;

    @Autowired
    private PaperLocationRepository locationRepository;

    @Test
    void savesVersionedCurrentModelPagesAndPageLocations() {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper-a");
        model.setModelVersion("rm_test_1");
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setCurrent(true);
        model.setParserName("MinerU");
        model.setParserVersion("1.3.0");
        model.setPageCount(1);
        model.setReadablePageCount(1);
        model.setReadableCharCount(12);
        model.setRetrievalIndexStatus(PaperRetrievalIndexStatus.READY);
        model.setRetrievalIndexGeneration("generation-1");
        model.setRetrievalEmbeddingContract("collection|embedding-v1|3");
        model.setRetrievalIndexedLocationCount(1);
        model.setRetrievalIndexedAt(LocalDateTime.of(2026, 7, 15, 12, 0));
        modelRepository.save(model);

        PaperPage page = new PaperPage();
        page.setPaperId("paper-a");
        page.setModelVersion("rm_test_1");
        page.setPageNumber(1);
        page.setPageText("Hello paper.");
        page.setTextHash("hash-a");
        page.setCharCount(12);
        page.setTextStatus(PaperPage.TEXT_STATUS_READABLE);
        page.setSourceSpanJson("{\"pageNumber\":1}");
        page.setParserName("MinerU");
        page.setParserVersion("1.3.0");
        page.setUserId("user-a");
        page.setOrgTag("lab");
        page.setPublic(true);
        pageRepository.save(page);

        PaperSection section = new PaperSection();
        section.setPaperId("paper-a");
        section.setModelVersion("rm_test_1");
        section.setSectionId("section_test_1");
        section.setSectionTitle("Intro");
        section.setSectionLevel(1);
        section.setPageNumberFrom(1);
        section.setPageNumberTo(1);
        section.setReadingOrderFrom(1);
        section.setReadingOrderTo(2);
        section.setDisplayOrder(2);
        section.setSectionText("Intro\n\nHello paper.");
        section.setTextHash("hash-section-a");
        section.setCharCount(19);
        section.setSourceSpanJson("{\"locationType\":\"SECTION\"}");
        section.setParserName("MinerU");
        section.setParserVersion("1.3.0");
        section.setUserId("user-a");
        section.setOrgTag("lab");
        section.setPublic(true);
        sectionRepository.save(section);

        PaperLocation location = new PaperLocation();
        location.setLocationRef("page_ref_test_1");
        location.setPaperId("paper-a");
        location.setModelVersion("rm_test_1");
        location.setLocationType(PaperLocationType.PAGE);
        location.setPageNumber(1);
        location.setPageEndNumber(1);
        location.setDisplayOrder(1);
        location.setSourceSpanJson("{\"pageNumber\":1}");
        location.setContentKind("PAGE_TEXT");
        location.setUserId("user-a");
        location.setOrgTag("lab");
        location.setPublic(true);
        locationRepository.save(location);

        PaperReadingModel persisted = modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a").orElseThrow();
        assertEquals(PaperRetrievalIndexStatus.READY, persisted.getRetrievalIndexStatus());
        assertEquals("generation-1", persisted.getRetrievalIndexGeneration());
        assertEquals("collection|embedding-v1|3", persisted.getRetrievalEmbeddingContract());
        assertEquals(1, persisted.getRetrievalIndexedLocationCount());
        assertEquals(1, pageRepository.findByPaperIdAndModelVersionOrderByPageNumberAsc("paper-a", "rm_test_1").size());
        assertEquals(1, sectionRepository.findByPaperIdAndModelVersionOrderByPageNumberFromAscDisplayOrderAsc("paper-a", "rm_test_1").size());
        assertEquals(1, locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc("paper-a", "rm_test_1").size());

        int cleared = modelRepository.clearCurrentModels("paper-a", "rm_next");

        assertEquals(1, cleared);
        assertFalse(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a").isPresent());
    }

    @Test
    void activatesOnlyWhenTheExpectedGenerationIsStillCurrent() {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper-cas");
        model.setModelVersion("rm-cas-1");
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setCurrent(true);
        modelRepository.saveAndFlush(model);

        int first = modelRepository.activateRetrievalIndex(
                "paper-cas",
                "rm-cas-1",
                null,
                "generation-first",
                "collection|embedding-v2|3",
                7,
                LocalDateTime.of(2026, 7, 15, 13, 0)
        );
        int stale = modelRepository.activateRetrievalIndex(
                "paper-cas",
                "rm-cas-1",
                null,
                "generation-loser",
                "collection|embedding-v2|3",
                7,
                LocalDateTime.of(2026, 7, 15, 13, 1)
        );
        int replacement = modelRepository.activateRetrievalIndex(
                "paper-cas",
                "rm-cas-1",
                "generation-first",
                "generation-new",
                "collection|embedding-v3|3",
                8,
                LocalDateTime.of(2026, 7, 15, 13, 2)
        );

        PaperReadingModel activated = modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-cas").orElseThrow();
        assertEquals(1, first);
        assertEquals(0, stale);
        assertEquals(1, replacement);
        assertEquals("generation-new", activated.getRetrievalIndexGeneration());
        assertEquals("collection|embedding-v3|3", activated.getRetrievalEmbeddingContract());
        assertEquals(8, activated.getRetrievalIndexedLocationCount());
    }
}
