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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        model.setRetrievalIndexContract("collection|location-bm25-v1");
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
        locationRepository.save(location);

        PaperReadingModel persisted = modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a").orElseThrow();
        assertEquals(PaperRetrievalIndexStatus.READY, persisted.getRetrievalIndexStatus());
        assertEquals("collection|location-bm25-v1", persisted.getRetrievalIndexContract());
        assertEquals(1, persisted.getRetrievalIndexedLocationCount());
        assertEquals(1, pageRepository.findByPaperIdAndModelVersionOrderByPageNumberAsc("paper-a", "rm_test_1").size());
        assertEquals(1, sectionRepository.findByPaperIdAndModelVersionOrderByPageNumberFromAscDisplayOrderAsc("paper-a", "rm_test_1").size());
        assertEquals(1, locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc("paper-a", "rm_test_1").size());

        int cleared = modelRepository.clearCurrentModels("paper-a", "rm_next");

        assertEquals(1, cleared);
        assertFalse(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a").isPresent());
    }

    @Test
    void claimsAndFinalizesOnlyTheOwningIndexJob() {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper-cas");
        model.setModelVersion("rm-cas-1");
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setCurrent(true);
        model.setRetrievalIndexStatus(PaperRetrievalIndexStatus.PENDING);
        modelRepository.saveAndFlush(model);

        int first = modelRepository.claimInitialIndex(
                "paper-cas", "rm-cas-1", "job-first", LocalDateTime.of(2026, 7, 15, 13, 0));
        int duplicate = modelRepository.claimInitialIndex(
                "paper-cas", "rm-cas-1", "job-loser", LocalDateTime.of(2026, 7, 15, 13, 1));
        int stale = modelRepository.finishRetrievalIndexReady(
                "paper-cas", "rm-cas-1", "BUILDING", "job-loser",
                "collection|location-bm25-v2", 7, LocalDateTime.of(2026, 7, 15, 13, 2));
        int completed = modelRepository.finishRetrievalIndexReady(
                "paper-cas", "rm-cas-1", "BUILDING", "job-first",
                "collection|location-bm25-v3", 8, LocalDateTime.of(2026, 7, 15, 13, 3));

        PaperReadingModel activated = modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-cas").orElseThrow();
        assertEquals(1, first);
        assertEquals(0, duplicate);
        assertEquals(0, stale);
        assertEquals(1, completed);
        assertEquals(PaperRetrievalIndexStatus.READY, activated.getRetrievalIndexStatus());
        assertEquals("collection|location-bm25-v3", activated.getRetrievalIndexContract());
        assertEquals(8, activated.getRetrievalIndexedLocationCount());
    }

    @Test
    void findsSearchableCurrentPaperIdsWithActiveIndexContract() {
        modelRepository.save(searchableModel("paper-ready", "contract-a"));
        modelRepository.save(searchableModel("paper-ready", "contract-a"));
        modelRepository.save(searchableModel("paper-other-contract", "contract-b"));
        modelRepository.save(model("paper-building", true, PaperReadingModelStatus.READING_MODEL_BUILDING,
                PaperRetrievalIndexStatus.READY, "contract-a", 1));
        modelRepository.save(model("paper-failed-index", true, PaperReadingModelStatus.READING_MODEL_READY,
                PaperRetrievalIndexStatus.FAILED, "contract-a", 1));
        modelRepository.save(model("paper-empty-index", true, PaperReadingModelStatus.READING_MODEL_READY,
                PaperRetrievalIndexStatus.READY, "contract-a", 0));
        modelRepository.save(model("paper-old", false, PaperReadingModelStatus.READING_MODEL_READY,
                PaperRetrievalIndexStatus.READY, "contract-a", 1));
        modelRepository.flush();

        Set<String> result = new HashSet<>(modelRepository.findSearchableCurrentPaperIds(
                List.of(
                        "paper-ready",
                        "paper-other-contract",
                        "paper-building",
                        "paper-failed-index",
                        "paper-empty-index",
                        "paper-old"
                ),
                PaperReadingModelStatus.READING_MODEL_READY,
                PaperRetrievalIndexStatus.READY,
                "contract-a"
        ));

        assertEquals(Set.of("paper-ready"), result);
    }

    private PaperReadingModel searchableModel(String paperId, String contract) {
        return model(paperId, true, PaperReadingModelStatus.READING_MODEL_READY,
                PaperRetrievalIndexStatus.READY, contract, 1);
    }

    private PaperReadingModel model(String paperId,
                                    boolean current,
                                    PaperReadingModelStatus modelStatus,
                                    PaperRetrievalIndexStatus indexStatus,
                                    String contract,
                                    Integer indexedLocations) {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId(paperId);
        model.setModelVersion("rm-" + paperId + "-" + current + "-" + indexedLocations);
        model.setModelStatus(modelStatus);
        model.setCurrent(current);
        model.setRetrievalIndexStatus(indexStatus);
        model.setRetrievalIndexContract(contract);
        model.setRetrievalIndexedLocationCount(indexedLocations);
        return model;
    }
}
