package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.service.ChatHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagLiveBenchmarkRunnerTest {

    @Test
    void runsCasesThroughLiveClientAndEvaluatesActualResponses() {
        RagBenchmarkCase passingCase = RagBenchmarkCase.productRescueCase(
                "pass_case",
                "讲一讲高噪声场景",
                "MANUAL_SOURCE_QA",
                List.of("paper-a"),
                List.of("increasing noise"),
                List.of("没有找到足够可靠")
        );
        RagBenchmarkCase failingCase = RagBenchmarkCase.productRescueCase(
                "fail_case",
                "只看这篇论文的方法",
                "MANUAL_SOURCE_QA",
                List.of("paper-a"),
                List.of("method"),
                List.of("没有找到足够可靠")
        );
        RagLiveBenchmarkRunner.LiveChatClient client = testCase -> {
            if ("pass_case".equals(testCase.id())) {
                return new RagLiveBenchmarkRunner.LiveChatResponse(
                        "论文讨论了 increasing noise 场景。[1]",
                        Map.of(1, reference("paper-a", "The experiment studies context scaling with increasing noise.")),
                        Map.of("route", "MANUAL_SOURCE_QA", "acceptedEvidenceCount", 1, "scannedCount", 3)
                );
            }
            return new RagLiveBenchmarkRunner.LiveChatResponse(
                    "我没有找到足够可靠的论文证据来回答这个问题。",
                    Map.of(),
                    Map.of("route", "MANUAL_SOURCE_QA", "acceptedEvidenceCount", 0, "scannedCount", 3)
            );
        };

        RagBenchmarkRun run = new RagLiveBenchmarkRunner(client, new RagBenchmarkEvaluator())
                .run(List.of(passingCase, failingCase));

        assertEquals(2, run.cases().size());
        assertEquals(2, run.actuals().size());
        assertEquals(2, run.verdicts().size());
        assertTrue(run.verdicts().get(0).passed());
        assertFalse(run.verdicts().get(1).passed());
        assertEquals("MANUAL_SOURCE_QA", run.actuals().get(0).route());
        assertTrue(run.verdicts().get(1).failureClass().contains("FALSE_NEGATIVE"));
    }

    private ChatHandler.ReferenceInfo reference(String paperId, String matchedText) {
        return new ChatHandler.ReferenceInfo(
                paperId,
                "Paper",
                "paper.pdf",
                2,
                matchedText,
                "HYBRID",
                "Hybrid",
                "query",
                matchedText,
                matchedText,
                0.9d,
                1,
                "PARAGRAPH",
                "Method",
                1,
                null,
                "MinerU",
                "self-hosted",
                "TEXT",
                null,
                null,
                null,
                "NORMAL_TEXT",
                "HYBRID",
                "GENERAL",
                "general",
                null,
                null,
                false
        );
    }
}
