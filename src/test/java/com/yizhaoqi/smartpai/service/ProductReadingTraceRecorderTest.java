package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductReadingTraceRecorderTest {

    @Test
    void recordsReadingTurnWithDistinctArtifactTraceSourceQuoteStageAndProductStateItems() {
        List<ProductTracePayload> submitted = new ArrayList<>();
        ProductReadingTraceRecorder recorder = new ProductReadingTraceRecorder(submitted::add);
        List<Map<String, Object>> productStateItems = List.of(Map.of(
                "kind", "READING_PAPER_CHOICE",
                "sourceTool", "list_papers",
                "paperHandle", "paper_handle_abc",
                "title", "Agentic Eval Benchmark"
        ));
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
                productStateItems,
                ProductStopReason.ANSWER_SCHEMA_INVALID,
                ProductResultStatus.FAILED
        );
        List<Map<String, Object>> toolCalls = List.of(Map.of(
                "toolName", "trace_source_quotes",
                "resultJson", Map.of("sourceQuotes", List.of(Map.of("sourceQuoteRef", "source_quote_abc")))
        ));

        recorder.recordReadingTurn(
                request,
                result,
                List.of(),
                toolCalls,
                Instant.EPOCH,
                Instant.EPOCH
        );

        assertEquals(1, submitted.size());
        ProductTracePayload payload = submitted.get(0);
        assertEquals("PRODUCT_READING_REACT_TURN", payload.artifactType());
        assertEquals("PRODUCT_READING_REACT_TURN", payload.traceJson().get("artifactType"));
        assertEquals(4, payload.traceJson().get("traceVersion"));
        assertEquals("TRACE_SOURCE_QUOTES_MVP", payload.traceJson().get("readingLoopStage"));
        assertEquals("READING_TRACE_SOURCE_QUOTES_MVP", payload.traceJson().get("harnessKind"));
        assertEquals(toolCalls, payload.traceJson().get("toolCalls"));
        assertEquals(productStateItems, payload.traceJson().get("productStateItems"));
        assertEquals(List.of(Map.of("sourceQuoteRef", "source_quote_abc")), payload.traceJson().get("references"));
    }
}
