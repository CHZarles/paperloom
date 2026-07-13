package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.paper.parser.BoundingBox;
import io.github.chzarles.paperloom.paper.parser.ParsedPaper;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperElement;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperElementType;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperFigure;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperFormula;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperMetadata;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperReadingModelElementBuilderTest {

    private final PaperReadingModelBuilder builder = new PaperReadingModelBuilder();

    @Test
    void retainsTypedParsedElementWhenTypedObjectListIsMissing() {
        ParsedPaper paper = parsedPaper(
                List.of(
                        element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Readable page text.", Map.of()),
                        element("orphan-figure-el", 1, 2, ParsedPaperElementType.FIGURE, "Figure 9: Orphan visual.", Map.of("type", "image"))
                ),
                List.of(),
                List.of(),
                List.of()
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        PaperReadingElement retained = retainedElement(result, "orphan-figure-el");
        assertEquals("IMAGE", retained.getElementType());
        assertEquals("Figure 9: Orphan visual.", retained.getSearchableText());
        assertNull(retained.getLocationNotCreatedReason());
        assertTrue(retained.getLocationRef().startsWith("figure_ref_"));
        assertTrue(retained.getRawAttributesJson().contains("image"));
    }

    @Test
    void retainsOneReadingElementForEveryParsedMineruItemWhenTypedObjectsExist() {
        ParsedPaper paper = parsedPaper(
                List.of(
                        element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Readable page text.", Map.of()),
                        element("table-el", 1, 2, ParsedPaperElementType.TABLE, "Table 1: Scores.", Map.of("type", "table")),
                        element("figure-el", 1, 3, ParsedPaperElementType.CHART, "Figure 1: Scores.", Map.of("type", "chart")),
                        element("formula-el", 1, 4, ParsedPaperElementType.FORMULA, "E = mc^2", Map.of("type", "equation"))
                ),
                List.of(table("table-1", "table-el", 1, 2, "Table 1: Scores.", "Model | Score")),
                List.of(figure(
                        "figure-1",
                        "figure-el",
                        1,
                        3,
                        "Figure 1: Scores.",
                        "Figure 1: Scores.",
                        Map.of("type", "chart", "chart_caption", List.of("Figure 1: Scores."))
                )),
                List.of(formula("formula-1", "formula-el", 1, 4, "E = mc^2"))
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        Set<String> parserElementIds = result.readingElements().stream()
                .map(PaperReadingElement::getParserElementId)
                .collect(Collectors.toSet());
        assertEquals(paper.elements().size(), result.readingElements().size());
        assertTrue(parserElementIds.containsAll(List.of("p1", "table-el", "figure-el", "formula-el")));
    }

    @Test
    void prefersFullCaptionParentWhoseRawChartCaptionContainsPanelLabel() {
        ParsedPaper paper = parsedPaper(
                List.of(element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Readable page text.", Map.of())),
                List.of(),
                List.of(
                        figure(
                                "figure-nearest",
                                "fig-el-1",
                                1,
                                3,
                                "Figure 1: Nearby but wrong chart.",
                                "Figure 1: Nearby but wrong chart.",
                                Map.of("type", "chart", "chart_caption", List.of("Figure 1: Nearby but wrong chart."))
                        ),
                        figure(
                                "figure-containing",
                                "fig-el-2",
                                1,
                                8,
                                "Figure 2: Rule-wise metrics.",
                                "Figure 2: Rule-wise metrics.",
                                Map.of("type", "chart", "chart_caption", List.of("(b) Precision", "Figure 2: Rule-wise metrics."))
                        ),
                        figure(
                                "figure-panel",
                                "fig-el-3",
                                1,
                                4,
                                null,
                                null,
                                Map.of("type", "chart", "chart_caption", List.of("(b) Precision"))
                        )
                ),
                List.of()
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        PaperReadingElement containing = retainedElement(result, "figure-containing");
        PaperReadingElement panel = retainedElement(result, "figure-panel");
        PaperLocation containingLocation = location(result, PaperLocationType.FIGURE, "figure-containing");

        assertEquals("ATTACHED", panel.getAssociationStatus());
        assertEquals("PANEL_LABEL", panel.getAttachmentRole());
        assertEquals(containing.getReadingElementId(), panel.getParentReadingElementId());
        assertEquals(containingLocation.getLocationRef(), containing.getLocationRef());
    }

    @Test
    void keepsAmbiguousPanelOnlyChartSearchableWithoutFalseParent() {
        ParsedPaper paper = parsedPaper(
                List.of(element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Readable page text.", Map.of())),
                List.of(),
                List.of(
                        figure(
                                "figure-left",
                                "fig-el-1",
                                1,
                                2,
                                "Figure 1: Left chart.",
                                "Figure 1: Left chart.",
                                Map.of("type", "chart", "chart_caption", List.of("Figure 1: Left chart."))
                        ),
                        figure(
                                "figure-right",
                                "fig-el-2",
                                1,
                                4,
                                "Figure 2: Right chart.",
                                "Figure 2: Right chart.",
                                Map.of("type", "chart", "chart_caption", List.of("Figure 2: Right chart."))
                        ),
                        figure(
                                "figure-panel",
                                "fig-el-3",
                                1,
                                3,
                                null,
                                null,
                                Map.of("type", "chart", "chart_caption", List.of("(a) Recall"))
                        )
                ),
                List.of()
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        PaperReadingElement panel = retainedElement(result, "figure-panel");
        assertEquals("(a) Recall", panel.getSearchableText());
        assertEquals("AMBIGUOUS", panel.getAssociationStatus());
        assertEquals("PANEL_LABEL", panel.getAttachmentRole());
        assertNull(panel.getParentReadingElementId());
    }

    @Test
    void keepsUnattachedPanelOnlyChartSearchableWhenNoParentIsNearEnough() {
        ParsedPaper paper = parsedPaper(
                List.of(element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Readable page text.", Map.of())),
                List.of(),
                List.of(figure(
                        "figure-panel",
                        "fig-el-1",
                        1,
                        20,
                        null,
                        null,
                        Map.of("type", "chart", "chart_caption", List.of("(b) Precision"))
                )),
                List.of()
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        PaperReadingElement panel = retainedElement(result, "figure-panel");
        assertEquals("(b) Precision", panel.getSearchableText());
        assertEquals("UNATTACHED", panel.getAssociationStatus());
        assertEquals("PANEL_LABEL", panel.getAttachmentRole());
    }

    @Test
    void attachesSeparateTableCaptionElementToNearestTableLocation() {
        ParsedPaper paper = parsedPaper(
                List.of(
                        element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Readable page text.", Map.of()),
                        element("table-caption-el", 1, 5, ParsedPaperElementType.CAPTION, "Table 2: Model scores.", Map.of("type", "caption"))
                ),
                List.of(table("table-2", "table-el-2", 1, 6, "Table 2: Model scores.", "Model | Score")),
                List.of(),
                List.of()
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        PaperReadingElement table = retainedElement(result, "table-2");
        PaperReadingElement caption = retainedElement(result, "table-caption-el");
        PaperLocation tableLocation = location(result, PaperLocationType.TABLE, "table-2");

        assertEquals("ATTACHED", caption.getAssociationStatus());
        assertEquals("TABLE_CAPTION", caption.getAttachmentRole());
        assertEquals(table.getReadingElementId(), caption.getParentReadingElementId());
        assertEquals(tableLocation.getLocationRef(), table.getLocationRef());
        assertEquals("Table 2: Model scores.", caption.getSearchableText());
    }

    @Test
    void attachesTableCaptionToRetainedParentEvenWhenParentHasNoTableLocation() {
        ParsedPaper paper = parsedPaper(
                List.of(
                        element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Readable page text.", Map.of()),
                        element("table-caption-el", 1, 5, ParsedPaperElementType.CAPTION, "Table 2: Model scores.", Map.of("type", "caption"))
                ),
                List.of(table("", "table-el-2", 1, 6, "Table 2: Model scores.", "Model | Score")),
                List.of(),
                List.of()
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        PaperReadingElement table = retainedElementByParserId(result, "table-el-2");
        PaperReadingElement caption = retainedElement(result, "table-caption-el");

        assertNull(table.getLocationNotCreatedReason());
        assertTrue(table.getLocationRef().startsWith("table_ref_"));
        assertEquals("ATTACHED", caption.getAssociationStatus());
        assertEquals("TABLE_CAPTION", caption.getAttachmentRole());
        assertEquals(table.getReadingElementId(), caption.getParentReadingElementId());
    }

    @Test
    void attachesPanelOnlyChartToRetainedParentEvenWhenParentHasNoFigureLocation() {
        ParsedPaper paper = parsedPaper(
                List.of(element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Readable page text.", Map.of())),
                List.of(),
                List.of(
                        figure(
                                "",
                                "fig-parent",
                                1,
                                2,
                                "Figure 1: Rule-wise metrics.",
                                "Figure 1: Rule-wise metrics.",
                                Map.of("type", "chart", "chart_caption", List.of("Figure 1: Rule-wise metrics."))
                        ),
                        figure(
                                "figure-panel",
                                "fig-panel",
                                1,
                                3,
                                null,
                                null,
                                Map.of("type", "chart", "chart_caption", List.of("(a) Recall"))
                        )
                ),
                List.of()
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        PaperReadingElement parent = retainedElementByParserId(result, "fig-parent");
        PaperReadingElement panel = retainedElement(result, "figure-panel");

        assertNull(parent.getLocationNotCreatedReason());
        assertTrue(parent.getLocationRef().startsWith("figure_ref_"));
        assertEquals("ATTACHED", panel.getAssociationStatus());
        assertEquals("PANEL_LABEL", panel.getAttachmentRole());
        assertEquals(parent.getReadingElementId(), panel.getParentReadingElementId());
        assertEquals("PANEL_ONLY_CAPTION", panel.getLocationNotCreatedReason());
    }

    @Test
    void doesNotAttachPanelOnlyChartWhenSamePageEvidenceIsMissing() {
        ParsedPaper paper = parsedPaper(
                List.of(element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Readable page text.", Map.of())),
                List.of(),
                List.of(
                        figure(
                                "figure-parent",
                                "fig-parent",
                                null,
                                2,
                                "Figure 1: Rule-wise metrics.",
                                "Figure 1: Rule-wise metrics.",
                                Map.of("type", "chart", "chart_caption", List.of("(a) Recall", "Figure 1: Rule-wise metrics."))
                        ),
                        figure(
                                "figure-panel",
                                "fig-panel",
                                null,
                                3,
                                null,
                                null,
                                Map.of("type", "chart", "chart_caption", List.of("(a) Recall"))
                        )
                ),
                List.of()
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        PaperReadingElement panel = retainedElement(result, "figure-panel");

        assertEquals("UNATTACHED", panel.getAssociationStatus());
        assertEquals("PANEL_LABEL", panel.getAttachmentRole());
        assertNull(panel.getParentReadingElementId());
    }

    @Test
    void retainsBlankTablePayloadWithoutTableLocation() {
        ParsedPaper paper = parsedPaper(
                List.of(element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Readable page text.", Map.of())),
                List.of(table("table-blank", "table-el-blank", 1, 2, " ", " ")),
                List.of(),
                List.of()
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        PaperReadingElement table = retainedElement(result, "table-blank");
        assertEquals("TABLE", table.getElementType());
        assertEquals("", table.getSearchableText());
        assertNull(table.getLocationRef());
        assertEquals("BLANK_SEARCHABLE_TEXT", table.getLocationNotCreatedReason());
    }

    @Test
    void retainsVisualOnlyChartWithParserImagePathWithoutCreatingFigureLocation() {
        ParsedPaper paper = parsedPaper(
                List.of(element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Readable page text.", Map.of())),
                List.of(),
                List.of(figure(
                        "figure-visual-only",
                        "fig-el-visual-only",
                        1,
                        2,
                        null,
                        null,
                        Map.of(
                                "type", "chart",
                                "img_path", "images/chart-1.jpg",
                                "content", "",
                                "chart_caption", List.of()
                        )
                )),
                List.of()
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        PaperReadingElement chart = retainedElement(result, "figure-visual-only");
        assertEquals("CHART", chart.getElementType());
        assertEquals("", chart.getSearchableText());
        assertEquals("images/chart-1.jpg", chart.getParserImagePath());
        assertNull(chart.getLocationRef());
        assertEquals("BLANK_SEARCHABLE_TEXT_WITH_VISUAL_ASSET", chart.getLocationNotCreatedReason());
    }

    @Test
    void mapsRetainedTextElementSubtypesFromRawMinerUType() {
        ParsedPaper paper = parsedPaper(
                List.of(
                        element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Readable page text.", Map.of()),
                        element("footnote-1", 1, 2, ParsedPaperElementType.PARAGRAPH, "Footnote text.", Map.of("type", "page_footnote")),
                        element("aside-1", 1, 3, ParsedPaperElementType.PARAGRAPH, "Aside text.", Map.of("type", "aside_text")),
                        element("code-1", 1, 4, ParsedPaperElementType.PARAGRAPH, "print('hello')", Map.of("type", "code"))
                ),
                List.of(),
                List.of(),
                List.of()
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false);

        assertEquals("FOOTNOTE", retainedElement(result, "footnote-1").getElementType());
        assertEquals("ASIDE", retainedElement(result, "aside-1").getElementType());
        assertEquals("CODE", retainedElement(result, "code-1").getElementType());
    }

    private PaperReadingElement retainedElement(PaperReadingModelBuildResult result, String sourceObjectId) {
        List<PaperReadingElement> matches = result.readingElements().stream()
                .filter(element -> sourceObjectId.equals(element.getSourceObjectId()))
                .toList();
        assertEquals(1, matches.size());
        return matches.get(0);
    }

    private PaperReadingElement retainedElementByParserId(PaperReadingModelBuildResult result, String parserElementId) {
        List<PaperReadingElement> matches = result.readingElements().stream()
                .filter(element -> parserElementId.equals(element.getParserElementId()))
                .toList();
        assertEquals(1, matches.size());
        return matches.get(0);
    }

    private PaperLocation location(PaperReadingModelBuildResult result, PaperLocationType type, String sourceObjectId) {
        PaperReadingElement target = retainedElement(result, sourceObjectId);
        List<PaperLocation> matches = result.locations().stream()
                .filter(location -> location.getLocationType() == type)
                .filter(location -> target.getReadingElementId().equals(location.getSourceObjectId()))
                .toList();
        assertEquals(1, matches.size());
        return matches.get(0);
    }

    private ParsedPaper parsedPaper(List<ParsedPaperElement> elements,
                                    List<ParsedPaperTable> tables,
                                    List<ParsedPaperFigure> figures,
                                    List<ParsedPaperFormula> formulas) {
        return new ParsedPaper(
                "MinerU",
                "self-hosted",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 2, null, null),
                elements,
                Map.of(),
                "{}",
                tables,
                figures,
                formulas
        );
    }

    private ParsedPaperElement element(String id,
                                       Integer pageNumber,
                                       Integer order,
                                       ParsedPaperElementType type,
                                       String text,
                                       Map<String, Object> rawAttributes) {
        return new ParsedPaperElement(
                id,
                pageNumber,
                order,
                type,
                text,
                null,
                null,
                pageNumber == null ? null : new BoundingBox(pageNumber, 1.0, 2.0, 3.0, 4.0, "pdf_points", "bottom_left"),
                rawAttributes
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
                null,
                1,
                2,
                tableText,
                null,
                pageNumber == null ? null : new BoundingBox(pageNumber, 10.0, 20.0, 30.0, 40.0, "pdf_points", "bottom_left"),
                Map.of("type", "table", "table_caption", List.of(caption))
        );
    }

    private ParsedPaperFigure figure(String figureId,
                                     String elementId,
                                     Integer pageNumber,
                                     Integer readingOrder,
                                     String caption,
                                     String figureText,
                                     Map<String, Object> rawAttributes) {
        return new ParsedPaperFigure(
                figureId,
                elementId,
                pageNumber,
                readingOrder,
                caption,
                null,
                figureText,
                pageNumber == null ? null : new BoundingBox(pageNumber, 10.0, 20.0, 30.0, 40.0, "pdf_points", "bottom_left"),
                "MINERU_CHART",
                "HIGH",
                rawAttributes
        );
    }

    private ParsedPaperFormula formula(String formulaId,
                                       String elementId,
                                       Integer pageNumber,
                                       Integer readingOrder,
                                       String latex) {
        return new ParsedPaperFormula(
                formulaId,
                elementId,
                pageNumber,
                readingOrder,
                latex,
                null,
                null,
                pageNumber == null ? null : new BoundingBox(pageNumber, 10.0, 20.0, 30.0, 40.0, "pdf_points", "bottom_left"),
                Map.of("type", "equation")
        );
    }
}
