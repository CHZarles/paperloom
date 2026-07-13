package io.github.chzarles.paperloom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.service.ChatHandler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductReadingLiveWebSocketChatClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void payloadIncludesConversationIdAndReferenceFocusForStructuredChatBoundary() throws Exception {
        String payload = ProductReadingLiveWebSocketChatClient.payloadFor(
                new ProductReadingLiveLaunchSmokeRunner.LiveReadingChatRequest(
                        "trace",
                        "解释刚才这个来源",
                        "conversation-live",
                        Map.of("sourceQuoteRef", "source_quote_a")
                )
        );

        JsonNode root = OBJECT_MAPPER.readTree(payload);
        assertEquals("chat", root.path("type").asText());
        assertEquals("解释刚才这个来源", root.path("message").asText());
        assertEquals("conversation-live", root.path("conversationId").asText());
        assertEquals("source_quote_a", root.path("referenceFocus").path("sourceQuoteRef").asText());
    }

    @Test
    void referenceMappingConversionPreservesSourceQuoteRefsForFollowupAnchors() {
        Map<Integer, ChatHandler.ReferenceInfo> mappings =
                ProductReadingLiveWebSocketChatClient.toReferenceMappings(Map.of(
                        "1",
                        Map.of(
                                "paperId", "paper-a",
                                "paperTitle", "Paper A",
                                "sourceQuoteRef", "source_quote_a",
                                "matchedChunkText", "quoted text"
                        )
                ));

        assertEquals("source_quote_a", mappings.get(1).sourceQuoteRef());
        assertEquals("quoted text", mappings.get(1).matchedChunkText());
    }
}
