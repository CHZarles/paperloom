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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QdrantClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new ArrayList<>();
    private HttpServer server;
    private QdrantClient client;
    private boolean collectionExists;
    private boolean conflictOnCreate;
    private boolean omitSparseVector;

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
    void createsSparseOnlyLexicalCollectionAndSearchesWithScopeFilter() throws Exception {
        client.ensureCollection();
        client.upsert(List.of(new QdrantPoint(
                "10000000-0000-0000-0000-000000000001",
                new QdrantSparseVector(List.of(7), List.of(1.0f)),
                Map.of("paper_id", "paper-a", "location_ref", "location_ref_a")
        )));
        List<QdrantSearchHit> hits = client.searchLexical(
                new QdrantSparseVector(List.of(7), List.of(1.0f)),
                client.filter(Map.of("paper-a", "rm-1"), 2, 4),
                8
        );

        JsonNode create = bodyFor("PUT", "/collections/test_reading_models");
        assertFalse(create.has("vectors"));
        assertEquals("idf", create.path("sparse_vectors").path("lexical_bm25_v1")
                .path("modifier").asText());
        assertEquals(5, requests.stream()
                .filter(item -> item.method().equals("PUT")
                        && item.path().equals("/collections/test_reading_models/index?wait=true"))
                .count());

        JsonNode upsert = bodyFor("PUT", "/collections/test_reading_models/points?wait=true");
        assertEquals("location_ref_a", upsert.path("points").get(0).path("payload").path("location_ref").asText());

        JsonNode search = bodyFor("POST", "/collections/test_reading_models/points/search");
        assertEquals("lexical_bm25_v1", search.path("vector").path("name").asText());
        assertEquals(7, search.path("vector").path("vector").path("indices").get(0).asInt());
        assertEquals(8, search.path("limit").asInt());
        assertEquals("paper_id", search.path("filter").path("must").get(0).path("key").asText());
        assertEquals("model_version", search.path("filter").path("must").get(1).path("key").asText());
        assertEquals("rm-1", search.path("filter").path("must").get(1)
                .path("match").path("value").asText());
        assertFalse(search.toString().contains("element_types"));
        assertEquals("location_ref_a", hits.get(0).payload().get("location_ref"));
    }

    @Test
    void scopeFilterKeepsEachPaperCoupledToItsCurrentReadingModel() {
        Map<String, Object> filter = client.filter(Map.of(
                "paper-a", "rm-a",
                "paper-b", "rm-b"
        ), 2, 4);

        JsonNode json = objectMapper.valueToTree(filter);
        Set<Set<String>> pairedValues = new java.util.LinkedHashSet<>();
        for (JsonNode branch : json.path("should")) {
            Set<String> values = new java.util.LinkedHashSet<>();
            for (JsonNode condition : branch.path("must")) {
                values.add(condition.path("match").path("value").asText());
            }
            pairedValues.add(values);
        }

        assertEquals(2, json.path("should").size());
        assertEquals(Set.of(
                Set.of("paper-a", "rm-a"),
                Set.of("paper-b", "rm-b")
        ), pairedValues);
        assertEquals("page_end_number", json.path("must").get(0).path("key").asText());
        assertEquals(2, json.path("must").get(0).path("range").path("gte").asInt());
        assertEquals("page_number", json.path("must").get(1).path("key").asText());
        assertEquals(4, json.path("must").get(1).path("range").path("lte").asInt());
    }

    @Test
    void retrievalVerificationRejectsMissingCollectionWithoutProvisioningIt() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                client::verifyCollection
        );

        assertTrue(error.getMessage().contains("missing"));
        assertEquals(0, requests.stream()
                .filter(item -> item.method().equals("PUT")
                        && item.path().equals("/collections/test_reading_models"))
                .count());
    }

    @Test
    void provisioningRechecksCollectionAvailabilityAfterEarlierSuccess() {
        client.ensureCollection();
        client.ensureCollection();

        assertEquals(2, requests.stream()
                .filter(item -> item.method().equals("GET")
                        && item.path().equals("/collections/test_reading_models"))
                .count());
        assertEquals(5, requests.stream()
                .filter(item -> item.method().equals("PUT")
                        && item.path().equals("/collections/test_reading_models/index?wait=true"))
                .count());
    }

    @Test
    void concurrentCollectionCreationConflictRechecksTheWinningCollection() {
        conflictOnCreate = true;

        client.ensureCollection();

        assertEquals(2, requests.stream()
                .filter(item -> item.method().equals("GET")
                        && item.path().equals("/collections/test_reading_models"))
                .count());
        assertEquals(1, requests.stream()
                .filter(item -> item.method().equals("PUT")
                        && item.path().equals("/collections/test_reading_models"))
                .count());
    }

    @Test
    void retrievalVerificationRejectsCollectionWithoutNamedSparseVector() {
        collectionExists = true;
        omitSparseVector = true;

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                client::verifyCollection
        );

        assertTrue(error.getMessage().contains("lexical_bm25_v1"));
    }

    @Test
    void countsTheSpecifiedPaperModelAndDeletesTheWholePaper() throws Exception {
        assertEquals(1, client.countByPaperIdAndModelVersion("paper-a", "rm-2"));
        client.deleteByPaperId("paper-a");

        JsonNode count = bodyFor("POST", "/collections/test_reading_models/points/count");
        assertEquals("paper_id", count.path("filter").path("must").get(0).path("key").asText());
        assertEquals("model_version", count.path("filter").path("must").get(1).path("key").asText());

        JsonNode delete = bodyFor("POST", "/collections/test_reading_models/points/delete?wait=true");
        assertEquals("paper_id", delete.path("filter").path("must").get(0).path("key").asText());
        assertEquals(1, delete.path("filter").path("must").size());
    }

    @Test
    void readsIndexedPaperIdsWithOneFacetRequest() {
        assertEquals(Set.of("paper-a", "paper-b"), client.indexedPaperIds(10_000));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().toString();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(exchange.getRequestMethod(), path, body));
        if ("GET".equals(exchange.getRequestMethod()) && path.equals("/collections/test_reading_models")) {
            if (collectionExists) {
                String sparse = omitSparseVector
                        ? "\"sparse_vectors\":{}"
                        : "\"sparse_vectors\":{\"lexical_bm25_v1\":{\"modifier\":\"idf\",\"index\":{\"on_disk\":true}}}";
                respond(exchange, 200,
                        "{\"result\":{\"config\":{\"params\":{\"vectors\":{},"
                                + sparse + "}},\"payload_schema\":{"
                                + "\"paper_id\":{\"data_type\":\"keyword\"},"
                                + "\"model_version\":{\"data_type\":\"keyword\"},"
                                + "\"element_types\":{\"data_type\":\"keyword\"},"
                                + "\"page_number\":{\"data_type\":\"integer\"},"
                                + "\"page_end_number\":{\"data_type\":\"integer\"}}},"
                                + "\"status\":\"ok\"}");
            } else {
                respond(exchange, 404, "{}");
            }
            return;
        }
        if ("PUT".equals(exchange.getRequestMethod()) && path.equals("/collections/test_reading_models")) {
            collectionExists = true;
            if (conflictOnCreate) {
                conflictOnCreate = false;
                respond(exchange, 409, "{\"status\":{\"error\":\"already exists\"}}");
            } else {
                respond(exchange, 200, "{\"result\":true,\"status\":\"ok\"}");
            }
            return;
        }
        if (path.endsWith("/points/search")) {
            respond(exchange, 200, """
                    {"result":[{"id":"10000000-0000-0000-0000-000000000001","score":0.9,
                    "payload":{"paper_id":"paper-a","location_ref":"location_ref_a"}}],"status":"ok"}
                    """);
            return;
        }
        if (path.endsWith("/points/count")) {
            respond(exchange, 200, "{\"result\":{\"count\":1},\"status\":\"ok\"}");
            return;
        }
        if (path.endsWith("/facet")) {
            respond(exchange, 200, "{\"result\":{\"hits\":[{\"value\":\"paper-a\",\"count\":2},{\"value\":\"paper-b\",\"count\":1}]},\"status\":\"ok\"}");
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
