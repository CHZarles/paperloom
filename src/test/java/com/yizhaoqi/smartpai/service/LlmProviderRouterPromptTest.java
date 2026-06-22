package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmProviderRouterPromptTest {

    @Test
    void promptAllowsPaperTitlesInRecommendationAnswers() {
        AiProperties aiProperties = new AiProperties();
        LlmProviderRouter router = new LlmProviderRouter(aiProperties, null, null, null, new ObjectMapper());

        List<Map<String, Object>> messages = router.buildReActMessages(
                "推荐一下和agent相关的论文",
                "",
                List.of()
        );

        String systemPrompt = String.valueOf(messages.get(0).get("content"));
        assertTrue(systemPrompt.contains("正文可以写论文标题"));
        assertTrue(systemPrompt.contains("必须列出论文标题和推荐理由"));
        assertFalse(systemPrompt.contains("不要写论文标题"));
        assertFalse(systemPrompt.contains("不要在正文写论文名"));
    }

    @Test
    void skipsCitationOnlyAssistantHistory() {
        LlmProviderRouter router = new LlmProviderRouter(new AiProperties(), null, null, null, new ObjectMapper());

        List<Map<String, Object>> messages = router.buildReActMessages(
                "推荐一下和agent相关的论文",
                "",
                List.of(
                        Map.of("role", "assistant", "content", "**推荐**\n[1] [2] [3]"),
                        Map.of("role", "assistant", "content", "可以重点看 Agent Harness 对检索行为的影响。 [1]")
                )
        );

        assertFalse(messages.stream().anyMatch(message -> String.valueOf(message.get("content")).contains("[1] [2] [3]")));
        assertTrue(messages.stream().anyMatch(message -> String.valueOf(message.get("content")).contains("Agent Harness")));
    }

    @Test
    void disablesMiniMaxM3ThinkingInOpenAiRequests() {
        LlmProviderRouter router = new LlmProviderRouter(new AiProperties(), null, null, null, new ObjectMapper());

        Map<String, Object> request = ReflectionTestUtils.invokeMethod(
                router,
                "buildReActRequest",
                "MiniMax-M3",
                List.of(Map.of("role", "user", "content", "ping")),
                List.of(),
                16,
                false
        );

        assertEquals(Map.of("type", "disabled"), request.get("thinking"));
    }
}
