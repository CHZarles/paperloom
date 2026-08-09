package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResearchHarnessPayloadFactoryTest {

    @Test
    void requestBodyUsesSnakeCaseRedisContractShape() {
        ResearchHarnessPayloadFactory factory = new ResearchHarnessPayloadFactory();
        ProductTurnRequest request = new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-1",
                "LoRA 是什么？",
                SourceScope.manual(List.of("paper-1")),
                List.of(Map.of("role", "user", "content", "上一轮")),
                Map.of(
                        "selected_paper_ids", List.of("paper-1"),
                        "selected_evidence_ids", List.of("ev_1"),
                        "previous_evidence", List.of(Map.of("evidence_id", "ev_1")),
                        "ignored", "value"
                ),
                new ProductModelContext(100, 2200)
        );

        Map<String, Object> body = factory.requestBody(request);

        assertEquals("generation-1", body.get("request_id"));
        assertEquals("conversation-1", body.get("conversation_id"));
        assertEquals(7L, body.get("user_id"));
        assertEquals("LoRA 是什么？", body.get("user_message"));
        assertEquals(List.of(Map.of("role", "user", "content", "上一轮")), body.get("history"));
        assertNull(body.get("retry"));

        @SuppressWarnings("unchecked")
        Map<String, Object> scope = (Map<String, Object>) body.get("scope");
        assertEquals(List.of("paper-1"), scope.get("paper_ids"));

        @SuppressWarnings("unchecked")
        Map<String, Object> memory = (Map<String, Object>) body.get("research_memory");
        assertEquals(List.of("paper-1"), memory.get("selected_paper_ids"));
        assertEquals(List.of("ev_1"), memory.get("selected_evidence_ids"));
        assertEquals(false, memory.containsKey("ignored"));

        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) body.get("options");
        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) options.get("run_limits");
        assertEquals("paperloom-run-limits/v1", limits.get("schema_version"));
        assertEquals(12, limits.get("max_model_calls"));
    }

    @Test
    void requestBodyIncludesOptionalRetryContext() {
        ResearchHarnessPayloadFactory factory = new ResearchHarnessPayloadFactory();
        Map<String, Object> retry = Map.of(
                "kind", "USER_UNSATISFIED",
                "retry_of_generation_id", "generation-parent",
                "answer_slot_id", 12L,
                "target_revision", 2
        );
        ProductTurnRequest request = new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-retry",
                "LoRA 是什么？",
                SourceScope.manual(List.of("paper-1")),
                List.of(),
                Map.of(),
                retry,
                ProductModelContext.defaults(),
                ignored -> {}
        );

        Map<String, Object> body = factory.requestBody(request);

        assertEquals(retry, body.get("retry"));
    }
}
