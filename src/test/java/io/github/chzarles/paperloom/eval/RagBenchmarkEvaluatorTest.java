package io.github.chzarles.paperloom.eval;

import io.github.chzarles.paperloom.service.ChatHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagBenchmarkEvaluatorTest {

    @Test
    void passesWhenAnswerAndEvidenceMeetProductRescueCase() {
        RagBenchmarkCase testCase = new RagBenchmarkCase(
                "grep_high_noise_zh",
                "讲一讲高噪声场景",
                "zh",
                "EXPERIMENT_QA",
                "MANUAL_SOURCE",
                new RagBenchmarkCase.Scope(List.of("paper-a"), List.of("Agent Harness Paper")),
                "MANUAL_SOURCE_QA",
                List.of("高噪声|噪声", "Experiment 2|实验 2"),
                List.of("increasing noise", "Context Scaling with Increasing Noise"),
                List.of("没有找到足够可靠"),
                List.of("^\\d+$", "^Agentic Search, Semantic Search"),
                List.of("paper-a"),
                true
        );
        RagBenchmarkActual actual = new RagBenchmarkActual(
                "MANUAL_SOURCE_QA",
                "**结论**\n论文在实验 2 中讨论高噪声场景。[1]",
                Map.of(1, reference("paper-a", 5,
                        "4.2 Experiment 2: Context Scaling with Increasing Noise",
                        "The context scaling experiment studies retrieval performance with increasing noise.")),
                Map.of("stopReason", "EXHAUSTED")
        );

        RagBenchmarkVerdict verdict = new RagBenchmarkEvaluator().evaluate(testCase, actual);

        assertTrue(verdict.passed());
        assertEquals(List.of(), verdict.failures());
    }

    @Test
    void failsFalseNegativeWhenAnswerRefusesDespiteRequiredEvidenceCase() {
        RagBenchmarkCase testCase = RagBenchmarkCase.productRescueCase(
                "grep_high_noise_zh",
                "讲一讲高噪声场景",
                "MANUAL_SOURCE_QA",
                List.of("paper-a"),
                List.of("increasing noise"),
                List.of("没有找到足够可靠")
        );
        RagBenchmarkActual actual = new RagBenchmarkActual(
                "MANUAL_SOURCE_QA",
                "我没有找到足够可靠的论文证据来回答这个问题。",
                Map.of(),
                Map.of("stopReason", "NO_USABLE_EVIDENCE")
        );

        RagBenchmarkVerdict verdict = new RagBenchmarkEvaluator().evaluate(testCase, actual);

        assertFalse(verdict.passed());
        assertTrue(verdict.failures().contains("ANSWER_FORBIDDEN_PATTERN:没有找到足够可靠"));
        assertTrue(verdict.failures().contains("CITATION_REQUIRED"));
        assertTrue(verdict.failureClass().contains("FALSE_NEGATIVE"));
    }

    @Test
    void failsBadEvidenceWhenCitationUsesNumericFragment() {
        RagBenchmarkCase testCase = RagBenchmarkCase.productRescueCase(
                "numeric_fragment",
                "进一步解释",
                "MANUAL_SOURCE_QA",
                List.of("paper-a"),
                List.of("agent harness"),
                List.of("没有找到足够可靠")
        );
        RagBenchmarkActual actual = new RagBenchmarkActual(
                "MANUAL_SOURCE_QA",
                "**结论**\nAgent Harness 更强。[1]",
                Map.of(1, reference("paper-a", 5, "4.2 Experiment 2", "5")),
                Map.of("stopReason", "EXHAUSTED")
        );

        RagBenchmarkVerdict verdict = new RagBenchmarkEvaluator().evaluate(testCase, actual);

        assertFalse(verdict.passed());
        assertTrue(verdict.failures().contains("EVIDENCE_UNUSABLE:1"));
        assertTrue(verdict.failures().contains("EVIDENCE_FORBIDDEN_PATTERN:^\\d+$"));
        assertTrue(verdict.failureClass().contains("BAD_EVIDENCE"));
    }

    @Test
    void failsScopeLeakWhenManualSourceAnswerCitesOutsidePaper() {
        RagBenchmarkCase testCase = RagBenchmarkCase.productRescueCase(
                "manual_scope_leak",
                "只看第一篇论文的方法",
                "MANUAL_SOURCE_QA",
                List.of("paper-a"),
                List.of("method"),
                List.of("没有找到足够可靠")
        );
        RagBenchmarkActual actual = new RagBenchmarkActual(
                "MANUAL_SOURCE_QA",
                "**结论**\n方法来自另一篇论文。[1]",
                Map.of(1, reference("paper-b", 2, "Method", "The method section describes the architecture.")),
                Map.of("stopReason", "EXHAUSTED")
        );

        RagBenchmarkVerdict verdict = new RagBenchmarkEvaluator().evaluate(testCase, actual);

        assertFalse(verdict.passed());
        assertTrue(verdict.failures().contains("SCOPE_LEAK:paper-b"));
        assertTrue(verdict.failureClass().contains("SCOPE_CONTROL"));
    }

    private ChatHandler.ReferenceInfo reference(String paperId, int pageNumber, String sectionTitle, String matchedText) {
        return new ChatHandler.ReferenceInfo(
                paperId,
                "Agent Harness Paper",
                "agent-harness.pdf",
                pageNumber,
                matchedText,
                "HYBRID",
                "Hybrid",
                "query",
                matchedText,
                matchedText,
                0.9d,
                7,
                "PARAGRAPH",
                sectionTitle,
                2,
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
