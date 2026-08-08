package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.paper.parser.ParsedPaper;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperElement;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperElementType;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperMetadata;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperTable;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperPageRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperSectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paper_reading_model_element_service;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({PaperReadingModelService.class, PaperReadingModelBuilder.class, PaperPassageService.class,
        StructuralPassageBuilder.class})
class PaperReadingModelElementServiceTest {

    @Autowired
    private PaperReadingModelService service;

    @Autowired
    private PaperReadingElementRepository readingElementRepository;

    @Autowired
    private PaperReadingModelRepository modelRepository;

    @Autowired
    private PaperPageRepository pageRepository;

    @Autowired
    private PaperSectionRepository sectionRepository;

    @Autowired
    private PaperLocationRepository locationRepository;

    @Test
    void retainedChildElementSearchPreservesParentLocationForRouting() {
        PaperReadingModel model = service.replaceFromParsedPaper(
                "paper-elements",
                parsedPaperWithSeparateTableCaption(),
                "user-a"
        );

        List<PaperReadingElement> matches = readingElementRepository.searchByPaperIdAndModelVersion(
                "paper-elements",
                model.getModelVersion(),
                "Model scores"
        );

        assertEquals(2, matches.size());
        PaperReadingElement caption = matches.stream()
                .filter(element -> "table-caption-el".equals(element.getSourceObjectId()))
                .findFirst()
                .orElseThrow();
        assertEquals("ATTACHED", caption.getAssociationStatus());
        assertEquals("TABLE_CAPTION", caption.getAttachmentRole());
        assertTrue(caption.getParentReadingElementId().startsWith("reading_element_"));

        assertEquals(1, modelRepository.findByPaperIdOrderByCreatedAtDesc("paper-elements").size());
        assertEquals(1, pageRepository.countByPaperIdAndModelVersion("paper-elements", model.getModelVersion()));
        assertEquals(1, sectionRepository.countByPaperIdAndModelVersion("paper-elements", model.getModelVersion()));
        assertEquals(4, locationRepository.countByPaperIdAndModelVersion("paper-elements", model.getModelVersion()));
    }

    private ParsedPaper parsedPaperWithSeparateTableCaption() {
        return new ParsedPaper(
                "MinerU",
                "self-hosted",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 1, null, null),
                List.of(
                        new ParsedPaperElement(
                                "h1",
                                1,
                                1,
                                ParsedPaperElementType.HEADING,
                                "Results",
                                null,
                                1,
                                null,
                                Map.of()
                        ),
                        new ParsedPaperElement(
                                "table-caption-el",
                                1,
                                2,
                                ParsedPaperElementType.CAPTION,
                                "Table 2: Model scores.",
                                "Results",
                                null,
                                null,
                                Map.of("type", "caption")
                        )
                ),
                Map.of(),
                "{}",
                List.of(new ParsedPaperTable(
                        "table-2",
                        "table-el-2",
                        1,
                        3,
                        "Table 2: Model scores.",
                        "Results",
                        1,
                        2,
                        "Model | Score",
                        null,
                        null,
                        Map.of("type", "table", "table_caption", List.of("Table 2: Model scores."))
                )),
                List.of(),
                List.of()
        );
    }
}
