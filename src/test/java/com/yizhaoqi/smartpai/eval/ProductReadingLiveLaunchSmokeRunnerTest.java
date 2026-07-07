package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.ChatHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductReadingLiveLaunchSmokeRunnerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void chainsPaperAndSourceQuoteAnchorsAndWritesPassingRun() throws Exception {
        Path cases = cases("""
                {"id":"browse","message":"列出可读论文","requiredToolNames":["list_papers"],"requiresProductStateItem":true,"requiredProductStateSourceTools":["list_papers"]}
                {"id":"outline","message":"看第一篇的大纲","focusPaperHandleFromCase":"browse","requiredToolNames":["get_paper_outline"]}
                {"id":"read","message":"读第一篇的方法段落","focusPaperHandleFromCase":"browse","requiredToolNames":["read_locations"],"requiresReference":true}
                {"id":"trace","message":"解释刚才这个来源","focusSourceQuoteRefFromCase":"read","requiredToolNames":["trace_source_quotes"],"requiresReference":true}
                """);
        FakeReadingClient client = new FakeReadingClient(List.of(
                response(List.of("list_papers"), List.of(paperChoice("paper_handle_a", "list_papers")), Map.of()),
                response(List.of("get_paper_outline"), List.of(), Map.of()),
                response(List.of("read_locations"), List.of(), Map.of(1, reference("source_quote_a"))),
                response(List.of("trace_source_quotes"), List.of(), Map.of(1, reference("source_quote_a")))
        ));

        ProductReadingLiveLaunchSmokeRunner runner = new ProductReadingLiveLaunchSmokeRunner(client);
        Path runDir = runner.run(new ProductReadingLiveLaunchSmokeRunner.Options(
                cases,
                tempDir.resolve("runs"),
                "live-reading-pass",
                "2026-07-07T12:00:00Z",
                "product-reading-live-launch-smoke",
                "product-reading-live-launch-smoke",
                "conversation-live"
        ));

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(4, scorecard.path("caseCount").asInt());
        assertEquals(4, scorecard.path("passed").asInt());
        assertEquals(1.0d, scorecard.path("passRate").asDouble());

        assertEquals("conversation-live", client.requests.get(0).conversationId());
        assertFalse(client.requests.get(0).referenceFocus().containsKey("paperHandle"));
        assertEquals("paper_handle_a", client.requests.get(1).referenceFocus().get("paperHandle"));
        assertEquals("paper_handle_a", client.requests.get(2).referenceFocus().get("paperHandle"));
        assertEquals("source_quote_a", client.requests.get(3).referenceFocus().get("sourceQuoteRef"));

        JsonNode rows = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases");
        assertEquals("paper_handle_a", rows.get(1).path("diagnostics").path("referenceFocus").path("paperHandle").asText());
        assertEquals("source_quote_a", rows.get(3).path("diagnostics").path("referenceFocus").path("sourceQuoteRef").asText());
    }

    @Test
    void failsDependentCaseWithoutCallingClientWhenRequiredAnchorIsMissing() throws Exception {
        Path cases = cases("""
                {"id":"browse","message":"列出可读论文","requiredToolNames":["list_papers"],"requiresProductStateItem":true}
                {"id":"outline","message":"看第一篇的大纲","focusPaperHandleFromCase":"browse","requiredToolNames":["get_paper_outline"]}
                """);
        FakeReadingClient client = new FakeReadingClient(List.of(
                response(List.of("list_papers"), List.of(), Map.of())
        ));

        ProductReadingLiveLaunchSmokeRunner runner = new ProductReadingLiveLaunchSmokeRunner(client);
        Path runDir = runner.run(new ProductReadingLiveLaunchSmokeRunner.Options(
                cases,
                tempDir.resolve("runs"),
                "live-reading-anchor-missing",
                "2026-07-07T12:05:00Z",
                "product-reading-live-launch-smoke",
                "product-reading-live-launch-smoke",
                "conversation-live"
        ));

        assertEquals(1, client.requests.size());
        JsonNode rows = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases");
        assertFalse(rows.get(0).path("passed").asBoolean());
        assertTrue(rows.get(0).path("failures").toString().contains("product_state_item_missing"));
        assertFalse(rows.get(1).path("passed").asBoolean());
        assertTrue(rows.get(1).path("failures").toString().contains("paper_handle_anchor_missing"));
        assertTrue(rows.get(1).path("failureClass").toString().contains("ANCHOR_MISSING"));
    }

    @Test
    void invalidPaperChoiceItemsDoNotSatisfyProductStateOrAnchorRequirements() throws Exception {
        Path cases = cases("""
                {"id":"browse","message":"列出可读论文","requiredToolNames":["list_papers"],"requiresProductStateItem":true,"requiredProductStateSourceTools":["list_papers"]}
                {"id":"outline","message":"看第一篇的大纲","focusPaperHandleFromCase":"browse","requiredToolNames":["get_paper_outline"]}
                """);
        FakeReadingClient client = new FakeReadingClient(List.of(
                response(List.of("list_papers"), List.of(Map.of(
                        "kind", "READING_PAPER_CHOICE",
                        "sourceTool", "list_papers",
                        "paperHandle", "raw-paper-id",
                        "title", "Invalid"
                )), Map.of())
        ));

        ProductReadingLiveLaunchSmokeRunner runner = new ProductReadingLiveLaunchSmokeRunner(client);
        Path runDir = runner.run(new ProductReadingLiveLaunchSmokeRunner.Options(
                cases,
                tempDir.resolve("runs"),
                "live-reading-invalid-card",
                "2026-07-07T12:06:00Z",
                "product-reading-live-launch-smoke",
                "product-reading-live-launch-smoke",
                "conversation-live"
        ));

        JsonNode rows = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases");
        assertFalse(rows.get(0).path("passed").asBoolean());
        assertTrue(rows.get(0).path("failures").toString().contains("product_state_item_missing"));
        assertFalse(rows.get(1).path("passed").asBoolean());
        assertTrue(rows.get(1).path("failures").toString().contains("paper_handle_anchor_missing"));
    }

    private Path cases(String content) throws Exception {
        Path cases = tempDir.resolve("cases-" + System.nanoTime() + ".jsonl");
        Files.writeString(cases, content);
        return cases;
    }

    private static Map<String, Object> paperChoice(String paperHandle, String sourceTool) {
        return Map.of(
                "kind", "READING_PAPER_CHOICE",
                "sourceTool", sourceTool,
                "paperHandle", paperHandle,
                "title", "Launch Paper"
        );
    }

    private static ProductReadingLiveLaunchSmokeRunner.LiveReadingChatResponse response(
            List<String> toolNames,
            List<Map<String, Object>> productStateItems,
            Map<Integer, ChatHandler.ReferenceInfo> references) {
        return new ProductReadingLiveLaunchSmokeRunner.LiveReadingChatResponse(
                "ok",
                references,
                Map.of("harness", "PRODUCT_READING_REACT"),
                productStateItems,
                toolNames
        );
    }

    private static ChatHandler.ReferenceInfo reference(String sourceQuoteRef) {
        return new ChatHandler.ReferenceInfo(
                "paper-a",
                "Paper A",
                "paper-a.pdf",
                1,
                "quoted text",
                null,
                null,
                null,
                "quoted text",
                "quoted text",
                null,
                null,
                "TEXT",
                null,
                null,
                null,
                null,
                null,
                "TEXT",
                null,
                null,
                null,
                "NORMAL_TEXT",
                "PRODUCT_READING",
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                true,
                false,
                false,
                null,
                null,
                sourceQuoteRef,
                List.of()
        );
    }

    private static final class FakeReadingClient implements ProductReadingLiveLaunchSmokeRunner.LiveReadingChatClient {
        private final List<ProductReadingLiveLaunchSmokeRunner.LiveReadingChatResponse> responses;
        private final List<ProductReadingLiveLaunchSmokeRunner.LiveReadingChatRequest> requests = new ArrayList<>();

        private FakeReadingClient(List<ProductReadingLiveLaunchSmokeRunner.LiveReadingChatResponse> responses) {
            this.responses = new ArrayList<>(responses);
        }

        @Override
        public ProductReadingLiveLaunchSmokeRunner.LiveReadingChatResponse ask(
                ProductReadingLiveLaunchSmokeRunner.LiveReadingChatRequest request) {
            requests.add(request);
            return responses.remove(0);
        }
    }
}
