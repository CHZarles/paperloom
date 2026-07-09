package com.yizhaoqi.smartpai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductReadingTraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(ProductReadingTraceRecorder.class);
    private static final String ARTIFACT_TYPE = "PRODUCT_READING_REACT_TURN";
    private static final String HARNESS_KIND = "READING_TRACE_SOURCE_QUOTES_MVP";
    private static final String READING_LOOP_STAGE = "TRACE_SOURCE_QUOTES_MVP";

    private final ProductTraceSink traceSink;

    public ProductReadingTraceRecorder(ProductTraceSink traceSink) {
        this.traceSink = traceSink;
    }

    public boolean recordReadingTurn(ProductTurnRequest request,
                                     ProductTurnResult result,
                                     List<Map<String, Object>> llmCalls,
                                     List<Map<String, Object>> toolCalls,
                                     Instant startedAt,
                                     Instant finishedAt) {
        try {
            ProductTurnRequest safeRequest = request == null
                    ? new ProductTurnRequest(null, "", "", "", SourceScope.auto(), List.of(), Map.of(), ProductModelContext.defaults())
                    : request;
            ProductTurnResult safeResult = result == null
                    ? new ProductTurnResult("", null, List.of(), List.of(), ProductStopReason.COMPLETED, ProductResultStatus.COMPLETED)
                    : result;
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("artifactType", ARTIFACT_TYPE);
            trace.put("harnessKind", HARNESS_KIND);
            trace.put("readingLoopStage", READING_LOOP_STAGE);
            trace.put("traceVersion", 5);
            trace.put("conversationId", safeRequest.conversationId());
            trace.put("generationId", safeRequest.generationId());
            trace.put("userId", safeRequest.userId());
            trace.put("scopeSnapshot", scopeSnapshot(safeRequest.lockedScope()));
            trace.put("startedAt", startedAt == null ? null : startedAt.toString());
            trace.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
            trace.put("input", Map.of(
                    "userMessage", safeRequest.userMessage(),
                    "historyMessageCount", safeRequest.history().size(),
                    "memory", safeRequest.memory()
            ));
            trace.put("llmCalls", llmCalls == null ? List.of() : llmCalls);
            trace.put("toolCalls", toolCalls == null ? List.of() : toolCalls);
            trace.put("answerEnvelope", safeResult.envelope());
            trace.put("productStateItems", productStateItems(safeResult.productStateItems()));
            trace.put("readingArtifacts", safeResult.readingArtifacts());
            trace.put("readingStatePatch", safeResult.readingStatePatch());
            trace.put("researchTrace", safeResult.researchTrace());
            trace.put("references", safeResult.references());
            trace.put("stopReason", safeResult.stopReason().name());
            trace.put("resultStatus", safeResult.resultStatus().name());
            trace.put("errors", List.of());

            traceSink.submit(new ProductTracePayload(
                    safeRequest.conversationId(),
                    safeRequest.generationId(),
                    ARTIFACT_TYPE,
                    "reading-turn-" + safeSegment(safeRequest.generationId()) + ".json",
                    trace
            ));
            return true;
        } catch (Exception exception) {
            log.warn("reading_trace_submit_failed conversationId={} generationId={} artifactType={}",
                    request == null ? "" : request.conversationId(),
                    request == null ? "" : request.generationId(),
                    ARTIFACT_TYPE,
                    exception);
            return false;
        }
    }

    private Map<String, Object> scopeSnapshot(SourceScope scope) {
        SourceScope safeScope = scope == null ? SourceScope.auto() : scope;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("scopeMode", safeScope.mode().name());
        snapshot.put("paperCount", safeScope.paperIds().size());
        snapshot.put("immutable", true);
        return snapshot;
    }

    private List<Map<String, Object>> productStateItems(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copies = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            copies.add(new LinkedHashMap<>(item));
        }
        return List.copyOf(copies);
    }

    private String safeSegment(String value) {
        String raw = value == null || value.isBlank() ? "unknown" : value.trim();
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
