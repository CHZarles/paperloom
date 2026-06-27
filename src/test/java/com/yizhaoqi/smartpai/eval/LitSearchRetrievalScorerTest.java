package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LitSearchRetrievalScorerTest {

    @Test
    void computesRecallAtKAndMrrAcrossQueries() {
        List<LitSearchBenchmarkCase> cases = List.of(
                new LitSearchBenchmarkCase(
                        "case-1",
                        "LITSEARCH_RETRIEVAL",
                        "inline_acl",
                        "find distillation papers",
                        0,
                        2,
                        List.of("10", "20")
                ),
                new LitSearchBenchmarkCase(
                        "case-2",
                        "LITSEARCH_RETRIEVAL",
                        "inline_acl",
                        "find hallucination papers",
                        0,
                        2,
                        List.of("30")
                )
        );

        Map<String, Double> metrics = LitSearchRetrievalScorer.score(cases, Map.of(
                "case-1", List.of("99", "10", "88", "77", "66", "20"),
                "case-2", List.of("30", "42")
        ));

        assertEquals(0.75d, metrics.get("recallAt5"));
        assertEquals(1.0d, metrics.get("recallAt20"));
        assertEquals(0.75d, metrics.get("mrr"));
    }

    @Test
    void returnsZeroMetricsWhenThereAreNoCases() {
        Map<String, Double> metrics = LitSearchRetrievalScorer.score(List.of(), Map.of());

        assertEquals(0.0d, metrics.get("recallAt5"));
        assertEquals(0.0d, metrics.get("recallAt20"));
        assertEquals(0.0d, metrics.get("mrr"));
    }
}
