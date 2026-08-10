package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResearchHarnessResultMapperTest {

    @Test
    void mapsHarnessResultIntoProductTurnResult() {
        ResearchHarnessResultMapper mapper = new ResearchHarnessResultMapper(new ObjectMapper());
        ProductTurnRequest request = new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-1",
                "question",
                SourceScope.manual(List.of("paper-1")),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        );
        Map<String, Object> response = Map.of(
                "run_id", "run-trace-1",
                "status", "COMPLETED",
                "answer", Map.of("markdown", "answer [1]"),
                "citations", List.of(Map.of(
                        "reference_number", 1,
                        "evidence_id", "ev_1",
                        "source_quote_ref", "source_quote_1",
                        "paper_id", "paper-1",
                        "title", "Paper",
                        "span_text", "quoted text",
                        "location_ref", "page_ref_1",
                        "source_kind", "TEXT"
                )),
                "trace", Map.of(
                        "finish_reason", "accepted",
                        "tool_calls", List.of(Map.of("tool_name", "read_locations")),
                        "paper_candidates", List.of(Map.of("paper_id", "paper-1", "title", "Paper"))
                )
        );

        ProductTurnResult result = mapper.toProductResult(request, response);

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals("answer [1]", result.finalAnswerMarkdown());
        assertEquals(1, result.references().size());
        assertEquals("ev_1", result.references().get(0).get("evidenceRef"));
        assertEquals("source_quote_1", result.references().get(0).get("sourceQuoteRef"));
        assertEquals("PYTHON_RESEARCH_HARNESS", result.references().get(0).get("retrievalRoute"));
        assertEquals("run-trace-1", result.diagnostics().get("agentTraceRunId"));
        assertEquals(1, result.productStateItems().size());
        assertEquals("paper_handle_paper-1", result.productStateItems().get(0).get("paperHandle"));
    }

    @Test
    void mapsControlledLimitWithoutTurningItIntoAFailure() {
        ResearchHarnessResultMapper mapper = new ResearchHarnessResultMapper(new ObjectMapper());
        ProductTurnResult result = mapper.toProductResult(new ProductTurnRequest(
                7L, "conversation-1", "generation-1", "question", SourceScope.manual(List.of("paper-1")),
                List.of(), Map.of(), ProductModelContext.defaults()), Map.of(
                "status", "LIMITED",
                "answer", Map.of("markdown", "limit notice"),
                "control", Map.of("reason_code", "RUN_MODEL_CALL_LIMIT", "usage", Map.of("total_tokens", 12))
        ));

        assertEquals(ProductResultStatus.LIMITED, result.resultStatus());
        assertEquals(ProductStopReason.MAX_MODEL_CALLS, result.stopReason());
        assertEquals("RUN_MODEL_CALL_LIMIT", result.diagnostics().get("reasonCode"));
    }
}
