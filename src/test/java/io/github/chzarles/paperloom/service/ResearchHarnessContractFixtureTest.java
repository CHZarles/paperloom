package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchHarnessContractFixtureTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resultFixtureMapsThroughSharedJavaMapper() throws Exception {
        ProductTurnRequest request = new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-1",
                "What does LoRA do?",
                SourceScope.manual(java.util.List.of("paper-1")),
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
