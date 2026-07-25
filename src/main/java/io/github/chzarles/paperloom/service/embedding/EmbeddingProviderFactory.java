package io.github.chzarles.paperloom.service.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.service.ModelProviderConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class EmbeddingProviderFactory {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final ModelProviderConfigService modelProviderConfigService;

    public EmbeddingProviderFactory(WebClient.Builder webClientBuilder,
                                    ObjectMapper objectMapper,
                                    ModelProviderConfigService modelProviderConfigService) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.modelProviderConfigService = modelProviderConfigService;
    }

    public EmbeddingProvider activeProvider() {
        ModelProviderConfigService.ActiveProviderView view =
                modelProviderConfigService.getActiveEmbeddingProvider();
        if (view.apiKey() == null || view.apiKey().isBlank()) {
            throw new CustomException(
                    "Embedding provider has no API key configured", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        String baseUrl = view.apiBaseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            baseUrl = baseUrl.replaceAll("/+$", "");
            // Strip "/v1" suffix if present so PATH can include it unambiguously.
            if (baseUrl.endsWith("/v1")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - "/v1".length());
            }
        }
        WebClient webClient = webClientBuilder.baseUrl(baseUrl).build();
        int dimension = view.dimension() == null ? 1536 : view.dimension();
        return new MiniMaxEmbeddingProvider(
                webClient,
                objectMapper,
                view.apiKey(),
                view.model(),
                dimension
        );
    }
}
