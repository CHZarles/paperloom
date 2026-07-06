package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperReadingElement;
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElement;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElementType;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperFigure;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperFormula;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperMetadata;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperTable;
import com.yizhaoqi.smartpai.repository.PaperLocationRepository;
import com.yizhaoqi.smartpai.repository.PaperPageRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingElementRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingModelRepository;
import com.yizhaoqi.smartpai.repository.PaperSectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paper_reading_model_element_service;MODE=MySQL;INIT=CREATE SCHEMA IF NOT EXISTS paperloom_eval;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({PaperReadingModelService.class, PaperReadingModelBuilder.class, PaperReadingElementSearchService.class})
class PaperReadingModelElementServiceTest {

    @Autowired
    private PaperReadingModelService service;

    @Autowired
    private PaperReadingElementRepository readingElementRepository;

    @Autowired
    private PaperReadingElementSearchService readingElementSearchService;

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
                "user-a",
                "lab",
                false
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
        assertEquals(3, locationRepository.countByPaperIdAndModelVersion("paper-elements", model.getModelVersion()));
    }

    @Test
    void routedSearchMapsAttachedPanelOnlyChartToParentFigureLocation() {
        PaperReadingModel model = service.replaceFromParsedPaper(
                "paper-panel",
                parsedPaperWithPanelOnlyChart(),
                "user-a",
                "lab",
                false
        );

        List<PaperReadingElementSearchResult> matches = readingElementSearchService.searchCurrentModel(
                "paper-panel",
                "Recall"
        );

        assertEquals(1, matches.size());
        PaperReadingElementSearchResult match = matches.get(0);
        assertEquals(model.getModelVersion(), match.element().getModelVersion());
        assertEquals("(a) Recall", match.element().getSearchableText());
        assertEquals("PARENT_LOCATION", match.routingSource());
        assertEquals(PaperLocationType.FIGURE, match.routedLocationType());
        assertTrue(match.routedLocationRef().startsWith("figure_ref_"));
    }

    @Test
    void routedSearchMapsUnattachedPanelOnlyChartToContainingPageLocation() {
        service.replaceFromParsedPaper(
                "paper-unattached-panel",
                parsedPaperWithUnattachedPanelOnlyChart(),
                "user-a",
                "lab",
                false
        );

        List<PaperReadingElementSearchResult> matches = readingElementSearchService.searchCurrentModel(
                "paper-unattached-panel",
                "Precision"
        );

        assertEquals(1, matches.size());
        PaperReadingElementSearchResult match = matches.get(0);
        assertEquals("(b) Precision", match.element().getSearchableText());
        assertEquals("UNATTACHED", match.element().getAssociationStatus());
        assertEquals("PAGE_LOCATION", match.routingSource());
        assertEquals(PaperLocationType.PAGE, match.routedLocationType());
        assertTrue(match.routedLocationRef().startsWith("page_ref_"));
    }

    @Test
    void routedSearchMapsChildAttachedToParentWithoutLocationToContainingPageLocation() {
        service.replaceFromParsedPaper(
                "paper-parent-without-location",
                parsedPaperWithPanelParentMissingFigureId(),
                "user-a",
                "lab",
                false
        );

        List<PaperReadingElementSearchResult> matches = readingElementSearchService.searchCurrentModel(
                "paper-parent-without-location",
                "Recall"
        );

        assertEquals(1, matches.size());
        PaperReadingElementSearchResult match = matches.get(0);
        assertEquals("(a) Recall", match.element().getSearchableText());
        assertEquals("ATTACHED", match.element().getAssociationStatus());
        assertTrue(match.element().getParentReadingElementId().startsWith("reading_element_"));
        assertEquals("PARENT_LOCATION", match.routingSource());
        assertEquals(PaperLocationType.FIGURE, match.routedLocationType());
    }

    @Test
    void routedSearchMapsAttachedTableCaptionToParentTableLocation() {
        service.replaceFromParsedPaper(
                "paper-table-route",
                parsedPaperWithSeparateTableCaption(),
                "user-a",
                "lab",
                false
        );

        List<PaperReadingElementSearchResult> matches = readingElementSearchService.searchCurrentModel(
                "paper-table-route",
                "Model scores"
        );

        PaperReadingElementSearchResult captionMatch = matches.stream()
                .filter(match -> "table-caption-el".equals(match.element().getSourceObjectId()))
                .findFirst()
                .orElseThrow();
        assertEquals("PARENT_LOCATION", captionMatch.routingSource());
        assertEquals(PaperLocationType.TABLE, captionMatch.routedLocationType());
        assertTrue(captionMatch.routedLocationRef().startsWith("table_ref_"));
    }

    @Test
    void routedSearchMapsFormulaWithoutOwnLocationToContainingPageLocation() {
        service.replaceFromParsedPaper(
                "paper-formula-route",
                parsedPaperWithFormula(),
                "user-a",
                "lab",
                false
        );

        List<PaperReadingElementSearchResult> matches = readingElementSearchService.searchCurrentModel(
                "paper-formula-route",
                "alpha"
        );

        assertEquals(1, matches.size());
        PaperReadingElementSearchResult match = matches.get(0);
        assertEquals("FORMULA", match.element().getElementType());
        assertEquals("FORMULA_LOCATION_DEFERRED", match.element().getLocationNotCreatedReason());
        assertEquals("PAGE_LOCATION", match.routingSource());
        assertEquals(PaperLocationType.PAGE, match.routedLocationType());
        assertTrue(match.routedLocationRef().startsWith("page_ref_"));
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

    private ParsedPaper parsedPaperWithPanelOnlyChart() {
        return new ParsedPaper(
                "MinerU",
                "self-hosted",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 1, null, null),
                List.of(new ParsedPaperElement(
                        "p1",
                        1,
                        1,
                        ParsedPaperElementType.PARAGRAPH,
                        "Readable page text.",
                        null,
                        null,
                        null,
                        Map.of()
                )),
                Map.of(),
                "{}",
                List.of(),
                List.of(
                        new ParsedPaperFigure(
                                "figure-1",
                                "fig-el-1",
                                1,
                                2,
                                "Figure 1: Rule-wise metrics.",
                                null,
                                "Figure 1: Rule-wise metrics.",
                                null,
                                "MINERU_CHART",
                                "HIGH",
                                Map.of("type", "chart", "chart_caption", List.of("Figure 1: Rule-wise metrics."))
                        ),
                        new ParsedPaperFigure(
                                "figure-panel",
                                "fig-el-2",
                                1,
                                3,
                                null,
                                null,
                                null,
                                null,
                                "MINERU_CHART",
                                "HIGH",
                                Map.of("type", "chart", "chart_caption", List.of("(a) Recall"))
                        )
                ),
                List.of()
        );
    }

    private ParsedPaper parsedPaperWithUnattachedPanelOnlyChart() {
        return new ParsedPaper(
                "MinerU",
                "self-hosted",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 1, null, null),
                List.of(new ParsedPaperElement(
                        "p1",
                        1,
                        1,
                        ParsedPaperElementType.PARAGRAPH,
                        "Readable page text.",
                        null,
                        null,
                        null,
                        Map.of()
                )),
                Map.of(),
                "{}",
                List.of(),
                List.of(new ParsedPaperFigure(
                        "figure-panel",
                        "fig-el-1",
                        1,
                        20,
                        null,
                        null,
                        null,
                        null,
                        "MINERU_CHART",
                        "HIGH",
                        Map.of("type", "chart", "chart_caption", List.of("(b) Precision"))
                )),
                List.of()
        );
    }

    private ParsedPaper parsedPaperWithPanelParentMissingFigureId() {
        return new ParsedPaper(
                "MinerU",
                "self-hosted",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 1, null, null),
                List.of(new ParsedPaperElement(
                        "p1",
                        1,
                        1,
                        ParsedPaperElementType.PARAGRAPH,
                        "Readable page text.",
                        null,
                        null,
                        null,
                        Map.of()
                )),
                Map.of(),
                "{}",
                List.of(),
                List.of(
                        new ParsedPaperFigure(
                                "",
                                "fig-el-1",
                                1,
                                2,
                                "Figure 1: Rule-wise metrics.",
                                null,
                                "Figure 1: Rule-wise metrics.",
                                null,
                                "MINERU_CHART",
                                "HIGH",
                                Map.of("type", "chart", "chart_caption", List.of("Figure 1: Rule-wise metrics."))
                        ),
                        new ParsedPaperFigure(
                                "figure-panel",
                                "fig-el-2",
                                1,
                                3,
                                null,
                                null,
                                null,
                                null,
                                "MINERU_CHART",
                                "HIGH",
                                Map.of("type", "chart", "chart_caption", List.of("(a) Recall"))
                        )
                ),
                List.of()
        );
    }

    private ParsedPaper parsedPaperWithFormula() {
        return new ParsedPaper(
                "MinerU",
                "self-hosted",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 1, null, null),
                List.of(new ParsedPaperElement(
                        "p1",
                        1,
                        1,
                        ParsedPaperElementType.PARAGRAPH,
                        "Readable page text.",
                        null,
                        null,
                        null,
                        Map.of()
                )),
                Map.of(),
                "{}",
                List.of(),
                List.of(),
                List.of(new ParsedPaperFormula(
                        "formula-1",
                        "formula-el-1",
                        1,
                        2,
                        "alpha + beta = gamma",
                        "Equation context",
                        null,
                        null,
                        Map.of("type", "equation", "text", "alpha + beta = gamma")
                ))
        );
    }
}
