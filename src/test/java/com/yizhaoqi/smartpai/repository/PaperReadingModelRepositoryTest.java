package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperPage;
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperReadingModelStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

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
        modelRepository.save(model);

        PaperPage page = new PaperPage();
        page.setPaperId("paper-a");
        page.setModelVersion("rm_test_1");
        page.setPageNumber(1);
        page.setPageText("Hello paper.");
        page.setTextHash("hash-a");
        page.setCharCount(12);
        page.setSourceSpanJson("{\"pageNumber\":1}");
        page.setParserName("MinerU");
        page.setParserVersion("1.3.0");
        page.setUserId("user-a");
        page.setOrgTag("lab");
        page.setPublic(true);
        pageRepository.save(page);

        PaperLocation location = new PaperLocation();
        location.setLocationRef("page_ref_test_1");
        location.setPaperId("paper-a");
        location.setModelVersion("rm_test_1");
        location.setLocationType(PaperLocationType.PAGE);
        location.setPageNumber(1);
        location.setSourceSpanJson("{\"pageNumber\":1}");
        location.setContentKind("PAGE_TEXT");
        location.setUserId("user-a");
        location.setOrgTag("lab");
        location.setPublic(true);
        locationRepository.save(location);

        assertTrue(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a").isPresent());
        assertEquals(1, pageRepository.findByPaperIdAndModelVersionOrderByPageNumberAsc("paper-a", "rm_test_1").size());
        assertEquals(1, locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc("paper-a", "rm_test_1").size());

        int cleared = modelRepository.clearCurrentModels("paper-a", "rm_next");

        assertEquals(1, cleared);
        assertFalse(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a").isPresent());
    }
}
