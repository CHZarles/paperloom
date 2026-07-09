package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductReadingLaunchTraceEvalRunnerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void matchesTraceCasesAndWritesStandardEvalArtifacts() throws Exception {
        Path traceRoot = tempDir.resolve("traces");
        writeTrace(traceRoot.resolve("conversation-a").resolve("reading-turn-browse.json"), trace(
                "PRODUCT_STATE",
                List.of("list_papers"),
                List.of(Map.of(
                        "kind", "READING_PAPER_CHOICE",
                        "sourceTool", "list_papers",
                        "paperHandle", "paper_handle_abc"
                )),
                List.of()
        ));
        writeTrace(traceRoot.resolve("conversation-a").resolve("reading-turn-read.json"), trace(
                "EVIDENCE_ANSWER",
                List.of("read_locations"),
                List.of(),
                List.of(Map.of("sourceQuoteRef", "source_quote_abc"))
        ));
        Path cases = cases("""
                {"id":"browse_cards","requiredToolNames":["list_papers"],"requiredAnswerType":"PRODUCT_STATE","requiredProductStateKinds":["READING_PAPER_CHOICE"],"requiredProductStateSourceTools":["list_papers"],"requiresReference":false}
                {"id":"read_source_quotes","requiredToolNames":["read_locations"],"requiredAnswerType":"EVIDENCE_ANSWER","requiresReference":true}
                """);

        ProductReadingLaunchTraceEvalRunner runner = new ProductReadingLaunchTraceEvalRunner();
        Path runDir = runner.run(new ProductReadingLaunchTraceEvalRunner.Options(
                traceRoot,
                cases,
                tempDir.resolve("runs"),
                "reading-trace-pass",
                "2026-07-07T10:00:00Z",
                "product-reading-launch-trace-eval",
                "product-reading-launch-trace"
        ));

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(2, scorecard.path("caseCount").asInt());
        assertEquals(2, scorecard.path("passed").asInt());
        assertEquals(1.0d, scorecard.path("passRate").asDouble());
        assertEquals(1.0d, scorecard.path("metrics").path("matchedTraceCaseRate").asDouble());
        JsonNode rows = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases");
        assertTrue(rows.get(0).path("diagnostics").path("matchedTracePath").asText().contains("reading-turn-browse.json"));
        assertTrue(rows.get(1).path("diagnostics").path("matchedTracePath").asText().contains("reading-turn-read.json"));
    }

    @Test
    void failsCaseWhenNoTraceMatchesRequiredTool() throws Exception {
        Path traceRoot = tempDir.resolve("traces");
        writeTrace(traceRoot.resolve("conversation-a").resolve("reading-turn-browse.json"), trace(
                "PRODUCT_STATE",
                List.of("list_papers"),
                List.of(Map.of(
                        "kind", "READING_PAPER_CHOICE",
                        "sourceTool", "list_papers",
                        "paperHandle", "paper_handle_abc"
                )),
                List.of()
        ));
        Path cases = cases("""
                {"id":"trace_source_quotes","requiredToolNames":["trace_source_quotes"],"requiredAnswerType":"EVIDENCE_ANSWER","requiresReference":true}
                """);

        ProductReadingLaunchTraceEvalRunner runner = new ProductReadingLaunchTraceEvalRunner();
        Path runDir = runner.run(new ProductReadingLaunchTraceEvalRunner.Options(
                traceRoot,
                cases,
                tempDir.resolve("runs"),
                "reading-trace-fail",
                "2026-07-07T10:05:00Z",
                "product-reading-launch-trace-eval",
                "product-reading-launch-trace"
        ));

        JsonNode row = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases").get(0);
        assertEquals(false, row.path("passed").asBoolean());
        assertTrue(row.path("failures").toString().contains("matching_reading_trace_missing"));
        assertTrue(row.path("failureClass").toString().contains("TRACE_MISSING"));
    }

    @Test
    void canRequireCanonicalVerifiedResearchTrace() throws Exception {
        Path traceRoot = tempDir.resolve("traces");
        writeTrace(traceRoot.resolve("conversation-a").resolve("reading-turn-evidence.json"), trace(
                "EVIDENCE_ANSWER",
                List.of("read_locations"),
                List.of(),
                List.of(Map.of("sourceQuoteRef", "source_quote_abc"))
        ));
        Path cases = cases("""
                {"id":"verified_trace","requiredToolNames":["read_locations"],"requiredAnswerType":"EVIDENCE_ANSWER","requiresReference":true,"requiresResearchTrace":true,"requiresVerifiedResearchTrace":true}
                """);

        ProductReadingLaunchTraceEvalRunner runner = new ProductReadingLaunchTraceEvalRunner();
        Path runDir = runner.run(new ProductReadingLaunchTraceEvalRunner.Options(
                traceRoot,
                cases,
                tempDir.resolve("runs"),
                "reading-trace-verified",
                "2026-07-08T10:00:00Z",
                "product-reading-launch-trace-eval",
                "product-reading-launch-trace"
        ));

        JsonNode row = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases").get(0);
        assertEquals(true, row.path("passed").asBoolean());
        assertEquals(true, row.path("diagnostics").path("matchedResearchTrace").asBoolean());
        assertEquals(true, row.path("diagnostics").path("matchedResearchTraceVerified").asBoolean());
    }

    private Path cases(String content) throws Exception {
        Path cases = tempDir.resolve("cases-" + System.nanoTime() + ".jsonl");
        Files.writeString(cases, content);
        return cases;
    }

    private void writeTrace(Path path, Map<String, Object> trace) throws Exception {
        Files.createDirectories(path.getParent());
        OBJECT_MAPPER.writeValue(path.toFile(), trace);
    }

    private Map<String, Object> trace(String answerType,
                                      List<String> toolNames,
                                      List<Map<String, Object>> productStateItems,
                                      List<Map<String, Object>> references) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("artifactType", "PRODUCT_READING_REACT_TURN");
        trace.put("traceVersion", 5);
        trace.put("resultStatus", "COMPLETED");
        trace.put("answerEnvelope", Map.of("answerType", answerType, "answer", "ok"));
        trace.put("toolCalls", toolNames.stream()
                .map(toolName -> Map.<String, Object>of("toolName", toolName, "resultJson", Map.of()))
                .toList());
        trace.put("productStateItems", productStateItems);
        trace.put("references", references);
        trace.put("researchTrace", researchTrace());
        return trace;
    }

    private Map<String, Object> researchTrace() {
        return Map.of(
                "schemaVersion", "research-harness-artifacts/v1",
                "intentFrame", Map.of("questionId", "case"),
                "retrievalPlan", Map.of("planId", "plan"),
                "evidenceLedger", Map.of("ledgerId", "ledger"),
                "claimGraph", Map.of("graphId", "graph"),
                "reasoningArtifacts", List.of(Map.of("artifactId", "reasoning")),
                "verificationPass", Map.of("verificationId", "verification", "valid", true),
                "researchAnswer", Map.of("answerId", "answer")
        );
    }
}
