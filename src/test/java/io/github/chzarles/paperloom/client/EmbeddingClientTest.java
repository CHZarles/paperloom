package io.github.chzarles.paperloom.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.chzarles.paperloom.config.OutboundWebClientFactory;
import io.github.chzarles.paperloom.service.ModelProviderConfigService;
import io.github.chzarles.paperloom.service.RateLimitService;
import io.github.chzarles.paperloom.service.UsageQuotaService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class EmbeddingClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldCallMiniMaxNativeEmbeddingApiAndParseVectors() throws Exception {
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] response = """
                    {"vectors":[[0.1,0.2]],"model":"embo-01","total_tokens":8,"base_resp":{"status_code":0,"status_msg":"success"}}
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
            UsageQuotaService.TokenReservationBundle reservation = UsageQuotaService.TokenReservationBundle.noop("embedding-query", "user-1");
            when(rateLimitService.reserveEmbeddingQueryUsage(eq("user-1"), anyList())).thenReturn(reservation);
            when(modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING))
                    .thenReturn(new ModelProviderConfigService.ActiveProviderView(
                            "minimax",
                            "MiniMax",
                            "minimax-embedding",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "embo-01",
                            "sk-minimax",
                            1536
                    ));
            EmbeddingClient client = new EmbeddingClient(
                    objectMapper,
                    rateLimitService,
                    usageQuotaService,
                    modelProviderConfigService,
                    new OutboundWebClientFactory()
            );
            ReflectionTestUtils.setField(client, "batchSize", 10);

            EmbeddingClient.EmbeddingUsageResult result = client.embedWithUsage(
                    List.of("hello"),
                    "user-1",
                    EmbeddingClient.UsageType.QUERY,
                    Duration.ofSeconds(5)
            );

            assertEquals("Bearer sk-minimax", authorizationHeader.get());
            assertEquals("/v1/embeddings", requestPath.get());
            JsonNode payload = objectMapper.readTree(requestBody.get());
            assertEquals("embo-01", payload.path("model").asText());
            assertEquals("hello", payload.path("texts").path(0).asText());
            assertEquals("query", payload.path("type").asText());
            assertFalse(payload.has("input"));
            assertFalse(payload.has("encoding_format"));
            assertEquals(8, result.totalTokens());
            assertEquals("minimax:embo-01:1536", result.modelVersion());
            assertEquals(1, result.vectors().size());
            assertEquals(2, result.vectors().get(0).length);
            assertEquals(0.1f, result.vectors().get(0)[0], 0.0001f);
            assertTrue(result.vectors().get(0)[1] > 0.19f);
            verify(usageQuotaService).settleReservation(reservation, 8);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void embeddingOperationPinsOneProviderAcrossAllBatches() throws Exception {
        List<String> requestBodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes()));
            byte[] response = """
                    {"data":[{"embedding":[0.1,0.2]}],"usage":{"total_tokens":2}}
                    """.getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            RateLimitService rateLimitService = Mockito.mock(RateLimitService.class);
            UsageQuotaService usageQuotaService = Mockito.mock(UsageQuotaService.class);
            ModelProviderConfigService providerService = Mockito.mock(ModelProviderConfigService.class);
            UsageQuotaService.TokenReservationBundle reservation =
                    UsageQuotaService.TokenReservationBundle.noop("embedding-query", "user-1");
            when(rateLimitService.reserveEmbeddingQueryUsage(eq("user-1"), anyList())).thenReturn(reservation);
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ModelProviderConfigService.ActiveProviderView first = new ModelProviderConfigService.ActiveProviderView(
                    "aliyun", "Aliyun", "openai-compatible", baseUrl, "embedding-v1", "sk-1", 2);
            ModelProviderConfigService.ActiveProviderView changed = new ModelProviderConfigService.ActiveProviderView(
                    "aliyun", "Aliyun", "openai-compatible", baseUrl, "embedding-v2", "sk-2", 2);
            when(providerService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING))
                    .thenReturn(first, changed, changed);
            EmbeddingClient client = new EmbeddingClient(
                    objectMapper,
                    rateLimitService,
                    usageQuotaService,
                    providerService,
                    new OutboundWebClientFactory()
            );
            ReflectionTestUtils.setField(client, "batchSize", 1);

            EmbeddingClient.EmbeddingUsageResult result = client.embedWithUsage(
                    List.of("first", "second"),
                    "user-1",
                    EmbeddingClient.UsageType.QUERY,
                    Duration.ofSeconds(5)
            );

            assertEquals(2, requestBodies.size());
            assertEquals("embedding-v1", objectMapper.readTree(requestBodies.get(0)).path("model").asText());
            assertEquals("embedding-v1", objectMapper.readTree(requestBodies.get(1)).path("model").asText());
            assertEquals("aliyun:embedding-v1:2", result.modelVersion());
            verify(providerService, times(1)).getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        } finally {
            server.stop(0);
        }
    }
}
