package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductReadingTraceRecorderTest {

    @Test
    void recordsReadingTurnWithDistinctArtifactAndHarnessKind() {
        List<ProductTracePayload> submitted = new ArrayList<>();
        ProductReadingTraceRecorder recorder = new ProductReadingTraceRecorder(submitted::add);
        ProductTurnRequest request = new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-1",
                "推荐 Agentic eval 相关论文",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        );
        ProductTurnResult result = new ProductTurnResult(
                "x",
                new AnswerEnvelope(
                        AnswerType.CLARIFICATION_NEEDED,
                        "x",
                        List.of(),
                        List.of(),
                        List.of("x"),
                        List.of(),
                        List.of(),
                        ProductStopReason.ANSWER_SCHEMA_INVALID.name()
                ),
                List.of(),
                List.of(),
                ProductStopReason.ANSWER_SCHEMA_INVALID,
                ProductResultStatus.FAILED
        );

        recorder.recordReadingTurn(request, result, List.of(), List.of(), Instant.EPOCH, Instant.EPOCH);

        assertEquals(1, submitted.size());
        ProductTracePayload payload = submitted.get(0);
        assertEquals("PRODUCT_READING_REACT_TURN", payload.artifactType());
        assertEquals("PRODUCT_READING_REACT_TURN", payload.traceJson().get("artifactType"));
        assertEquals("READING_PHASE1", payload.traceJson().get("harnessKind"));
    }
}
