package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskRouterTest {

    @Test
    void routesLibraryStatusFromStructuredLlmOutput() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        when(llm.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(turn("""
                        {
                          "taskType": "LIBRARY_STATUS",
                          "operation": "COUNT_SEARCHABLE_PAPERS",
                          "query": "",
                          "confidence": 0.92,
                          "reason": "The user asks for searchable paper count."
                        }
                        """));
        TaskRouter router = new LlmTaskRouter(llm, new ObjectMapper());

        TaskRoutingResult result = router.route(new TaskRoutingRequest(
                "u1",
                "c1",
                "有多少论文可以检索",
                SourceScope.auto()
        ));

        assertTrue(result.routed());
        assertEquals(TaskType.LIBRARY_STATUS, result.decision().taskType());
        assertEquals(TaskOperation.COUNT_SEARCHABLE_PAPERS, result.decision().operation());
        assertFalse(result.failed());
    }

    @Test
    void invalidLlmOutputFailsClosedWithoutTaskDecision() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        when(llm.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(turn("not json"));
        TaskRouter router = new LlmTaskRouter(llm, new ObjectMapper());

        TaskRoutingResult result = router.route(new TaskRoutingRequest(
                "u1",
                "c1",
                "介绍一下 LoRA",
                SourceScope.auto()
        ));

        assertTrue(result.failed());
        assertEquals(TaskRoutingFailure.ReasonCode.INVALID_JSON, result.failure().reasonCode());
    }

    private LlmProviderRouter.ReActTurn turn(String content) {
        return new LlmProviderRouter.ReActTurn(
                content,
                List.of(),
                Map.of("role", "assistant", "content", content),
                "stop",
                10,
                5
        );
    }
}
