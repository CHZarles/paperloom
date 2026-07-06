package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductReadingTraceRecorderTest {

    @Test
    void recordsReadingTurnWithDistinctArtifactAndSourceQuoteStage() {
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
                List.of(Map.of("sourceQuoteRef", "source_quote_abc")),
                List.of(),
                ProductStopReason.ANSWER_SCHEMA_INVALID,
                ProductResultStatus.FAILED
        );

        recorder.recordReadingTurn(
                request,
                result,
                List.of(),
                List.of(Map.of(
                        "toolName", "read_locations",
                        "result", Map.of("sourceQuotes", List.of(Map.of("sourceQuoteRef", "source_quote_abc")))
                )),
                Instant.EPOCH,
                Instant.EPOCH
        );

        assertEquals(1, submitted.size());
        ProductTracePayload payload = submitted.get(0);
        assertEquals("PRODUCT_READING_REACT_TURN", payload.artifactType());
        assertEquals("PRODUCT_READING_REACT_TURN", payload.traceJson().get("artifactType"));
        assertEquals(2, payload.traceJson().get("traceVersion"));
        assertEquals("SOURCE_QUOTE_MVP", payload.traceJson().get("readingLoopStage"));
        assertEquals("READING_SOURCE_QUOTE_MVP", payload.traceJson().get("harnessKind"));
        assertEquals(List.of(Map.of("sourceQuoteRef", "source_quote_abc")), payload.traceJson().get("references"));
    }
}
