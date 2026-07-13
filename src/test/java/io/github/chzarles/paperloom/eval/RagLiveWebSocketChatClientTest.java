package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.service.ChatHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagLiveWebSocketChatClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsScopedChatPayloadForManualSourceCases() throws Exception {
        RagBenchmarkCase testCase = new RagBenchmarkCase(
                "manual",
                "讲一讲高噪声场景",
                "zh",
                "EXPERIMENT_QA",
                "MANUAL_SOURCE",
                new RagBenchmarkCase.Scope(List.of("paper-a"), List.of("Paper A")),
                "MANUAL_SOURCE_QA",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("paper-a"),
                true
        );

        String payload = RagLiveWebSocketChatClient.payloadFor(testCase);
        JsonNode json = objectMapper.readTree(payload);

        assertEquals("chat", json.path("type").asText());
        assertEquals("讲一讲高噪声场景", json.path("message").asText());
        assertEquals("paper-a", json.path("scope").path("paperIds").get(0).asText());
        assertEquals("Paper A", json.path("scope").path("paperTitles").get(0).asText());
    }

    @Test
    void mapsSerializedReferenceMappingsToReferenceInfo() {
        Map<Integer, ChatHandler.ReferenceInfo> references = RagLiveWebSocketChatClient.toReferenceMappings(Map.of(
                "1", Map.of(
                        "paperId", "paper-a",
                        "paperTitle", "Paper A",
                        "originalFilename", "paper-a.pdf",
                        "pageNumber", 3,
                        "matchedChunkText", "The experiment studies increasing noise.",
                        "evidenceSnippet", "increasing noise",
                        "chunkId", 7,
                        "sourceKind", "TEXT"
                )
        ));

        assertEquals(1, references.size());
        assertEquals("paper-a", references.get(1).paperId());
        assertEquals(3, references.get(1).pageNumber());
        assertTrue(references.get(1).matchedChunkText().contains("increasing noise"));
    }
}
