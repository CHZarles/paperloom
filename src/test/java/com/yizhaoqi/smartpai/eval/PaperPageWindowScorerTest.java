package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperPageWindowScorerTest {

    @Test
    void scoresGoldPagesInsideLocatedWindows() {
        PaperPageDocument page5 = page(5);
        PaperPageDocument page6 = page(6);
        PaperPageDocument page7 = page(7);
        PaperPageDocument page9 = page(9);

        Map<String, Double> metrics = PaperPageWindowScorer.score(
                Map.of(
                        "noise", List.of("paper-a:7"),
                        "appendix", List.of("paper-a:9")
                ),
                Map.of(
                        "noise", List.of(new PaperPageWindow(page5, List.of(page5, page6, page7), 3.0d, List.of("text"))),
                        "appendix", List.of(
                                new PaperPageWindow(page5, List.of(page5, page6, page7), 0.0d, List.of()),
                                new PaperPageWindow(page9, List.of(page9), 2.0d, List.of("section"))
                        )
                ),
                1,
                2
        );

        assertEquals(0.5d, metrics.get("windowRecallAt1"));
        assertEquals(1.0d, metrics.get("windowRecallAt2"));
        assertEquals(0.75d, metrics.get("windowMrr"));
        assertEquals(1.0d, metrics.get("positiveWindowRecallAt1"));
        assertEquals(1.0d, metrics.get("positiveWindowRecallAt2"));
        assertEquals(1.0d, metrics.get("positiveWindowMrr"));
    }

    private PaperPageDocument page(int pageNumber) {
        return new PaperPageDocument(
                "paper-a",
                "paper-a",
                "paper-a.pdf",
                pageNumber,
                "text",
                List.of(pageNumber),
                List.of("Section"),
                List.of("TEXT"),
                List.of(),
                List.of()
        );
    }
}
