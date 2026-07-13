package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.paper.parser.BoundingBox;
import io.github.chzarles.paperloom.paper.parser.ParsedPaper;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperElement;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperElementType;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PaperReadingModelSourceSpanTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaperReadingModelBuilder builder = new PaperReadingModelBuilder();

    @Test
    void sourceSpanKeepsParserPageElementOrderAndKinds() throws Exception {
        ParsedPaper parsedPaper = new ParsedPaper(
                "MinerU",
                "1.3.0",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 1, null, null),
                List.of(
                        new ParsedPaperElement(
                                "h1",
                                1,
                                1,
                                ParsedPaperElementType.HEADING,
                                "Intro",
                                "Intro",
                                1,
                                new BoundingBox(1, 1.0, 2.0, 3.0, 4.0, "pdf_points", "bottom_left"),
                                Map.of()
                        ),
                        new ParsedPaperElement(
                                "p1",
                                1,
                                2,
                                ParsedPaperElementType.PARAGRAPH,
                                "Readable text.",
                                "Intro",
                                null,
                                new BoundingBox(1, 5.0, 6.0, 7.0, 8.0, "pdf_points", "bottom_left"),
                                Map.of()
                        )
                ),
                Map.of(),
                "{}",
                List.of(),
                List.of(),
                List.of()
        );

        PaperReadingModelBuildResult result = builder.build("paper-a", "rm_test_1", parsedPaper, "user-a", "lab", false);

        JsonNode sourceSpan = objectMapper.readTree(result.pages().get(0).getSourceSpanJson());
        assertEquals("MinerU", sourceSpan.get("parserName").asText());
        assertEquals("1.3.0", sourceSpan.get("parserVersion").asText());
        assertEquals(1, sourceSpan.get("pageNumber").asInt());
        assertEquals("h1", sourceSpan.get("elementIds").get(0).asText());
        assertEquals("p1", sourceSpan.get("elementIds").get(1).asText());
        assertEquals(1, sourceSpan.get("readingOrderFrom").asInt());
        assertEquals(2, sourceSpan.get("readingOrderTo").asInt());
        assertEquals("HEADING", sourceSpan.get("sourceKinds").get(0).asText());
        assertEquals("PARAGRAPH", sourceSpan.get("sourceKinds").get(1).asText());
        assertFalse(sourceSpan.get("bbox").isNull());
        assertEquals(result.pages().get(0).getSourceSpanJson(), result.locations().get(0).getSourceSpanJson());
    }
}
