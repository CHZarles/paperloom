package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchHarnessContractFixtureTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void jobFixtureKeepsRetryContextAndPayloadGenerationInSync() throws Exception {
        Map<String, Object> job = readFixture("job-v1.json");
        Map<String, Object> payload = objectMap(job.get("payload_json"));
        Map<String, Object> retry = objectMap(payload.get("retry"));
        Map<String, Object> scope = objectMap(payload.get("scope"));

        assertEquals("research-harness-job/v1", job.get("schema_version"));
        assertEquals(job.get("generation_id"), payload.get("request_id"));
        assertEquals("SOURCE_SET_SNAPSHOT", scope.get("mode"));
        assertFalse(listValue(scope.get("paper_ids")).isEmpty());
        assertEquals("USER_UNSATISFIED", retry.get("kind"));
        assertEquals(retry.get("answer_slot_id"), retry.get("retry_of_conversation_record_id"));
        assertTrue(((Number) retry.get("target_revision")).intValue() > 1);
    }

    @Test
    void eventFixtureUsesTerminalResultWithHttpCompatiblePayload() throws Exception {
        Map<String, Object> event = readFixture("event-v1.json");
        Map<String, Object> payload = objectMap(event.get("payload_json"));
        Map<String, Object> answer = objectMap(payload.get("answer"));

        assertEquals("research-harness-event/v1", event.get("schema_version"));
        assertEquals("result", event.get("type"));
        assertEquals(event.get("generation_id"), payload.get("request_id"));
        assertFalse(String.valueOf(answer.get("markdown")).isBlank());
    }

    @Test
    void resultFixtureMapsThroughSharedJavaMapper() throws Exception {
        ProductTurnRequest request = new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-1",
                "What does LoRA do?",
                SourceScope.manual(java.util.List.of("paper-1"), RetrievalBudgetProfile.INTERACTIVE),
                java.util.List.of(),
                Map.of(),
                ProductModelContext.defaults()
        );
        ProductTurnResult result = new ResearchHarnessResultMapper(objectMapper)
                .toProductResult(request, readFixture("result-v1.json"));

        assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
        assertEquals(1, result.references().size());
        assertTrue(result.finalAnswerMarkdown().contains("LoRA"));
    }

    private Map<String, Object> readFixture(String name) throws Exception {
        return objectMapper.readValue(
                Files.readString(Path.of("docs/contracts/research-harness", name)),
                new TypeReference<Map<String, Object>>() {
                }
        );
    }

    private Map<String, Object> objectMap(Object value) throws Exception {
        if (value instanceof String raw) {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    private java.util.List<?> listValue(Object value) {
        return value instanceof java.util.List<?> list ? list : java.util.List.of();
    }
}
