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
    public static final String API_STYLE_OPENAI = "openai-compatible";
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final ModelProviderConfigRepository repository;
    private final SecretCryptoService secretCryptoService;
    private volatile ScopeSettingsView currentSettings;

    @Value("${deepseek.api.url:https://api.deepseek.com/v1}")
    private String deepSeekApiUrl;

    @Value("${deepseek.api.key:}")
    private String deepSeekApiKey;

    @Value("${deepseek.api.model:deepseek-chat}")
    private String deepSeekModel;

    public ModelProviderConfigService(ModelProviderConfigRepository repository,
                                      SecretCryptoService secretCryptoService) {
        this.repository = repository;
        this.secretCryptoService = secretCryptoService;
        this.currentSettings = defaultSettings();
    }

    @PostConstruct
    public void loadPersistedConfigs() {
        reloadSettings();
    }

    public ActiveProviderView getActiveProvider(String scope) {
        requireLlmScope(scope);
        ProviderConfigView provider = currentSettings.providers().stream()
                .filter(ProviderConfigView::active)
                .filter(ProviderConfigView::enabled)
                .findFirst()
                .orElseThrow(() -> new CustomException(
                        "No active LLM provider is configured",
                        HttpStatus.INTERNAL_SERVER_ERROR
                ));
        return new ActiveProviderView(
                provider.provider(),
                provider.displayName(),
                provider.apiStyle(),
                provider.apiBaseUrl(),
                provider.model(),
                resolveProviderApiKey(provider.provider()).orElse(null),
                null
        );
    }

    public synchronized void reloadSettings() {
        currentSettings = mergeOverrides(defaultSettings(), repository.findAll());
    }

    private ScopeSettingsView defaultSettings() {
        String apiUrl = hasValue(deepSeekApiUrl) ? deepSeekApiUrl : "https://api.deepseek.com/v1";
        String model = hasValue(deepSeekModel) ? deepSeekModel : "deepseek-chat";
        return new ScopeSettingsView(
                SCOPE_LLM,
                "deepseek",
                List.of(
                        new ProviderConfigView("deepseek", "DeepSeek", API_STYLE_OPENAI,
                                normalizeOpenAiCompatibleBaseUrl(apiUrl), model, true, true),
                        new ProviderConfigView("minimax", "MiniMax", API_STYLE_OPENAI,
                                "https://api.minimaxi.com/v1", "MiniMax-M3", true, false),
                        new ProviderConfigView("qwen", "Qwen", API_STYLE_OPENAI,
                                "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash", true, false),
                        new ProviderConfigView("zhipu", "ZhipuAI", API_STYLE_OPENAI,
                                "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-air", true, false)
                )
        );
    }

    private ScopeSettingsView mergeOverrides(ScopeSettingsView defaults, List<ModelProviderConfig> configs) {
        Map<String, ProviderConfigView> providers = new LinkedHashMap<>();
        for (ProviderConfigView provider : defaults.providers()) {
            providers.put(provider.provider(), provider);
        }

        List<ModelProviderConfig> sorted = configs == null ? List.of() : configs.stream()
                .filter(config -> SCOPE_LLM.equalsIgnoreCase(config.getConfigScope()))
                .sorted(Comparator.comparing(ModelProviderConfig::getProviderCode))
                .toList();
        String activeProvider = defaults.activeProvider();
        for (ModelProviderConfig config : sorted) {
            ProviderConfigView fallback = providers.get(config.getProviderCode());
            if (fallback == null) {
                continue;
            }
            ProviderConfigView merged = new ProviderConfigView(
                    fallback.provider(),
                    hasValue(config.getDisplayName()) ? config.getDisplayName() : fallback.displayName(),
                    hasValue(config.getApiStyle()) ? config.getApiStyle() : fallback.apiStyle(),
                    normalizeOpenAiCompatibleBaseUrl(hasValue(config.getApiBaseUrl())
                            ? config.getApiBaseUrl() : fallback.apiBaseUrl()),
                    hasValue(config.getModelName()) ? config.getModelName() : fallback.model(),
                    config.isEnabled(),
                    config.isActive()
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
                        provider.provider().equals(selected)
                ))
                .toList();
        return new ScopeSettingsView(SCOPE_LLM, selected, normalized);
    }

    private Optional<String> resolveProviderApiKey(String provider) {
        Optional<ModelProviderConfig> persisted = repository.findByConfigScopeAndProviderCode(SCOPE_LLM, provider);
        if (persisted.isPresent()) {
            String apiKey = secretCryptoService.decrypt(persisted.get().getApiKeyCiphertext());
            if (hasValue(apiKey)) {
                return Optional.of(apiKey);
            }
        }
        if ("deepseek".equals(provider) && hasValue(deepSeekApiKey)) {
            return Optional.of(deepSeekApiKey);
        }
        return Optional.empty();
    }

    private void requireLlmScope(String scope) {
        String normalized = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        if (!SCOPE_LLM.equals(normalized)) {
            throw new CustomException("Unsupported model scope: " + scope, HttpStatus.BAD_REQUEST);
        }
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
            boolean active
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
