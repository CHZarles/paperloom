package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.ModelProviderConfig;
import io.github.chzarles.paperloom.repository.ModelProviderConfigRepository;
import io.github.chzarles.paperloom.utils.SecretCryptoService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class ModelProviderConfigService {

    public static final String SCOPE_LLM = "llm";
    public static final String SCOPE_EMBEDDING = "embedding";
    public static final String API_STYLE_OPENAI = "openai-compatible";
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final ModelProviderConfigRepository repository;
    private final SecretCryptoService secretCryptoService;
    private final String minimaxApiKey;
    private volatile ScopeSettingsView currentSettings;
    private volatile ScopeSettingsView embeddingSettings;

    public ModelProviderConfigService(ModelProviderConfigRepository repository,
                                      SecretCryptoService secretCryptoService,
                                      @Value("${MINIMAX_API_KEY:}") String minimaxApiKey) {
        this.repository = repository;
        this.secretCryptoService = secretCryptoService;
        this.minimaxApiKey = minimaxApiKey == null ? "" : minimaxApiKey.trim();
        this.currentSettings = defaultSettings();
        this.embeddingSettings = defaultEmbeddingSettings();
    }

    @PostConstruct
    public void loadPersistedConfigs() {
        reloadSettings();
    }

    public ActiveProviderView getActiveProvider(String scope) {
        return resolveActiveProvider(scope, "No active LLM provider is configured");
    }

    public ActiveProviderView getActiveEmbeddingProvider() {
        return resolveActiveProvider(SCOPE_EMBEDDING, "No active embedding provider is configured");
    }

    private ActiveProviderView resolveActiveProvider(String scope, String missingMessage) {
        ProviderConfigView provider = settingsFor(scope).providers().stream()
                .filter(ProviderConfigView::active)
                .filter(ProviderConfigView::enabled)
                .findFirst()
                .orElseThrow(() -> new CustomException(
                        missingMessage,
                        HttpStatus.INTERNAL_SERVER_ERROR
                ));
        return new ActiveProviderView(
                provider.provider(),
                provider.displayName(),
                provider.apiStyle(),
                provider.apiBaseUrl(),
                provider.model(),
                resolveProviderApiKey(scope, provider.provider()).orElse(null),
                provider.dimension()
        );
    }

    private synchronized ScopeSettingsView settingsFor(String scope) {
        if (SCOPE_EMBEDDING.equalsIgnoreCase(scope)) {
            if (embeddingSettings == null) {
                embeddingSettings = mergeOverrides(defaultEmbeddingSettings(), repository.findAll(), SCOPE_EMBEDDING);
            }
            return embeddingSettings;
        }
        return currentSettings;
    }

    public synchronized void reloadSettings() {
        currentSettings = mergeOverrides(defaultSettings(), repository.findAll(), SCOPE_LLM);
        embeddingSettings = mergeOverrides(defaultEmbeddingSettings(), repository.findAll(), SCOPE_EMBEDDING);
    }

    private ScopeSettingsView defaultSettings() {
        return new ScopeSettingsView(
                SCOPE_LLM,
                "minimax",
                List.of(
                        new ProviderConfigView("minimax", "MiniMax", API_STYLE_OPENAI,
                                "https://api.minimaxi.com/v1", "MiniMax-M3", true, true, null)
                )
        );
    }

    private ScopeSettingsView defaultEmbeddingSettings() {
        return new ScopeSettingsView(
                SCOPE_EMBEDDING,
                "minimax",
                List.of(
                        new ProviderConfigView("minimax", "MiniMax", API_STYLE_OPENAI,
                                "https://api.minimaxi.com/v1", "embo-01", true, true, 1536)
                )
        );
    }

    private ScopeSettingsView mergeOverrides(ScopeSettingsView defaults,
                                              List<ModelProviderConfig> configs,
                                              String scope) {
        Map<String, ProviderConfigView> providers = new LinkedHashMap<>();
        for (ProviderConfigView provider : defaults.providers()) {
            providers.put(provider.provider(), provider);
        }

        List<ModelProviderConfig> sorted = configs == null ? List.of() : configs.stream()
                .filter(config -> scope.equalsIgnoreCase(config.getConfigScope()))
                .sorted(Comparator.comparing(ModelProviderConfig::getProviderCode))
                .toList();
        String activeProvider = defaults.activeProvider();
        for (ModelProviderConfig config : sorted) {
            ProviderConfigView fallback = providers.get(config.getProviderCode());
            if (fallback == null) {
                continue;
            }
            Integer dimension = config.getDimension() != null ? config.getDimension() : fallback.dimension();
            ProviderConfigView merged = new ProviderConfigView(
                    fallback.provider(),
                    hasValue(config.getDisplayName()) ? config.getDisplayName() : fallback.displayName(),
                    hasValue(config.getApiStyle()) ? config.getApiStyle() : fallback.apiStyle(),
                    normalizeOpenAiCompatibleBaseUrl(hasValue(config.getApiBaseUrl())
                            ? config.getApiBaseUrl() : fallback.apiBaseUrl()),
                    hasValue(config.getModelName()) ? config.getModelName() : fallback.model(),
                    config.isEnabled(),
                    config.isActive(),
                    dimension
            );
            providers.put(merged.provider(), merged);
            if (merged.active() && merged.enabled()) {
                activeProvider = merged.provider();
            }
        }

        List<ProviderConfigView> ordered = new ArrayList<>(providers.values());
        ordered.sort(Comparator.comparing(ProviderConfigView::provider));
        String selected = activeProvider;
        List<ProviderConfigView> normalized = ordered.stream()
                .map(provider -> new ProviderConfigView(
                        provider.provider(),
                        provider.displayName(),
                        provider.apiStyle(),
                        provider.apiBaseUrl(),
                        provider.model(),
                        provider.enabled(),
                        provider.provider().equals(selected),
                        provider.dimension()
                ))
                .toList();
        return new ScopeSettingsView(scope, selected, normalized);
    }

    private Optional<String> resolveProviderApiKey(String scope, String provider) {
        Optional<ModelProviderConfig> persisted = repository.findByConfigScopeAndProviderCode(scope, provider);
        if (persisted.isPresent()) {
            String apiKey = secretCryptoService.decrypt(persisted.get().getApiKeyCiphertext());
            if (hasValue(apiKey)) {
                return Optional.of(apiKey);
            }
        }
        if ("minimax".equalsIgnoreCase(provider) && hasValue(minimaxApiKey)) {
            return Optional.of(minimaxApiKey);
        }
        return Optional.empty();
    }

    public static String normalizeOpenAiCompatibleBaseUrl(String rawBaseUrl) {
        if (rawBaseUrl == null) {
            return null;
        }
        String normalized = rawBaseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith(CHAT_COMPLETIONS_PATH)) {
            normalized = normalized.substring(0, normalized.length() - CHAT_COMPLETIONS_PATH.length());
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private record ScopeSettingsView(
            String scope,
            String activeProvider,
            List<ProviderConfigView> providers
    ) {
    }

    private record ProviderConfigView(
            String provider,
            String displayName,
            String apiStyle,
            String apiBaseUrl,
            String model,
            boolean enabled,
            boolean active,
            Integer dimension
    ) {
    }

    public record ActiveProviderView(
            String provider,
            String displayName,
            String apiStyle,
            String apiBaseUrl,
            String model,
            String apiKey,
            Integer dimension
    ) {
    }
}
