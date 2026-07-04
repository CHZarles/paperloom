package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperPage;
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperReadingModelStatus;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElement;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElementType;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperMetadata;
import com.yizhaoqi.smartpai.repository.PaperLocationRepository;
import com.yizhaoqi.smartpai.repository.PaperPageRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingModelRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paper_reading_model_service;MODE=MySQL;INIT=CREATE SCHEMA IF NOT EXISTS paperloom_eval;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({PaperReadingModelService.class, PaperReadingModelBuilder.class})
class PaperReadingModelServicePersistenceTest {

    @Autowired
    private PaperReadingModelService service;

    @Autowired
    private PaperReadingModelRepository modelRepository;

    @Autowired
    private PaperPageRepository pageRepository;

    @Autowired
    private PaperLocationRepository locationRepository;

    @Test
    void successfulBuildPersistsCurrentModelPagesAndPageLocations() {
        PaperReadingModel model = service.replaceFromParsedPaper(
                "paper-a",
                parsedPaper("Readable text.", 1),
                "user-a",
                "lab",
                true
        );

        assertEquals(PaperReadingModelStatus.READING_MODEL_READY, model.getModelStatus());
        assertTrue(model.isCurrent());
        assertTrue(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a").isPresent());

        List<PaperPage> pages = pageRepository.findByPaperIdAndModelVersionOrderByPageNumberAsc(
                "paper-a",
                model.getModelVersion()
        );
        List<PaperLocation> locations = locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(
                "paper-a",
                model.getModelVersion()
        );

        assertEquals(1, pages.size());
        assertEquals("Readable text.", pages.get(0).getPageText());
        assertEquals(1, locations.size());
        assertEquals(PaperLocationType.PAGE, locations.get(0).getLocationType());
        assertEquals(pages.get(0).getSourceSpanJson(), locations.get(0).getSourceSpanJson());
    }

    @Test
    void failedRebuildDoesNotReplacePreviousCurrentModel() {
        PaperReadingModel first = service.replaceFromParsedPaper(
                "paper-a",
                parsedPaper("Readable text.", 1),
                "user-a",
                "lab",
                false
        );

        PaperReadingModel failed = service.replaceFromParsedPaper(
                "paper-a",
                parsedPaper(" ", 1),
                "user-a",
                "lab",
                false
        );

        PaperReadingModel current = modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a").orElseThrow();
        assertEquals(first.getModelVersion(), current.getModelVersion());
        assertEquals(PaperReadingModelStatus.READING_MODEL_FAILED, failed.getModelStatus());
        assertEquals("NO_READABLE_NUMBERED_TEXT", failed.getFailureReason());
        assertFalse(failed.isCurrent());
        assertEquals(0, pageRepository.countByPaperIdAndModelVersion("paper-a", failed.getModelVersion()));
        assertEquals(0, locationRepository.countByPaperIdAndModelVersion("paper-a", failed.getModelVersion()));
    }

    @Test
    void successfulRebuildCreatesOnlyOneCurrentModel() {
        PaperReadingModel first = service.replaceFromParsedPaper(
                "paper-a",
                parsedPaper("First version.", 1),
                "user-a",
                "lab",
                false
        );
        PaperReadingModel second = service.replaceFromParsedPaper(
                "paper-a",
                parsedPaper("Second version.", 1),
                "user-a",
                "lab",
                false
        );

        PaperReadingModel current = modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a").orElseThrow();
        long currentCount = modelRepository.findByPaperIdOrderByCreatedAtDesc("paper-a").stream()
                .filter(PaperReadingModel::isCurrent)
                .count();

        assertFalse(first.getModelVersion().equals(second.getModelVersion()));
        assertEquals(second.getModelVersion(), current.getModelVersion());
        assertEquals(1, currentCount);
    }

    private ParsedPaper parsedPaper(String text, Integer pageNumber) {
        return new ParsedPaper(
                "MinerU",
                "1.3.0",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 1, null, null),
                List.of(new ParsedPaperElement(
                        "p1",
                        pageNumber,
                        1,
                        ParsedPaperElementType.PARAGRAPH,
                        text,
                        null,
                        null,
                        null,
                        Map.of()
                )),
                Map.of(),
                "{}",
                List.of(),
                List.of(),
                List.of()
        );
    }
}
