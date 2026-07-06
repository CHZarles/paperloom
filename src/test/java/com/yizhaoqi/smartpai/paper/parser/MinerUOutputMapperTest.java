package com.yizhaoqi.smartpai.paper.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinerUOutputMapperTest {

    @Test
    void mapsMinerUContentListToTextTableFigureAndFormulaEvidence() {
        String contentListJson = """
                [
                  {
                    "type": "text",
                    "text": "PaperLoom: Evidence Grounded Paper RAG",
                    "text_level": 1,
                    "page_idx": 0,
                    "bbox": [70, 80, 930, 130]
                  },
                  {
                    "type": "text",
                    "text": "We evaluate retrieval methods on long-memory questions.",
                    "page_idx": 0,
                    "bbox": [70, 160, 930, 230]
                  },
                  {
                    "type": "table",
                    "table_caption": ["Table 1: Overall accuracy on LongMemEval."],
                    "table_body": "<table><tr><td>Method</td><td>Accuracy</td></tr><tr><td>Grep</td><td>74.2</td></tr></table>",
                    "page_idx": 4,
                    "bbox": [80, 250, 920, 620]
                  },
                  {
                    "type": "image",
                    "image_caption": ["Figure 1: Mean performance as noise is added."],
                    "page_idx": 5,
                    "bbox": [100, 200, 900, 650]
                  },
                  {
                    "type": "equation",
                    "text": "score = alpha * bm25 + beta * cosine",
                    "page_idx": 5,
                    "bbox": [120, 700, 880, 760]
                  }
                ]
                """;

        ParsedPaper paper = new MinerUOutputMapper().map(
                contentListJson,
                "{\"layout\":\"debug\"}",
                "# PaperLoom",
                "MinerU",
                "1.3.0",
                "paper.pdf"
        );

        assertEquals("MinerU", paper.parserName());
        assertEquals("1.3.0", paper.parserVersion());
        assertEquals("paper.pdf", paper.metadata().originalFilename());
        assertNotNull(paper.rawParserJson());
        assertTrue(paper.rawParserJson().contains("contentList"));

        assertEquals(5, paper.elements().size());
        assertEquals(ParsedPaperElementType.HEADING, paper.elements().get(0).elementType());
        assertEquals(1, paper.elements().get(0).pageNumber());
        assertEquals(ParsedPaperElementType.TABLE, paper.elements().get(2).elementType());
        assertEquals(ParsedPaperElementType.FIGURE, paper.elements().get(3).elementType());
        assertEquals(ParsedPaperElementType.FORMULA, paper.elements().get(4).elementType());

        assertEquals(1, paper.tables().size());
        ParsedPaperTable table = paper.tables().get(0);
        assertEquals("table-3", table.tableId());
        assertEquals(5, table.pageNumber());
        assertTrue(table.caption().contains("Overall accuracy"));
        assertTrue(table.tableText().contains("Grep"));
        assertTrue(table.tableMarkdown().contains("Accuracy"));
        assertNotNull(table.boundingBox());

        assertEquals(1, paper.figures().size());
        ParsedPaperFigure figure = paper.figures().get(0);
        assertEquals("figure-4", figure.figureId());
        assertEquals(6, figure.pageNumber());
        assertTrue(figure.caption().contains("Mean performance"));
        assertNotNull(figure.boundingBox());

        assertEquals(1, paper.formulas().size());
        ParsedPaperFormula formula = paper.formulas().get(0);
        assertEquals("formula-5", formula.formulaId());
        assertEquals(6, formula.pageNumber());
        assertTrue(formula.latex().contains("bm25"));
    }

    @Test
    void mapsOnlyObservedMinerUFigureCaptionFieldsAndSkipsPanelOnlyChartLabelsAndLegacyAliases() {
        String contentListJson = """
                [
                  {
                    "type": "image",
                    "image_caption": ["Figure 1: Overview of the benchmark."],
                    "page_idx": 1,
                    "bbox": [100, 200, 900, 650]
                  },
                  {
                    "type": "chart",
                    "chart_caption": ["(a) Recall", "Figure 2: Rule-wise metrics of rules in airline domain."],
                    "page_idx": 2,
                    "bbox": [120, 180, 880, 620]
                  },
                  {
                    "type": "chart",
                    "chart_caption": ["(b) Correctness"],
                    "page_idx": 2,
                    "bbox": [120, 660, 880, 920]
                  },
                  {
                    "type": "image",
                    "img_caption": ["Figure 9: Legacy alias should not be used."],
                    "page_idx": 3,
                    "bbox": [100, 200, 900, 650]
                  },
                  {
                    "type": "image",
                    "caption": ["Figure 10: Generic alias should not be used."],
                    "page_idx": 3,
                    "bbox": [100, 200, 900, 650]
                  }
                ]
                """;

        ParsedPaper paper = new MinerUOutputMapper().map(
                contentListJson,
                "{}",
                "",
                "MinerU",
                "self-hosted",
                "paper.pdf"
        );

        assertEquals(5, paper.figures().size());
        ParsedPaperFigure image = paper.figures().get(0);
        assertNotNull(image.caption());
        assertNotNull(image.figureText());
        assertTrue(image.caption().contains("Overview of the benchmark"));
        assertTrue(image.figureText().contains("Overview of the benchmark"));

        ParsedPaperFigure chartWithFullCaption = paper.figures().get(1);
        assertNotNull(chartWithFullCaption.caption());
        assertTrue(chartWithFullCaption.caption().contains("Figure 2"));
        assertTrue(chartWithFullCaption.caption().contains("(a) Recall"));

        ParsedPaperFigure panelOnlyChart = paper.figures().get(2);
        assertTrue(panelOnlyChart.caption() == null || panelOnlyChart.caption().isBlank());
        assertTrue(panelOnlyChart.figureText() == null || panelOnlyChart.figureText().isBlank());

        ParsedPaperFigure legacyAlias = paper.figures().get(3);
        assertTrue(legacyAlias.caption() == null || legacyAlias.caption().isBlank());
        assertTrue(legacyAlias.figureText() == null || legacyAlias.figureText().isBlank());

        ParsedPaperFigure genericAlias = paper.figures().get(4);
        assertTrue(genericAlias.caption() == null || genericAlias.caption().isBlank());
        assertTrue(genericAlias.figureText() == null || genericAlias.figureText().isBlank());
    }
}
