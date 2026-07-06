package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.PaperReadingElement;
import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperSection;
import com.yizhaoqi.smartpai.paper.parser.BoundingBox;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElement;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElementType;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperFigure;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperFormula;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperMetadata;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperReadingModelStructuredLocationBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaperReadingModelBuilder builder = new PaperReadingModelBuilder();

    @Test
    void buildsSectionRowsAndLocationsFromHeadingsAcrossPages() throws Exception {
        ParsedPaper paper = parsedPaper(
                List.of(
                        element("h1", 1, 1, ParsedPaperElementType.HEADING, "Methods", null, 1),
                        element("p1", 1, 2, ParsedPaperElementType.PARAGRAPH, "Method first page.", "Methods", null),
                        element("p2", 2, 3, ParsedPaperElementType.PARAGRAPH, "Method second page.", "Methods", null),
                        element("h2", 3, 4, ParsedPaperElementType.HEADING, "Results", null, 1),
                        element("p3", 3, 5, ParsedPaperElementType.PARAGRAPH, "Result text.", "Results", null)
                ),
                List.of(),
                List.of()
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        assertEquals(2, result.sections().size());
        PaperSection methods = result.sections().get(0);
        assertEquals("Methods", methods.getSectionTitle());
        assertEquals(1, methods.getPageNumberFrom());
        assertEquals(2, methods.getPageNumberTo());
        assertEquals("Methods\n\nMethod first page.\n\nMethod second page.", methods.getSectionText());
        assertTrue(methods.getSectionId().startsWith("section_"));

        List<PaperLocation> sectionLocations = result.locations().stream()
                .filter(location -> location.getLocationType() == PaperLocationType.SECTION)
                .toList();
        assertEquals(2, sectionLocations.size());
        PaperLocation methodsLocation = sectionLocations.get(0);
        assertTrue(methodsLocation.getLocationRef().startsWith("section_ref_"));
        assertEquals(methods.getSectionId(), methodsLocation.getSourceObjectId());
        assertEquals(1, methodsLocation.getPageNumber());
        assertEquals(2, methodsLocation.getPageEndNumber());
        assertEquals("SECTION_TEXT", methodsLocation.getContentKind());

        JsonNode sourceSpan = objectMapper.readTree(methodsLocation.getSourceSpanJson());
        assertEquals("SECTION", sourceSpan.path("locationType").asText());
        assertEquals(methods.getSectionId(), sourceSpan.path("sourceObjectId").asText());
        assertEquals(1, sourceSpan.path("pageNumberFrom").asInt());
        assertEquals(2, sourceSpan.path("pageNumberTo").asInt());
        assertEquals("h1", sourceSpan.path("elementIds").get(0).asText());
        assertEquals("p2", sourceSpan.path("elementIds").get(2).asText());

        JsonNode diagnostics = objectMapper.readTree(result.diagnosticsJson());
        assertEquals(2, diagnostics.path("sectionCount").asInt());
        assertEquals(2, diagnostics.path("sectionLocationCount").asInt());
    }

    @Test
    void keepsDuplicateSectionTitlesAsDistinctSections() {
        ParsedPaper paper = parsedPaper(
                List.of(
                        element("h1", 1, 1, ParsedPaperElementType.HEADING, "Method", null, 1),
                        element("p1", 1, 2, ParsedPaperElementType.PARAGRAPH, "First method.", "Method", null),
                        element("h2", 2, 3, ParsedPaperElementType.HEADING, "Method", null, 1),
                        element("p2", 2, 4, ParsedPaperElementType.PARAGRAPH, "Second method.", "Method", null)
                ),
                List.of(),
                List.of()
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        assertEquals(2, result.sections().size());
        assertEquals("Method", result.sections().get(0).getSectionTitle());
        assertEquals("Method", result.sections().get(1).getSectionTitle());
        assertNotEquals(result.sections().get(0).getSectionId(), result.sections().get(1).getSectionId());
    }

    @Test
    void buildsTableAndFigureLocationsAndCountsLocationObjectsWithoutOwnLocations() throws Exception {
        ParsedPaper paper = parsedPaper(
                List.of(element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Readable page.", null, null)),
                List.of(
                        table("table-1", "t-el-1", 1, 2, "Table caption.", "A | B"),
                        table("table-no-page", "t-el-2", null, 3, "Caption", "Text"),
                        table("table-blank", "t-el-3", 2, 4, " ", " ")
                ),
                List.of(
                        figure("figure-1", "f-el-1", 1, 5, "Figure caption.", null),
                        figure(null, "f-el-2", 1, 6, "Caption", null),
                        figure("figure-blank", "f-el-3", 2, 7, " ", " ")
                )
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        PaperLocation tableLocation = onlyLocation(result, PaperLocationType.TABLE);
        PaperReadingElement tableElement = retainedElement(result, "table-1");
        assertTrue(tableLocation.getLocationRef().startsWith("table_ref_"));
        assertEquals(tableElement.getReadingElementId(), tableLocation.getSourceObjectId());
        assertEquals("TABLE", tableLocation.getContentKind());
        assertEquals(1, tableLocation.getPageNumber());
        assertEquals(1, tableLocation.getPageEndNumber());
        assertEquals("TABLE", objectMapper.readTree(tableLocation.getSourceSpanJson()).path("locationType").asText());

        List<PaperLocation> figureLocations = result.locations().stream()
                .filter(location -> location.getLocationType() == PaperLocationType.FIGURE)
                .toList();
        assertEquals(2, figureLocations.size());
        PaperReadingElement figureElement = retainedElement(result, "figure-1");
        PaperLocation figureLocation = figureLocations.stream()
                .filter(location -> figureElement.getReadingElementId().equals(location.getSourceObjectId()))
                .findFirst()
                .orElseThrow();
        assertTrue(figureLocation.getLocationRef().startsWith("figure_ref_"));
        assertEquals("FIGURE", figureLocation.getContentKind());
        assertEquals("FIGURE", objectMapper.readTree(figureLocation.getSourceSpanJson()).path("locationType").asText());

        JsonNode diagnostics = objectMapper.readTree(result.diagnosticsJson());
        assertEquals(3, diagnostics.path("tableCount").asInt());
        assertEquals(1, diagnostics.path("tableLocationCount").asInt());
        assertEquals(1, diagnostics.path("tableLocationNotCreatedNoPageCount").asInt());
        assertEquals(1, diagnostics.path("tableLocationNotCreatedBlankPayloadCount").asInt());
        assertEquals(3, diagnostics.path("figureCount").asInt());
        assertEquals(2, diagnostics.path("figureLocationCount").asInt());
        assertEquals(0, diagnostics.path("figureLocationNotCreatedNoIdCount").asInt());
        assertEquals(1, diagnostics.path("figureLocationNotCreatedBlankTextCount").asInt());
        assertEquals(3, diagnostics.path("structuredLocationCount").asInt());
    }

    @Test
    void retainsPanelOnlyChartAsSearchableChildOfParentFigureWithoutStandaloneLocation() throws Exception {
        ParsedPaper paper = parsedPaper(
                List.of(element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Readable page.", null, null)),
                List.of(),
                List.of(
                        figure(
                                "figure-1",
                                "f-el-1",
                                1,
                                2,
                                "Figure 1: Rule-wise metrics.",
                                null,
                                "MINERU_CHART",
                                Map.of("type", "chart", "chart_caption", List.of("Figure 1: Rule-wise metrics."))
                        ),
                        figure(
                                "figure-panel",
                                "f-el-2",
                                1,
                                3,
                                null,
                                null,
                                "MINERU_CHART",
                                Map.of("type", "chart", "chart_caption", List.of("(a) Recall"))
                        )
                )
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        PaperLocation parentLocation = onlyLocation(result, PaperLocationType.FIGURE);
        PaperReadingElement parent = retainedElement(result, "figure-1");
        PaperReadingElement panel = retainedElement(result, "figure-panel");

        assertEquals(parentLocation.getLocationRef(), parent.getLocationRef());
        assertEquals("SELF", parent.getAssociationStatus());
        assertEquals("(a) Recall", panel.getSearchableText());
        assertEquals("(a) Recall", panel.getCaptionText());
        assertEquals("CHART", panel.getElementType());
        assertEquals("chart_caption_panel_only", panel.getCaptionSource());
        assertEquals("PANEL_ONLY_CAPTION", panel.getLocationNotCreatedReason());
        assertEquals("PANEL_LABEL", panel.getAttachmentRole());
        assertEquals("ATTACHED", panel.getAssociationStatus());
        assertEquals(parent.getReadingElementId(), panel.getParentReadingElementId());
        assertTrue(panel.getRawAttributesJson().contains("chart_caption"));

        JsonNode diagnostics = objectMapper.readTree(result.diagnosticsJson());
        assertEquals(2, diagnostics.path("retainedFigureElementCount").asInt());
        assertEquals(1, diagnostics.path("retainedPanelOnlyChartCount").asInt());
        assertEquals(1, diagnostics.path("attachedPanelOnlyChartCount").asInt());
        assertEquals(1, diagnostics.path("figureLocationNotCreatedCount").asInt());
    }

    @Test
    void retainsTablesFormulasAndTextElementsAsSearchableReadingElements() {
        ParsedPaper paper = new ParsedPaper(
                "MinerU",
                "self-hosted",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 2, null, null),
                List.of(
                        element("h1", 1, 1, ParsedPaperElementType.HEADING, "Results", null, 1),
                        element("p1", 1, 2, ParsedPaperElementType.PARAGRAPH, "Main result text.", "Results", null)
                ),
                Map.of(),
                "{}",
                List.of(table("table-1", "t-el-1", 1, 3, "", "A | B")),
                List.of(),
                List.of(new ParsedPaperFormula(
                        "formula-1",
                        "eq-el-1",
                        1,
                        4,
                        "x = y + z",
                        "Equation context",
                        "Results",
                        null,
                        Map.of("type", "equation", "text", "x = y + z")
                ))
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        assertTrue(result.readingElements().stream()
                .anyMatch(element -> "HEADING".equals(element.getElementType())
                        && "Results".equals(element.getSearchableText())));
        assertTrue(result.readingElements().stream()
                .anyMatch(element -> "TABLE".equals(element.getElementType())
                        && "table-1".equals(element.getSourceObjectId())
                        && element.getSearchableText().contains("A | B")
                        && element.getLocationRef() != null));
        assertTrue(result.readingElements().stream()
                .anyMatch(element -> "FORMULA".equals(element.getElementType())
                        && "formula-1".equals(element.getSourceObjectId())
                        && element.getSearchableText().contains("x = y + z")
                        && "FORMULA_LOCATION_DEFERRED".equals(element.getLocationNotCreatedReason())));
    }

    @Test
    void pageReadinessDoesNotRequireStructuredLocations() throws Exception {
        ParsedPaper paper = parsedPaper(
                List.of(element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Readable page.", null, null)),
                List.of(),
                List.of()
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        assertEquals(1, result.pages().size());
        assertTrue(result.sections().isEmpty());
        assertEquals(1, result.locations().size());
        assertEquals(PaperLocationType.PAGE, result.locations().get(0).getLocationType());
        JsonNode diagnostics = objectMapper.readTree(result.diagnosticsJson());
        assertEquals(0, diagnostics.path("sectionCount").asInt());
        assertEquals(0, diagnostics.path("tableLocationCount").asInt());
        assertEquals(0, diagnostics.path("figureLocationCount").asInt());
        assertEquals(1, diagnostics.path("pageLocationCount").asInt());
    }

    private PaperLocation onlyLocation(PaperReadingModelBuildResult result, PaperLocationType type) {
        List<PaperLocation> locations = result.locations().stream()
                .filter(location -> location.getLocationType() == type)
                .toList();
        assertEquals(1, locations.size());
        return locations.get(0);
    }

    private PaperReadingElement retainedElement(PaperReadingModelBuildResult result, String sourceObjectId) {
        List<PaperReadingElement> matches = result.readingElements().stream()
                .filter(element -> sourceObjectId.equals(element.getSourceObjectId()))
                .toList();
        assertEquals(1, matches.size());
        return matches.get(0);
    }

    private ParsedPaper parsedPaper(List<ParsedPaperElement> elements,
                                    List<ParsedPaperTable> tables,
                                    List<ParsedPaperFigure> figures) {
        return new ParsedPaper(
                "MinerU",
                "self-hosted",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 4, null, null),
                elements,
                Map.of(),
                "{}",
                tables,
                figures,
                List.of()
        );
    }

    private ParsedPaperElement element(String id,
                                       Integer pageNumber,
                                       Integer order,
                                       ParsedPaperElementType type,
                                       String text,
                                       String sectionTitle,
                                       Integer sectionLevel) {
        return new ParsedPaperElement(
                id,
                pageNumber,
                order,
                type,
                text,
                sectionTitle,
                sectionLevel,
                pageNumber == null ? null : new BoundingBox(pageNumber, 1.0, 2.0, 3.0, 4.0, "pdf_points", "bottom_left"),
                Map.of()
        );
    }

    private ParsedPaperTable table(String tableId,
                                   String elementId,
                                   Integer pageNumber,
                                   Integer readingOrder,
                                   String caption,
                                   String tableText) {
        return new ParsedPaperTable(
                tableId,
                elementId,
                pageNumber,
                readingOrder,
                caption,
                "Results",
                2,
                2,
                tableText,
                null,
                pageNumber == null ? null : new BoundingBox(pageNumber, 10.0, 20.0, 30.0, 40.0, "pdf_points", "bottom_left"),
                Map.of()
        );
    }

    private ParsedPaperFigure figure(String figureId,
                                     String elementId,
                                     Integer pageNumber,
                                     Integer readingOrder,
                                     String caption,
                                     String figureText) {
        return figure(
                figureId,
                elementId,
                pageNumber,
                readingOrder,
                caption,
                figureText,
                "mineru",
                Map.of()
        );
    }

    private ParsedPaperFigure figure(String figureId,
                                     String elementId,
                                     Integer pageNumber,
                                     Integer readingOrder,
                                     String caption,
                                     String figureText,
                                     String detectionSource,
                                     Map<String, Object> rawAttributes) {
        return new ParsedPaperFigure(
                figureId,
                elementId,
                pageNumber,
                readingOrder,
                caption,
                "Results",
                figureText,
                pageNumber == null ? null : new BoundingBox(pageNumber, 10.0, 20.0, 30.0, 40.0, "pdf_points", "bottom_left"),
                detectionSource,
                "high",
                rawAttributes
        );
    }
}
