package io.github.chzarles.paperloom.service.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

public class MiniMaxEmbeddingProvider implements EmbeddingProvider {

    private static final String PATH = "/v1/embeddings";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final int dimension;

    public MiniMaxEmbeddingProvider(
            WebClient webClient,
            ObjectMapper objectMapper,
            String apiKey,
            String model,
            int dimension
    ) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
    }

    @Override
    public Mono<List<float[]>> embed(List<String> texts) {
        return embed(texts, "db");
    }

    @Override
    public Mono<List<float[]>> embedQueries(List<String> texts) {
        return embed(texts, "query");
    }

    private Mono<List<float[]>> embed(List<String> texts, String type) {
        if (texts == null || texts.isEmpty()) {
            return Mono.just(List.of());
        }
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("type", type);
        body.put("texts", texts);
        return webClient.post()
                .uri(PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseVectors);
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    private List<float[]> parseVectors(String responseBody) {
        try {
            ObjectMapper tolerant = objectMapper.copy()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            EmbeddingResponse response = tolerant.readValue(responseBody, EmbeddingResponse.class);
            if (response.baseResp() != null
                    && response.baseResp().statusCode() != null
                    && response.baseResp().statusCode() != 0) {
                throw new IllegalStateException(
                        "MiniMax embedding error: " + response.baseResp().statusMsg());
            }
            List<float[]> result = new ArrayList<>();
            if (response.vectors() == null) {
                return result;
            }
            for (List<Double> raw : response.vectors()) {
                float[] vector = new float[raw.size()];
                for (int i = 0; i < raw.size(); i++) {
                    vector[i] = raw.get(i).floatValue();
                }
                result.add(vector);
            }
            if (result.size() > 0 && result.get(0).length != dimension) {
                throw new IllegalStateException(
                        "MiniMax embedding dimension " + result.get(0).length
                                + " does not match configured " + dimension);
            }
            return result;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse MiniMax embedding response", ex);
        }
    }

    public record EmbeddingResponse(
            @JsonProperty("vectors") List<List<Double>> vectors,
            @JsonProperty("base_resp") BaseResp baseResp
    ) {
    }

    public record BaseResp(
            @JsonProperty("status_code") Integer statusCode,
            @JsonProperty("status_msg") String statusMsg
    ) {
    }
}
