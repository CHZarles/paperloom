package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperPage;
import com.yizhaoqi.smartpai.paper.parser.BoundingBox;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElement;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElementType;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperMetadata;
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
                element("no-page", null, 5, ParsedPaperElementType.PARAGRAPH, "Skipped text.")
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

        assertTrue(result.diagnosticsJson().contains("\"elementsSkippedNoPage\":1"));
        assertTrue(result.diagnosticsJson().contains("\"elementsSkippedBlankText\":1"));
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
        assertTrue(failure.diagnosticsJson().contains("\"elementsSkippedNoPage\":1"));
        assertTrue(failure.diagnosticsJson().contains("\"elementsSkippedBlankText\":1"));
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
}
