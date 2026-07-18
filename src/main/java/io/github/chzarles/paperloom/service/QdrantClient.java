package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.config.QdrantProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class QdrantClient {

    static final String LEXICAL_VECTOR_NAME = "lexical_bm25_v1";

    private final ObjectMapper objectMapper;
    private final QdrantProperties properties;
    private final HttpClient httpClient;

    public QdrantClient(ObjectMapper objectMapper, QdrantProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(safeDuration(properties.getConnectTimeout(), Duration.ofSeconds(5)))
                .build();
    }

    public synchronized void ensureCollection() {
        HttpResponse<String> existing = send("GET", collectionPath(), null, true);
        if (existing.statusCode() == 404) {
            Map<String, Object> body = Map.of("sparse_vectors", Map.of(
                    LEXICAL_VECTOR_NAME,
                    Map.of("modifier", "idf", "index", Map.of("on_disk", true))
            ));
            HttpResponse<String> created = send("PUT", collectionPath(), body, false);
            if (created.statusCode() == 409) {
                existing = send("GET", collectionPath(), null, false);
            } else {
                requireSuccess(created, "create Qdrant collection");
                existing = null;
            }
        }
        if (existing != null) {
            validateCollection(existing);
        }
        ensurePayloadIndexes(existing);
    }

    public void verifyCollection() {
        HttpResponse<String> existing = send("GET", collectionPath(), null, true);
        if (existing.statusCode() == 404) {
            throw new IllegalStateException("Qdrant collection is missing: " + properties.getCollection());
        }
        validateCollection(existing);
    }

    private void validateCollection(HttpResponse<String> existing) {
        requireSuccess(existing, "inspect Qdrant collection");
        JsonNode params = readCollectionParams(existing.body());
        JsonNode vectors = params.path("vectors");
        if (vectors.isObject() && vectors.size() > 0) {
            throw new IllegalStateException("Qdrant lexical collection must not contain dense vectors");
        }
        JsonNode sparseVectors = params.path("sparse_vectors");
        if (!sparseVectors.has(LEXICAL_VECTOR_NAME)) {
            throw new IllegalStateException("Qdrant collection is missing " + LEXICAL_VECTOR_NAME);
        }
        if (sparseVectors.size() != 1) {
            throw new IllegalStateException("Qdrant lexical collection contains unexpected sparse vectors");
        }
        String modifier = sparseVectors.path(LEXICAL_VECTOR_NAME).path("modifier").asText("");
        if (!"idf".equalsIgnoreCase(modifier)) {
            throw new IllegalStateException("Qdrant lexical sparse vector must use modifier=idf");
        }
        if (!sparseVectors.path(LEXICAL_VECTOR_NAME).path("index").path("on_disk").asBoolean(false)) {
            throw new IllegalStateException("Qdrant lexical sparse index must use on_disk=true");
        }
    }

    public void upsert(List<QdrantPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        int batchSize = Math.max(1, properties.getUpsertBatchSize());
        for (int start = 0; start < points.size(); start += batchSize) {
            List<Map<String, Object>> batch = new ArrayList<>();
            for (QdrantPoint point : points.subList(start, Math.min(points.size(), start + batchSize))) {
                batch.add(Map.of(
                        "id", point.id(),
                        "vector", Map.of(LEXICAL_VECTOR_NAME, Map.of(
                                "indices", point.lexicalVector().indices(),
                                "values", point.lexicalVector().values()
                        )),
                        "payload", point.payload()
                ));
            }
            requireSuccess(send(
                    "PUT",
                    collectionPath() + "/points?wait=true",
                    Map.of("points", batch),
                    false
            ), "upsert Qdrant points");
        }
    }

    public void deleteByPaperId(String paperId) {
        if (paperId == null || paperId.isBlank()) {
            return;
        }
        HttpResponse<String> response = send(
                "POST",
                collectionPath() + "/points/delete?wait=true",
                Map.of("filter", Map.of("must", List.of(matchValue("paper_id", paperId)))),
                true
        );
        if (response.statusCode() != 404) {
            requireSuccess(response, "delete Qdrant paper points");
        }
    }

    public void deleteCollectionIfExists() {
        HttpResponse<String> response = send("DELETE", collectionPath(), null, true);
        if (response.statusCode() != 404) {
            requireSuccess(response, "delete Qdrant collection");
        }
    }

    public long countByPaperId(String paperId) {
        if (paperId == null || paperId.isBlank()) {
            return 0;
        }
        return count(Map.of("must", List.of(matchValue("paper_id", paperId))));
    }

    public long countByPaperIdAndModelVersion(String paperId, String modelVersion) {
        if (paperId == null || paperId.isBlank() || modelVersion == null || modelVersion.isBlank()) {
            return 0;
        }
        return count(Map.of("must", List.of(
                matchValue("paper_id", paperId),
                matchValue("model_version", modelVersion)
        )));
    }

    public Set<String> indexedPaperIds(int limit) {
        HttpResponse<String> response = send(
                "POST",
                collectionPath() + "/facet",
                Map.of("key", "paper_id", "limit", Math.max(1, limit), "exact", true),
                false
        );
        requireSuccess(response, "facet Qdrant paper ids");
        try {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (JsonNode hit : objectMapper.readTree(response.body()).path("result").path("hits")) {
                String value = hit.path("value").asText("").trim();
                if (!value.isBlank()) {
                    result.add(value);
                }
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid Qdrant facet response", exception);
        }
    }

    private long count(Map<String, Object> filter) {
        HttpResponse<String> response = send(
                "POST",
                collectionPath() + "/points/count",
                Map.of(
                        "filter", filter,
                        "exact", true
                ),
                false
        );
        requireSuccess(response, "count Qdrant paper points");
        try {
            return objectMapper.readTree(response.body()).path("result").path("count").asLong(0);
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid Qdrant count response", exception);
        }
    }

    public List<QdrantSearchHit> searchLexical(QdrantSparseVector vector,
                                               Map<String, Object> filter,
                                               int limit) {
        return search(Map.of(
                "name", LEXICAL_VECTOR_NAME,
                "vector", Map.of("indices", vector.indices(), "values", vector.values())
        ), filter, limit);
    }

    public Map<String, Object> filter(Map<String, String> activeModels,
                                      Integer pageFrom,
                                      Integer pageTo) {
        List<Map<String, Object>> must = new ArrayList<>();
        if (activeModels != null && !activeModels.isEmpty()) {
            if (activeModels.size() == 1) {
                Map.Entry<String, String> entry = activeModels.entrySet().iterator().next();
                must.add(matchValue("paper_id", entry.getKey()));
                must.add(matchValue("model_version", entry.getValue()));
            } else {
                List<Map<String, Object>> activePairs = activeModels.entrySet().stream()
                        .map(entry -> Map.<String, Object>of("must", List.of(
                                matchValue("paper_id", entry.getKey()),
                                matchValue("model_version", entry.getValue())
                        )))
                        .toList();
                Map<String, Object> filter = new LinkedHashMap<>();
                filter.put("should", activePairs);
                addPageRange(must, pageFrom, pageTo);
                if (!must.isEmpty()) {
                    filter.put("must", must);
                }
                return filter;
            }
        }
        addPageRange(must, pageFrom, pageTo);
        return must.isEmpty() ? Map.of() : Map.of("must", must);
    }

    private void addPageRange(List<Map<String, Object>> must, Integer pageFrom, Integer pageTo) {
        if (pageFrom != null) {
            must.add(Map.of("key", "page_end_number", "range", Map.of("gte", pageFrom)));
        }
        if (pageTo != null) {
            must.add(Map.of("key", "page_number", "range", Map.of("lte", pageTo)));
        }
    }

    public String indexVersion() {
        return properties.getCollection();
    }

    private List<QdrantSearchHit> search(Map<String, Object> vector,
                                         Map<String, Object> filter,
                                         int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", vector);
        if (filter != null && !filter.isEmpty()) {
            body.put("filter", filter);
        }
        body.put("limit", Math.max(1, limit));
        body.put("with_payload", true);
        HttpResponse<String> response = send("POST", collectionPath() + "/points/search", body, false);
        requireSuccess(response, "search Qdrant points");
        try {
            List<QdrantSearchHit> hits = new ArrayList<>();
            for (JsonNode item : objectMapper.readTree(response.body()).path("result")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = objectMapper.convertValue(item.path("payload"), Map.class);
                hits.add(new QdrantSearchHit(item.path("id").asText(), item.path("score").asDouble(), payload));
            }
            return hits;
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid Qdrant search response", exception);
        }
    }

    private HttpResponse<String> send(String method,
                                      String path,
                                      Object body,
                                      boolean allowNotFound) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                    .timeout(safeDuration(properties.getRequestTimeout(), Duration.ofSeconds(20)))
                    .header("Accept", "application/json");
            String apiKey = properties.getApiKey() == null ? "" : properties.getApiKey().trim();
            if (!apiKey.isBlank()) {
                builder.header("api-key", apiKey);
            }
            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (!allowNotFound && response.statusCode() == 404) {
                throw new IllegalStateException("Qdrant collection is missing: " + properties.getCollection());
            }
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant request interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Qdrant request failed", exception);
        }
    }

    private void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        String body = response.body() == null ? "" : response.body();
        throw new IllegalStateException(operation + " failed with HTTP " + response.statusCode() + ": "
                + body.substring(0, Math.min(body.length(), 1000)));
    }

    private JsonNode readCollectionParams(String body) {
        return readCollectionResult(body).path("config").path("params");
    }

    private JsonNode readCollectionResult(String body) {
        try {
            return objectMapper.readTree(body)
                    .path("result");
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid Qdrant collection response", exception);
        }
    }

    private void ensurePayloadIndexes(HttpResponse<String> collectionResponse) {
        Map<String, String> indexes = new LinkedHashMap<>();
        indexes.put("paper_id", "keyword");
        indexes.put("model_version", "keyword");
        indexes.put("element_types", "keyword");
        indexes.put("page_number", "integer");
        indexes.put("page_end_number", "integer");
        JsonNode payloadSchema = collectionResponse == null
                ? objectMapper.createObjectNode()
                : readCollectionResult(collectionResponse.body()).path("payload_schema");
        for (Map.Entry<String, String> index : indexes.entrySet()) {
            if (payloadSchema.has(index.getKey())) {
                continue;
            }
            requireSuccess(send(
                    "PUT",
                    collectionPath() + "/index?wait=true",
                    Map.of("field_name", index.getKey(), "field_schema", index.getValue()),
                    false
            ), "create Qdrant payload index " + index.getKey());
        }
    }

    private URI uri(String path) {
        String baseUrl = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("qdrant.base-url must not be blank");
        }
        return URI.create(baseUrl.replaceAll("/+$", "") + path);
    }

    private String collectionPath() {
        String collection = properties.getCollection() == null ? "" : properties.getCollection().trim();
        if (!collection.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalStateException("qdrant.collection contains unsupported characters");
        }
        return "/collections/" + URLEncoder.encode(collection, StandardCharsets.UTF_8);
    }

    private Map<String, Object> matchValue(String key, String value) {
        return Map.of("key", key, "match", Map.of("value", value));
    }

    private Duration safeDuration(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
