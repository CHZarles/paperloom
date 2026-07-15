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

    public synchronized void ensureCollection(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("Qdrant dense vector dimension must be positive");
        }
        HttpResponse<String> existing = send("GET", collectionPath(), null, true);
        if (existing.statusCode() == 404) {
            Map<String, Object> body = Map.of(
                    "vectors", Map.of("dense", Map.of("size", dimension, "distance", "Cosine")),
                    "sparse_vectors", Map.of("sparse", Map.of("index", Map.of("on_disk", true)))
            );
            HttpResponse<String> created = send("PUT", collectionPath(), body, false);
            if (created.statusCode() == 409) {
                validateCollection(send("GET", collectionPath(), null, false), dimension);
            } else {
                requireSuccess(created, "create Qdrant collection");
            }
        } else {
            validateCollection(existing, dimension);
        }
        ensurePayloadIndexes();
    }

    public void verifyCollection(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("Qdrant dense vector dimension must be positive");
        }
        HttpResponse<String> existing = send("GET", collectionPath(), null, true);
        if (existing.statusCode() == 404) {
            throw new IllegalStateException("Qdrant collection is missing: " + properties.getCollection());
        }
        validateCollection(existing, dimension);
    }

    private void validateCollection(HttpResponse<String> existing, int dimension) {
        requireSuccess(existing, "inspect Qdrant collection");
        JsonNode params = readCollectionParams(existing.body());
        int configuredDimension = params.path("vectors").path("dense").path("size").asInt(0);
        if (configuredDimension <= 0) {
            throw new IllegalStateException("Qdrant collection is missing the named dense vector");
        }
        if (configuredDimension != dimension) {
            throw new IllegalStateException("Qdrant collection dense dimension is " + configuredDimension
                    + " but the active embedding provider returned " + dimension);
        }
        if (!params.path("sparse_vectors").has("sparse")) {
            throw new IllegalStateException("Qdrant collection is missing the named sparse vector");
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
                        "vector", Map.of(
                                "dense", point.denseVector(),
                                "sparse", Map.of(
                                        "indices", point.sparseVector().indices(),
                                        "values", point.sparseVector().values()
                                )
                        ),
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
        requireSuccess(send(
                "POST",
                collectionPath() + "/points/delete?wait=true",
                Map.of("filter", Map.of("must", List.of(matchValue("paper_id", paperId)))),
                false
        ), "delete Qdrant paper points");
    }

    public void deleteByPaperIdAndGeneration(String paperId, String indexGeneration) {
        if (paperId == null || paperId.isBlank() || indexGeneration == null || indexGeneration.isBlank()) {
            throw new IllegalArgumentException("paperId and indexGeneration are required");
        }
        requireSuccess(send(
                "POST",
                collectionPath() + "/points/delete?wait=true",
                Map.of("filter", Map.of("must", List.of(
                        matchValue("paper_id", paperId),
                        matchValue("index_generation", indexGeneration)
                ))),
                false
        ), "delete Qdrant paper generation");
    }

    public long countByPaperId(String paperId) {
        if (paperId == null || paperId.isBlank()) {
            return 0;
        }
        return count(Map.of("must", List.of(matchValue("paper_id", paperId))));
    }

    public long countByPaperIdAndGeneration(String paperId, String indexGeneration) {
        if (paperId == null || paperId.isBlank() || indexGeneration == null || indexGeneration.isBlank()) {
            return 0;
        }
        return count(Map.of("must", List.of(
                matchValue("paper_id", paperId),
                matchValue("index_generation", indexGeneration)
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

    public List<QdrantSearchHit> searchDense(float[] vector, Map<String, Object> filter, int limit) {
        return search(Map.of("name", "dense", "vector", vector), filter, limit);
    }

    public List<QdrantSearchHit> searchSparse(QdrantSparseVector vector,
                                              Map<String, Object> filter,
                                              int limit) {
        return search(Map.of(
                "name", "sparse",
                "vector", Map.of("indices", vector.indices(), "values", vector.values())
        ), filter, limit);
    }

    public Map<String, Object> filter(Map<String, String> activeGenerations,
                                      Integer pageFrom,
                                      Integer pageTo) {
        List<Map<String, Object>> must = new ArrayList<>();
        if (activeGenerations != null && !activeGenerations.isEmpty()) {
            if (activeGenerations.size() == 1) {
                Map.Entry<String, String> entry = activeGenerations.entrySet().iterator().next();
                must.add(matchValue("paper_id", entry.getKey()));
                must.add(matchValue("index_generation", entry.getValue()));
            } else {
                List<Map<String, Object>> activePairs = activeGenerations.entrySet().stream()
                        .map(entry -> Map.<String, Object>of("must", List.of(
                                matchValue("paper_id", entry.getKey()),
                                matchValue("index_generation", entry.getValue())
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
        if (pageFrom == null && pageTo == null) {
            return;
        }
        Map<String, Object> range = new LinkedHashMap<>();
        if (pageFrom != null) {
            range.put("gte", pageFrom);
        }
        if (pageTo != null) {
            range.put("lte", pageTo);
        }
        must.add(Map.of("key", "page_number", "range", range));
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
        try {
            return objectMapper.readTree(body)
                    .path("result")
                    .path("config")
                    .path("params");
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid Qdrant collection response", exception);
        }
    }

    private void ensurePayloadIndexes() {
        Map<String, String> indexes = new LinkedHashMap<>();
        indexes.put("paper_id", "keyword");
        indexes.put("model_version", "keyword");
        indexes.put("index_generation", "keyword");
        indexes.put("element_types", "keyword");
        indexes.put("page_number", "integer");
        indexes.put("owner_user_id", "keyword");
        indexes.put("org_tag", "keyword");
        indexes.put("is_public", "bool");
        for (Map.Entry<String, String> index : indexes.entrySet()) {
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
