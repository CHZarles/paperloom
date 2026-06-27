package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagScorecardTest {

    @Test
    void aggregatesVerdictsDiagnosticsAndRetrievalMetrics() {
        RagBenchmarkCase passCase = RagBenchmarkCase.productRescueCase(
                "pass_case",
                "讲一讲高噪声场景",
                "MANUAL_SOURCE_QA",
                List.of("paper-a"),
                List.of("increasing noise"),
                List.of("没有找到足够可靠")
        );
        RagBenchmarkCase failCase = RagBenchmarkCase.productRescueCase(
                "fail_case",
                "只看这篇论文的方法",
                "MANUAL_SOURCE_QA",
                List.of("paper-a"),
                List.of("method"),
                List.of("没有找到足够可靠")
        );
        RagBenchmarkRun run = new RagBenchmarkRun(
                List.of(passCase, failCase),
                List.of(
                        new RagBenchmarkActual("MANUAL_SOURCE_QA", "ok [1]", Map.of(), Map.of(
                                "scannedCount", 6,
                                "acceptedEvidenceCount", 3,
                                "fallbackUsed", false
                        )),
                        new RagBenchmarkActual("AUTO_SOURCE_QA", "bad", Map.of(), Map.of(
                                "scannedCount", 10,
                                "acceptedEvidenceCount", 1,
                                "fallbackUsed", true
                        ))
                ),
                List.of(
                        new RagBenchmarkVerdict("pass_case", true, List.of(), List.of()),
                        new RagBenchmarkVerdict(
                                "fail_case",
                                false,
                                List.of("ROUTE_MISMATCH", "EVIDENCE_UNUSABLE:1", "CITATION_NOT_RENDERED"),
                                List.of("INTENT_ROUTE", "BAD_EVIDENCE", "FALSE_NEGATIVE", "CITATION_MAPPING")
                        )
                )
        );

        RagScorecard scorecard = RagScorecard.from(
                "run-1",
                "2026-06-23T12:00:00Z",
                "current-evidence-ledger",
                "litsearch-full",
                run,
                Map.of("recallAt20", 0.42d)
        );

        assertEquals(2, scorecard.caseCount());
        assertEquals(1, scorecard.passed());
        assertEquals(1, scorecard.failed());
        assertEquals(0.5d, scorecard.passRate());
        assertEquals(0.5d, scorecard.routeAccuracy());
        assertEquals(1.0d, scorecard.answerRequiredHitRate());
        assertEquals(0.5d, scorecard.evidenceRequiredHitRate());
        assertEquals(0.5d, scorecard.citationMappingRate());
        assertEquals(0.5d, scorecard.badEvidenceRate());
        assertEquals(0.0d, scorecard.scopeLeakRate());
        assertEquals(0.5d, scorecard.falseNegativeRate());
        assertEquals(8.0d, scorecard.avgScannedCount());
        assertEquals(2.0d, scorecard.avgAcceptedEvidenceCount());
        assertEquals(0.5d, scorecard.fallbackRate());
        assertEquals(0.42d, scorecard.metrics().get("recallAt20"));
    }

    @Test
    void returnsZeroRatesForEmptyRuns() {
        RagScorecard scorecard = RagScorecard.from(
                "empty",
                "2026-06-23T12:00:00Z",
                "current-evidence-ledger",
                "qasper-dev-200",
                new RagBenchmarkRun(List.of(), List.of(), List.of()),
                Map.of()
        );

        assertEquals(0, scorecard.caseCount());
        assertEquals(0.0d, scorecard.passRate());
        assertEquals(0.0d, scorecard.avgScannedCount());
        assertEquals(0.0d, scorecard.metrics().get("passRate"));
    }
}
