package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperPageLocatorScorerTest {

    @Test
    void scoresPageRecallAndMrrAcrossCases() {
        PaperPageDocument pageA4 = page("paper-a", 4);
        PaperPageDocument pageA5 = page("paper-a", 5);
        PaperPageDocument pageB2 = page("paper-b", 2);
        PaperPageDocument pageC9 = page("paper-c", 9);

        Map<String, Double> metrics = PaperPageLocatorScorer.score(
                Map.of(
                        "case-1", List.of("paper-a:4", "paper-a:5"),
                        "case-2", List.of("paper-b:2")
                ),
                Map.of(
                        "case-1", List.of(
                                new PaperPageHit(pageC9, 3.0d, List.of("text")),
                                new PaperPageHit(pageA5, 2.0d, List.of("section:Experiments"))
                        ),
                        "case-2", List.of(
                                new PaperPageHit(pageB2, 4.0d, List.of("title")),
                                new PaperPageHit(pageA4, 1.0d, List.of("text"))
                        )
                ),
                1,
                3
        );

        assertEquals(0.5d, metrics.get("pageRecallAt1"));
        assertEquals(0.75d, metrics.get("pageRecallAt3"));
        assertEquals(0.75d, metrics.get("pageMrr"));
        assertEquals(0.5d, metrics.get("positivePageRecallAt1"));
        assertEquals(0.75d, metrics.get("positivePageRecallAt3"));
        assertEquals(0.75d, metrics.get("positivePageMrr"));
    }

    @Test
    void positiveMetricsIgnoreZeroScoreFallbackPages() {
        PaperPageDocument pageA4 = page("paper-a", 4);
        PaperPageDocument pageA5 = page("paper-a", 5);

        Map<String, Double> metrics = PaperPageLocatorScorer.score(
                Map.of("case-1", List.of("paper-a:4")),
                Map.of("case-1", List.of(
                        new PaperPageHit(pageA5, 0.0d, List.of()),
                        new PaperPageHit(pageA4, 0.0d, List.of())
                )),
                1,
                3
        );

        assertEquals(1.0d, metrics.get("pageRecallAt3"));
        assertEquals(0.0d, metrics.get("positivePageRecallAt3"));
        assertEquals(0.0d, metrics.get("positivePageMrr"));
    }

    @Test
    void returnsZeroMetricsWhenThereAreNoCases() {
        Map<String, Double> metrics = PaperPageLocatorScorer.score(Map.of(), Map.of(), 1, 3);

        assertEquals(0.0d, metrics.get("pageRecallAt1"));
        assertEquals(0.0d, metrics.get("pageRecallAt3"));
        assertEquals(0.0d, metrics.get("pageMrr"));
        assertEquals(0.0d, metrics.get("positivePageRecallAt1"));
        assertEquals(0.0d, metrics.get("positivePageRecallAt3"));
        assertEquals(0.0d, metrics.get("positivePageMrr"));
    }

    private PaperPageDocument page(String paperId, int pageNumber) {
        return new PaperPageDocument(
                paperId,
                paperId,
                paperId + ".pdf",
                pageNumber,
                "text",
                List.of(pageNumber),
                List.of("Experiments"),
                List.of("TEXT"),
                List.of(),
                List.of()
        );
    }
}
