package io.github.chzarles.paperloom.paper.parser;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperChunkBuilderTest {

    @Test
    void buildsPageAwareChunksWithSectionElementAndBoundingBoxProvenance() {
        ParsedPaper paper = new ParsedPaper(
                "opendataloader-pdf",
                "2.4.7",
                new ParsedPaperMetadata("paper.pdf", "A Study", "Ada", 2, null, null),
                List.of(
                        element("h1", 1, 1, ParsedPaperElementType.HEADING, "Methods", 1, bbox(1, 72, 700, 520, 735)),
                        element("p1", 1, 2, ParsedPaperElementType.PARAGRAPH,
                                "We introduce a retrieval method that grounds answers in paper chunks.",
                                null, bbox(1, 72, 620, 520, 690)),
                        element("footer", 1, 3, ParsedPaperElementType.FOOTER, "1", null, bbox(1, 300, 20, 320, 40)),
                        element("h2", 2, 4, ParsedPaperElementType.HEADING, "Experiments", 1, bbox(2, 72, 700, 520, 735)),
                        element("cap1", 2, 5, ParsedPaperElementType.CAPTION,
                                "Table 1 shows that the method improves accuracy.",
                                null, bbox(2, 72, 500, 520, 540))
                ),
                Map.of()
        );

        List<PaperChunkCandidate> chunks = new PaperChunkBuilder().buildChunks(paper, 512);

        assertEquals(2, chunks.size());

        PaperChunkCandidate methodChunk = chunks.get(0);
        assertEquals(1, methodChunk.chunkId());
        assertEquals(1, methodChunk.pageNumber());
        assertEquals("Methods", methodChunk.sectionTitle());
        assertEquals(ParsedPaperElementType.PARAGRAPH.name(), methodChunk.elementType());
        assertEquals("opendataloader-pdf", methodChunk.parserName());
        assertTrue(methodChunk.bboxJson().contains("\"left\":72.0"));
        assertTrue(methodChunk.rawProvenanceJson().contains("\"elementId\":\"p1\""));

        PaperChunkCandidate experimentChunk = chunks.get(1);
        assertEquals(2, experimentChunk.chunkId());
        assertEquals(2, experimentChunk.pageNumber());
        assertEquals("Experiments", experimentChunk.sectionTitle());
        assertEquals(ParsedPaperElementType.CAPTION.name(), experimentChunk.elementType());
        assertTrue(experimentChunk.text().contains("improves accuracy"));
    }

    @Test
    void rawProvenanceStaysCompactWhenParserRawAttributesAreLarge() {
        String largeRawContent = "x".repeat(20_000);
        ParsedPaper paper = new ParsedPaper(
                "opendataloader-pdf",
                "2.4.7",
                new ParsedPaperMetadata("paper.pdf", "A Study", "Ada", 1, null, null),
                List.of(
                        new ParsedPaperElement(
                                "large-element",
                                1,
                                1,
                                ParsedPaperElementType.PARAGRAPH,
                                "A compact chunk should not persist parser raw dumps.",
                                null,
                                null,
                                bbox(1, 72, 620, 520, 690),
                                Map.of("content", largeRawContent, "rows", largeRawContent)
                        )
                ),
                Map.of()
        );

        PaperChunkCandidate chunk = new PaperChunkBuilder().buildChunks(paper, 512).get(0);

        assertTrue(chunk.rawProvenanceJson().length() <= 255);
        assertTrue(chunk.rawProvenanceJson().contains("\"elementId\":\"large-element\""));
        assertTrue(chunk.rawProvenanceJson().contains("\"elementType\":\"PARAGRAPH\""));
        assertTrue(!chunk.rawProvenanceJson().contains("rawAttributes"));
    }

    @Test
    void doesNotBuildChunksFromPageNumberFragments() {
        ParsedPaper paper = new ParsedPaper(
                "MinerU",
                "1.3.0",
                new ParsedPaperMetadata("paper.pdf", "A Study", "Ada", 2, null, null),
                List.of(
                        element("page-number", 5, 1, ParsedPaperElementType.TEXT_BLOCK, "5", null, bbox(5, 300, 20, 320, 40)),
                        element("page-marker", 6, 2, ParsedPaperElementType.PARAGRAPH, "Page 6", null, bbox(6, 300, 20, 340, 40)),
                        element("content", 6, 3, ParsedPaperElementType.PARAGRAPH,
                                "Agent harnesses reshape retrieval by deciding which tools to call and when to stop searching.",
                                null, bbox(6, 72, 500, 520, 560))
                ),
                Map.of()
        );

        List<PaperChunkCandidate> chunks = new PaperChunkBuilder().buildChunks(paper, 512);

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).text().contains("Agent harnesses reshape retrieval"));
    }

    @Test
    void buildsFigureChartAndFormulaChunksWithSourceIds() {
        ParsedPaper paper = new ParsedPaper(
                "MinerU",
                "1.3.0",
                new ParsedPaperMetadata("paper.pdf", "A Study", "Ada", 6, null, null),
                List.of(
                        element("fig1", 5, 1, ParsedPaperElementType.FIGURE,
                                "Figure 1: Accuracy rises with more context.",
                                null, bbox(5, 100, 200, 500, 600)),
                        element("formula1", 6, 2, ParsedPaperElementType.FORMULA,
                                "score = alpha * bm25 + beta * cosine",
                                null, bbox(6, 100, 200, 500, 240))
                ),
                Map.of(),
                "{}",
                List.of(),
                List.of(new ParsedPaperFigure(
                        "figure-1",
                        "fig1",
                        5,
                        1,
                        "Figure 1: Accuracy rises with more context.",
                        "Experiments",
                        "Figure 1: Accuracy rises with more context.",
                        bbox(5, 100, 200, 500, 600),
                        "MINERU_FIGURE",
                        "HIGH",
                        Map.of()
                )),
                List.of(new ParsedPaperFormula(
                        "formula-1",
                        "formula1",
                        6,
                        2,
                        "score = alpha * bm25 + beta * cosine",
                        null,
                        "Method",
                        bbox(6, 100, 200, 500, 240),
                        Map.of()
                ))
        );

        List<PaperChunkCandidate> chunks = new PaperChunkBuilder().buildChunks(paper, 512);

        assertEquals(2, chunks.size());
        assertEquals("FIGURE", chunks.get(0).sourceKind());
        assertEquals("figure-1", chunks.get(0).figureId());
        assertEquals("FIGURE_CAPTION", chunks.get(0).evidenceRole());
        assertEquals("FORMULA", chunks.get(1).sourceKind());
        assertEquals("formula-1", chunks.get(1).formulaId());
        assertEquals("FORMULA", chunks.get(1).evidenceRole());
    }

    @Test
    void assignsEvidenceRoleFromParserStructureNotContentKeywords() {
        ParsedPaper paper = new ParsedPaper(
                "MinerU",
                "1.3.0",
                new ParsedPaperMetadata("paper.pdf", "A Study", "Ada", 2, null, null),
                List.of(
                        element("caption", 1, 1, ParsedPaperElementType.CAPTION,
                                "Table 1 reports experiment accuracy on the benchmark.",
                                null, bbox(1, 72, 620, 520, 690)),
                        element("table", 2, 2, ParsedPaperElementType.TABLE,
                                "Metric Value Accuracy 91.2",
                                null, bbox(2, 72, 500, 520, 540))
                ),
                Map.of()
        );

        List<PaperChunkCandidate> chunks = new PaperChunkBuilder().buildChunks(paper, 512);

        assertEquals(2, chunks.size());
        assertEquals("NORMAL_TEXT", chunks.get(0).evidenceRole());
        assertEquals("TABLE", chunks.get(1).evidenceRole());
    }

    private ParsedPaperElement element(String id, int page, int order, ParsedPaperElementType type,
                                       String text, Integer sectionLevel, BoundingBox bbox) {
        return new ParsedPaperElement(id, page, order, type, text, null, sectionLevel, bbox, Map.of());
    }

    private BoundingBox bbox(int page, double left, double bottom, double right, double top) {
        return new BoundingBox(page, left, bottom, right, top, "pdf_points", "bottom_left");
    }
}
