package io.github.chzarles.paperloom.service.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MiniMaxEmbeddingProviderTest {

    private HttpServer server;
    private AtomicReference<String> lastRequestBody;
    private AtomicReference<String> lastAuthHeader;
    private MiniMaxEmbeddingProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        lastRequestBody = new AtomicReference<>();
        lastAuthHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes()));
                String body = """
                        {
                          "vectors": [
                            [0.1, 0.2, 0.3],
                            [0.4, 0.5, 0.6]
                          ],
                          "base_resp": {"status_code": 0}
                        }
                        """;
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body.getBytes());
                }
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        provider = new MiniMaxEmbeddingProvider(
                webClient,
                new ObjectMapper(),
                "test-key",
                "embo-01",
                3
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void postsEmbeddingsRequestAndParsesFloatArrays() {
        Mono<List<float[]>> mono = provider.embed(List.of("alpha", "beta"));
        List<float[]> result = mono.block();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(0.1f, result.get(0)[0], 0.0001f);
        assertEquals(0.6f, result.get(1)[2], 0.0001f);

        String body = lastRequestBody.get();
        assertNotNull(body, "no request received");
        assertEquals("Bearer test-key", lastAuthHeader.get());
        assertEquals(true, body.contains("\"model\":\"embo-01\""));
        assertEquals(true, body.contains("\"texts\":[\"alpha\",\"beta\"]"));
        assertEquals(true, body.contains("\"type\":\"db\""));

        provider.embedQueries(List.of("question")).block();
        assertEquals(true, lastRequestBody.get().contains("\"type\":\"query\""));
    }

    @Test
    void exposesConfiguredModelAndDimension() {
        assertEquals("embo-01", provider.modelName());
        assertEquals(3, provider.dimension());
    }
}
