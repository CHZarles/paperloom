package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperPage;
import io.github.chzarles.paperloom.paper.parser.BoundingBox;
import io.github.chzarles.paperloom.paper.parser.ParsedPaper;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperElement;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperElementType;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperMetadata;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperPage;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperPageBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperReadingModelBuilderTest {

    private final PaperReadingModelBuilder builder = new PaperReadingModelBuilder();

    @Test
    void buildsPagesAndPageLocationsInReadingOrder() {
        ParsedPaper paper = parsedPaper(List.of(
                element("p2", 2, 3, ParsedPaperElementType.PARAGRAPH, "Second page text."),
                element("h1", 1, 1, ParsedPaperElementType.HEADING, "Intro"),
                element("p1", 1, 2, ParsedPaperElementType.PARAGRAPH, "First page text."),
                element("blank", 1, 4, ParsedPaperElementType.PARAGRAPH, "   "),
                element("no-page", null, 5, ParsedPaperElementType.PARAGRAPH, "Omitted from page text.")
        ));

        PaperReadingModelBuildResult result = builder.build(
                "paper-a",
                "rm_test_1",
                paper,
                "user-a",
                "lab",
                true
        );

        assertEquals(2, result.pages().size());
        PaperPage page1 = result.pages().get(0);
        assertEquals(1, page1.getPageNumber());
        assertEquals("Intro\n\nFirst page text.", page1.getPageText());
        assertEquals("paper-a", page1.getPaperId());
        assertEquals("rm_test_1", page1.getModelVersion());
        assertEquals("MinerU", page1.getParserName());
        assertTrue(page1.getSourceSpanJson().contains("\"elementIds\":[\"h1\",\"p1\"]"));
        assertTrue(page1.getSourceSpanJson().contains("\"readingOrderFrom\":1"));
        assertTrue(page1.getSourceSpanJson().contains("\"readingOrderTo\":2"));

        PaperLocation location = result.locations().get(0);
        assertEquals(PaperLocationType.PAGE, location.getLocationType());
        assertEquals(1, location.getPageNumber());
        assertTrue(location.getLocationRef().startsWith("page_ref_"));
        assertEquals("PAGE_TEXT", location.getContentKind());
        assertEquals(page1.getSourceSpanJson(), location.getSourceSpanJson());

        assertTrue(result.diagnosticsJson().contains("\"pageTextElementsOmittedNoPageCount\":1"));
        assertTrue(result.diagnosticsJson().contains("\"pageTextElementsOmittedBlankTextCount\":1"));
    }

    @Test
    void buildsPhysicalPagesAndSurfaceLocationsWhenPdfHasTextlessPages() {
        ParsedPaper paper = parsedPaper(List.of(
                element("p1", 1, 1, ParsedPaperElementType.PARAGRAPH, "Only readable page.")
        ));

        PaperReadingModelBuildResult result = builder.build(
                "paper-a",
                "rm_test_1",
                paper,
                3,
                "user-a",
                "lab",
                false
        );

        assertEquals(3, result.pages().size());
        assertEquals(PaperPage.TEXT_STATUS_READABLE, result.pages().get(0).getTextStatus());
        assertEquals(PaperPage.TEXT_STATUS_TEXTLESS, result.pages().get(1).getTextStatus());
        assertEquals("", result.pages().get(1).getPageText());
        List<PaperLocation> pageLocations = result.locations().stream()
                .filter(location -> location.getLocationType() == PaperLocationType.PAGE)
                .toList();
        assertEquals(3, pageLocations.size());
        assertEquals("PAGE_TEXT", pageLocations.get(0).getContentKind());
        assertEquals("PAGE_SURFACE", pageLocations.get(1).getContentKind());
        assertTrue(result.diagnosticsJson().contains("\"physicalPageCount\":3"));
        assertTrue(result.diagnosticsJson().contains("\"textlessPageCount\":2"));
    }

    @Test
    void buildsPhysicalPageTextWithoutSplittingCanonicalReadingElements() {
        ParsedPaperElement semanticElement = element(
                "paragraph-1",
                1,
                1,
                ParsedPaperElementType.PARAGRAPH,
                "The paragraph begins on page one and continues on page two."
        );
        ParsedPaper paper = new ParsedPaper(
                "MinerU",
                "self-hosted",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 2, null, null),
                List.of(semanticElement),
                Map.of(),
                "{}",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        physicalPage(1, "The paragraph begins on page one"),
                        physicalPage(2, "and continues on page two.")
                )
        );

        PaperReadingModelBuildResult result = builder.build(
                "paper-a",
                "rm_test_1",
                paper,
                "user-a",
                "lab",
                false
        );

        assertEquals(1, result.readingElements().size());
        assertEquals("The paragraph begins on page one and continues on page two.",
                result.readingElements().get(0).getSearchableText());
        assertEquals("The paragraph begins on page one", result.pages().get(0).getPageText());
        assertEquals("and continues on page two.", result.pages().get(1).getPageText());
        assertTrue(result.pages().get(1).getSourceSpanJson().contains("MINERU_MIDDLE_JSON"));
        assertTrue(result.diagnosticsJson().contains("\"pagesBuiltFromPhysicalProjection\":2"));
        assertTrue(result.diagnosticsJson().contains("\"pagesBuiltFromSemanticProjection\":0"));
    }

    @Test
    void failsWhenParsedPaperIsMissing() {
        PaperReadingModelValidationException failure = assertThrows(
                PaperReadingModelValidationException.class,
                () -> builder.build("paper-a", "rm_test_1", null, "user-a", "lab", false)
        );

        assertEquals("PARSED_PAPER_MISSING", failure.failureReason());
        assertTrue(failure.diagnosticsJson().contains("\"failureReason\":\"PARSED_PAPER_MISSING\""));
    }

    @Test
    void failsWhenParsedElementsAreEmpty() {
        PaperReadingModelValidationException failure = assertThrows(
                PaperReadingModelValidationException.class,
                () -> builder.build("paper-a", "rm_test_1", parsedPaper(List.of()), "user-a", "lab", false)
        );

        assertEquals("PARSED_ELEMENTS_EMPTY", failure.failureReason());
        assertTrue(failure.diagnosticsJson().contains("\"elementCount\":0"));
    }

    @Test
    void failsWhenNoReadableNumberedTextExists() {
        ParsedPaper paper = parsedPaper(List.of(
                element("blank", 1, 1, ParsedPaperElementType.PARAGRAPH, " "),
                element("no-page", null, 2, ParsedPaperElementType.PARAGRAPH, "Has text.")
        ));

        PaperReadingModelValidationException failure = assertThrows(
                PaperReadingModelValidationException.class,
                () -> builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false)
        );

        assertEquals("NO_READABLE_NUMBERED_TEXT", failure.failureReason());
        assertTrue(failure.diagnosticsJson().contains("\"pageTextElementsOmittedNoPageCount\":1"));
        assertTrue(failure.diagnosticsJson().contains("\"pageTextElementsOmittedBlankTextCount\":1"));
    }

    @Test
    void normalizesTextAndDoesNotRequireBoundingBoxes() {
        ParsedPaper paper = parsedPaper(List.of(new ParsedPaperElement(
                "p1",
                1,
                1,
                ParsedPaperElementType.PARAGRAPH,
                " First\r\nLine\u0000\rSecond ",
                null,
                null,
                null,
                Map.of()
        )));

        PaperReadingModelBuildResult result = builder.build(
                "paper-a",
                "rm_test_1",
                paper,
                "user-a",
                "lab",
                false
        );

        assertEquals("First\nLine\nSecond", result.pages().get(0).getPageText());
        assertTrue(result.pages().get(0).getSourceSpanJson().contains("\"bbox\":null"));
        assertTrue(result.diagnosticsJson().contains("\"hasAnyBbox\":false"));
    }

    private ParsedPaper parsedPaper(List<ParsedPaperElement> elements) {
        return new ParsedPaper(
                "MinerU",
                "1.3.0",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 2, null, null),
                elements,
                Map.of(),
                "{}",
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ParsedPaperElement element(String id,
                                       Integer pageNumber,
                                       Integer order,
                                       ParsedPaperElementType type,
                                       String text) {
        return new ParsedPaperElement(
                id,
                pageNumber,
                order,
                type,
                text,
                null,
                null,
                pageNumber == null ? null : new BoundingBox(pageNumber, 1.0, 2.0, 3.0, 4.0, "pdf_points", "bottom_left"),
                Map.of()
        );
    }

    private ParsedPaperPage physicalPage(int pageNumber, String text) {
        return new ParsedPaperPage(
                pageNumber,
                List.of(new ParsedPaperPageBlock(
                        "page-" + pageNumber + "-block-1",
                        1,
                        "text",
                        text,
                        new BoundingBox(pageNumber, 1.0, 2.0, 3.0, 4.0, "mineru_1000", "top_left_1000"),
                        Map.of()
                )),
                Map.of()
        );
    }
}
