package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.chzarles.paperloom.config.QdrantProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QdrantClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new ArrayList<>();
    private HttpServer server;
    private QdrantClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        QdrantProperties properties = new QdrantProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setCollection("test_reading_models");
        client = new QdrantClient(objectMapper, properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void createsNamedDenseSparseCollectionAndSearchesWithScopeFilter() throws Exception {
        client.ensureCollection(3);
        client.upsert(List.of(new QdrantPoint(
                "10000000-0000-0000-0000-000000000001",
                new float[]{1.0f, 0.0f, 0.0f},
                new QdrantSparseVector(List.of(7), List.of(1.0f)),
                Map.of("paper_id", "paper-a", "location_ref", "location_ref_a")
        )));
        List<QdrantSearchHit> hits = client.searchDense(
                new float[]{1.0f, 0.0f, 0.0f},
                client.filter(List.of("paper-a"), List.of("paragraph"), 2, 4),
                8
        );

        JsonNode create = bodyFor("PUT", "/collections/test_reading_models");
        assertEquals(3, create.path("vectors").path("dense").path("size").asInt());
        assertTrue(create.path("sparse_vectors").has("sparse"));

        JsonNode upsert = bodyFor("PUT", "/collections/test_reading_models/points?wait=true");
        assertEquals("location_ref_a", upsert.path("points").get(0).path("payload").path("location_ref").asText());

        JsonNode search = bodyFor("POST", "/collections/test_reading_models/points/search");
        assertEquals("dense", search.path("vector").path("name").asText());
        assertEquals(8, search.path("limit").asInt());
        assertEquals("paper_id", search.path("filter").path("must").get(0).path("key").asText());
        assertEquals("location_ref_a", hits.get(0).payload().get("location_ref"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().toString();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(exchange.getRequestMethod(), path, body));
        if ("GET".equals(exchange.getRequestMethod()) && path.equals("/collections/test_reading_models")) {
            respond(exchange, 404, "{}");
            return;
        }
        if (path.endsWith("/points/search")) {
            respond(exchange, 200, """
                    {"result":[{"id":"10000000-0000-0000-0000-000000000001","score":0.9,
                    "payload":{"paper_id":"paper-a","location_ref":"location_ref_a"}}],"status":"ok"}
                    """);
            return;
        }
        respond(exchange, 200, "{\"result\":true,\"status\":\"ok\"}");
    }

    private JsonNode bodyFor(String method, String path) throws Exception {
        CapturedRequest request = requests.stream()
                .filter(item -> item.method().equals(method) && item.path().equals(path))
                .findFirst()
                .orElseThrow();
        return objectMapper.readTree(request.body());
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CapturedRequest(String method, String path, String body) {
    }
}
