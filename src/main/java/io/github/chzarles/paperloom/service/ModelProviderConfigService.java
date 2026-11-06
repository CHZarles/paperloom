package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.ModelProviderConfig;
import io.github.chzarles.paperloom.repository.ModelProviderConfigRepository;
import io.github.chzarles.paperloom.utils.SecretCryptoService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        seedDefaultsIfAbsent();
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

    /**
     * 管理页总览：默认 provider 加数据库覆盖后的合并视图，不返回明文 API Key。
     */
    public synchronized AdminProviderOverview getAdminOverview(String scope) {
        String normalizedScope = normalizeScope(scope);
        ScopeSettingsView settings = settingsFor(normalizedScope);
        List<AdminProviderView> providers = settings.providers().stream()
                .map(provider -> new AdminProviderView(
                        provider.provider(),
                        provider.displayName(),
                        provider.apiStyle(),
                        provider.apiBaseUrl(),
                        provider.model(),
                        provider.enabled(),
                        provider.active(),
                        provider.dimension(),
                        resolveProviderApiKey(normalizedScope, provider.provider()).isPresent()
                ))
                .toList();
        return new AdminProviderOverview(normalizedScope, settings.activeProvider(), providers);
    }

    /**
     * 保存一个 provider 配置行；active 唯一性在同一 scope 内维护，保存后立即热加载。
     */
    @Transactional
    public synchronized AdminProviderOverview saveProvider(String scope,
                                                           String providerCode,
                                                           ProviderSaveCommand command,
                                                           String updatedBy) {
        String normalizedScope = normalizeScope(scope);
        String code = normalizeProviderCode(providerCode);
        validateSaveCommand(normalizedScope, command);

        ModelProviderConfig config = repository
                .findByConfigScopeAndProviderCode(normalizedScope, code)
                .orElseGet(() -> new ModelProviderConfig());
        boolean configIsNew = config.getId() == null;
        if (configIsNew) {
            config.setConfigScope(normalizedScope);
            config.setProviderCode(code);
            config.setDisplayName(code);
        }
        if (hasValue(command.displayName())) {
            config.setDisplayName(command.displayName().trim());
        }
        config.setApiStyle(API_STYLE_OPENAI);
        config.setApiBaseUrl(normalizeOpenAiCompatibleBaseUrl(command.apiBaseUrl().trim()));
        config.setModelName(command.modelName().trim());
        if (hasValue(command.apiKey())) {
            config.setApiKeyCiphertext(secretCryptoService.encrypt(command.apiKey().trim()));
        }
        config.setEnabled(command.enabled());
        config.setActive(command.active());
        config.setDimension(SCOPE_EMBEDDING.equals(normalizedScope) ? command.dimension() : null);
        config.setUpdatedBy(hasValue(updatedBy) ? updatedBy.trim() : "admin");

        boolean keyAvailableAfterSave = hasValue(command.apiKey())
                || (config.getId() != null && hasValue(config.getApiKeyCiphertext()))
                || resolveProviderApiKey(normalizedScope, code).isPresent();
        if (command.active() && !keyAvailableAfterSave) {
            throw new CustomException("激活的 provider 必须已配置 API Key", HttpStatus.BAD_REQUEST);
        }

        if (command.active()) {
            for (ModelProviderConfig existing : repository.findByConfigScopeOrderByProviderCodeAsc(normalizedScope)) {
                if (!existing.getId().equals(config.getId()) && existing.isActive()) {
                    existing.setActive(false);
                    repository.save(existing);
                }
            }
        }
        repository.save(config);

        reloadSettings();
        return getAdminOverview(normalizedScope);
    }

    private void seedDefaultsIfAbsent() {
        seedScopeIfAbsent(SCOPE_LLM, defaultSettings());
        seedScopeIfAbsent(SCOPE_EMBEDDING, defaultEmbeddingSettings());
    }

    private void seedScopeIfAbsent(String scope, ScopeSettingsView defaults) {
        if (!repository.findByConfigScopeOrderByProviderCodeAsc(scope).isEmpty()) {
            return;
        }
        for (ProviderConfigView provider : defaults.providers()) {
            ModelProviderConfig config = new ModelProviderConfig();
            config.setConfigScope(scope);
            config.setProviderCode(provider.provider());
            config.setDisplayName(provider.displayName());
            config.setApiStyle(provider.apiStyle());
            config.setApiBaseUrl(provider.apiBaseUrl());
            config.setModelName(provider.model());
            config.setEnabled(provider.enabled());
            config.setActive(provider.active());
            config.setDimension(provider.dimension());
            if (hasValue(minimaxApiKey)) {
                config.setApiKeyCiphertext(secretCryptoService.encrypt(minimaxApiKey));
            }
            config.setUpdatedBy("system-seed");
            repository.save(config);
        }
    }

    private String normalizeScope(String scope) {
        String normalized = scope == null ? SCOPE_LLM : scope.trim().toLowerCase(Locale.ROOT);
        if (!SCOPE_LLM.equals(normalized) && !SCOPE_EMBEDDING.equals(normalized)) {
            throw new CustomException("scope 必须是 llm 或 embedding", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private String normalizeProviderCode(String providerCode) {
        if (!hasValue(providerCode)) {
            throw new CustomException("provider_code 不能为空", HttpStatus.BAD_REQUEST);
        }
        String code = providerCode.trim().toLowerCase(Locale.ROOT);
        if (!code.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new CustomException("provider_code 只能包含小写字母、数字和中划线", HttpStatus.BAD_REQUEST);
        }
        return code;
    }

    private void validateSaveCommand(String scope, ProviderSaveCommand command) {
        if (command == null) {
            throw new CustomException("请求体不能为空", HttpStatus.BAD_REQUEST);
        }
        if (hasValue(command.apiStyle()) && !API_STYLE_OPENAI.equals(command.apiStyle().trim())) {
            throw new CustomException("当前只支持 " + API_STYLE_OPENAI + " 接口风格", HttpStatus.BAD_REQUEST);
        }
        if (!hasValue(command.apiBaseUrl())
                || !(command.apiBaseUrl().trim().startsWith("http://")
                     || command.apiBaseUrl().trim().startsWith("https://"))) {
            throw new CustomException("api_base_url 必须是 http(s) 地址", HttpStatus.BAD_REQUEST);
        }
        if (!hasValue(command.modelName())) {
            throw new CustomException("model_name 不能为空", HttpStatus.BAD_REQUEST);
        }
        if (command.active() && !command.enabled()) {
            throw new CustomException("激活的 provider 必须同时启用", HttpStatus.BAD_REQUEST);
        }
        if (SCOPE_EMBEDDING.equals(scope) && (command.dimension() == null || command.dimension() <= 0)) {
            throw new CustomException("embedding provider 必须配置正数 dimension", HttpStatus.BAD_REQUEST);
        }
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
                // 管理页可以新增默认列表之外的 provider；缺 URL 或模型的行无法生效，跳过。
                if (!hasValue(config.getApiBaseUrl()) || !hasValue(config.getModelName())) {
                    continue;
                }
                providers.put(config.getProviderCode(), new ProviderConfigView(
                        config.getProviderCode(),
                        hasValue(config.getDisplayName()) ? config.getDisplayName() : config.getProviderCode(),
                        hasValue(config.getApiStyle()) ? config.getApiStyle() : API_STYLE_OPENAI,
                        normalizeOpenAiCompatibleBaseUrl(config.getApiBaseUrl()),
                        config.getModelName(),
                        config.isEnabled(),
                        config.isActive(),
                        config.getDimension()
                ));
                ProviderConfigView added = providers.get(config.getProviderCode());
                if (added.active() && added.enabled()) {
                    activeProvider = added.provider();
                }
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

    public record AdminProviderView(
            String provider,
            String displayName,
            String apiStyle,
            String apiBaseUrl,
            String model,
            boolean enabled,
            boolean active,
            Integer dimension,
            boolean hasApiKey
    ) {
    }

    public record AdminProviderOverview(
            String scope,
            String activeProvider,
            List<AdminProviderView> providers
    ) {
    }

    public record ProviderSaveCommand(
            String displayName,
            String apiStyle,
            String apiBaseUrl,
            String modelName,
            String apiKey,
            boolean enabled,
            boolean active,
            Integer dimension
    ) {
    }
}
