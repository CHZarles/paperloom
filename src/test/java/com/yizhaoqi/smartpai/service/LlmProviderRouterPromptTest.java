package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.config.OutboundWebClientFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmProviderRouterPromptTest {

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

    @Test
    void reactRequestOmitsMaxCompletionTokensWhenUnlimited() {
        LlmProviderRouter router = new LlmProviderRouter(new AiProperties(), null, null, null, new ObjectMapper());

        Map<String, Object> unlimitedRequest = ReflectionTestUtils.invokeMethod(
                router,
                "buildReActRequest",
                "MiniMax-M3",
                List.of(Map.of("role", "user", "content", "ping")),
                List.of(),
                0,
                false
        );
        Map<String, Object> cappedRequest = ReflectionTestUtils.invokeMethod(
                router,
                "buildReActRequest",
                "MiniMax-M3",
                List.of(Map.of("role", "user", "content", "ping")),
                List.of(),
                16,
                false
        );

        assertFalse(unlimitedRequest.containsKey("max_tokens"));
        assertEquals(16, cappedRequest.get("max_tokens"));
    }

    @Test
    void reactTurnFailureIncludesProviderStatusBodyAndRequestDiagnosticsWithoutSecrets() throws Exception {
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = """
                    {"error":{"message":"messages with role 'tool' must follow a tool_call","type":"invalid_request_error"}}
                    """.getBytes();
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            RateLimitService rateLimitService = Mockito.mock(RateLimitService.class);
            UsageQuotaService usageQuotaService = Mockito.mock(UsageQuotaService.class);
            ModelProviderConfigService modelProviderConfigService = Mockito.mock(ModelProviderConfigService.class);
            UsageQuotaService.TokenReservationBundle reservation = UsageQuotaService.TokenReservationBundle.noop("llm", "user-1");
            when(rateLimitService.reserveLlmUsage(eq("user-1"), anyInt(), anyInt())).thenReturn(reservation);
            when(usageQuotaService.estimateTextTokens(org.mockito.ArgumentMatchers.any())).thenReturn(1);
            when(modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM))
                    .thenReturn(new ModelProviderConfigService.ActiveProviderView(
                            "minimax",
                            "MiniMax",
                            "openai-compatible",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "MiniMax-M3",
                            "sk-secret-value",
                            null
                    ));
            LlmProviderRouter router = new LlmProviderRouter(
                    new AiProperties(),
                    rateLimitService,
                    usageQuotaService,
                    modelProviderConfigService,
                    new ObjectMapper(),
                    new OutboundWebClientFactory()
            );

            RuntimeException exception = assertThrows(RuntimeException.class, () -> router.completeReActTurn(
                    "user-1",
                    List.of(
                            Map.of("role", "system", "content", "system prompt"),
                            Map.of("role", "user", "content", "find locations")
                    ),
                    List.of(),
                    16
            ));

            String message = exception.getMessage();
            assertTrue(message.contains("HTTP 400"));
            assertTrue(message.contains("messages with role 'tool' must follow a tool_call"));
            assertTrue(message.contains("provider=minimax"));
            assertTrue(message.contains("model=MiniMax-M3"));
            assertTrue(message.contains("messageCount=2"));
            assertTrue(message.contains("roles=system,user"));
            assertFalse(message.contains("sk-secret-value"));
            assertEquals("Bearer sk-secret-value", authorizationHeader.get());
            verify(usageQuotaService).abortReservation(reservation);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reactTurnRetriesTransientProviderOverloadWithoutDoubleSettlingReservation() throws Exception {
        AtomicReference<Integer> requestCount = new AtomicReference<>(0);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int attempt = requestCount.updateAndGet(value -> value + 1);
            if (attempt == 1) {
                byte[] response = """
                        {"error":{"message":"temporarily overloaded","type":"overloaded_error"}}
                        """.getBytes();
                exchange.sendResponseHeaders(529, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            byte[] response = """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "{\\"answerType\\":\\"NON_EVIDENCE\\",\\"answer\\":\\"ok\\",\\"evidenceBasedClaims\\":[],\\"stateClaims\\":[],\\"limitations\\":[],\\"nonEvidenceNotes\\":[],\\"missingFields\\":[],\\"reason\\":\\"\\"}"
                          },
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {"prompt_tokens": 10, "completion_tokens": 5}
                    }
                    """.getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            RateLimitService rateLimitService = Mockito.mock(RateLimitService.class);
            UsageQuotaService usageQuotaService = Mockito.mock(UsageQuotaService.class);
            ModelProviderConfigService modelProviderConfigService = Mockito.mock(ModelProviderConfigService.class);
            UsageQuotaService.TokenReservationBundle reservation = UsageQuotaService.TokenReservationBundle.noop("llm", "user-1");
            when(rateLimitService.reserveLlmUsage(eq("user-1"), anyInt(), anyInt())).thenReturn(reservation);
            when(usageQuotaService.estimateTextTokens(org.mockito.ArgumentMatchers.any())).thenReturn(1);
            when(modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM))
                    .thenReturn(new ModelProviderConfigService.ActiveProviderView(
                            "minimax",
                            "MiniMax",
                            "openai-compatible",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "MiniMax-M3",
                            "sk-secret-value",
                            null
                    ));
            LlmProviderRouter router = new LlmProviderRouter(
                    new AiProperties(),
                    rateLimitService,
                    usageQuotaService,
                    modelProviderConfigService,
                    new ObjectMapper(),
                    new OutboundWebClientFactory()
            );

            LlmProviderRouter.ReActTurn turn = router.completeReActTurn(
                    "user-1",
                    List.of(Map.of("role", "user", "content", "ping")),
                    List.of(),
                    16
            );

            assertEquals(2, requestCount.get());
            assertEquals("stop", turn.finishReason());
            assertTrue(turn.content().contains("NON_EVIDENCE"));
            verify(usageQuotaService).settleReservation(reservation, 15);
            verify(usageQuotaService, never()).abortReservation(reservation);
        } finally {
            server.stop(0);
        }
    }
}
