package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.ModelProviderConfig;
import io.github.chzarles.paperloom.repository.ModelProviderConfigRepository;
import io.github.chzarles.paperloom.utils.SecretCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelProviderConfigServiceTest {

    private ModelProviderConfigRepository repository;
    private SecretCryptoService secretCryptoService;

    @BeforeEach
    void setUp() {
        repository = mock(ModelProviderConfigRepository.class);
        secretCryptoService = mock(SecretCryptoService.class);
        when(repository.findAll()).thenReturn(List.of());
        when(repository.findByConfigScopeAndProviderCode(ModelProviderConfigService.SCOPE_LLM, "minimax"))
                .thenReturn(Optional.empty());
        when(repository.findByConfigScopeAndProviderCode(ModelProviderConfigService.SCOPE_EMBEDDING, "minimax"))
                .thenReturn(Optional.empty());
    }

    @Test
    void defaultsToMiniMaxProvider() {
        ModelProviderConfigService service = service();

        ModelProviderConfigService.ActiveProviderView provider =
                service.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);

        assertEquals("minimax", provider.provider());
        assertEquals("MiniMax-M3", provider.model());
        assertEquals("https://api.minimaxi.com/v1", provider.apiBaseUrl());
        assertNull(provider.apiKey());
    }

    @Test
    void loadsPersistedActiveLlmProviderAndCredential() {
        ModelProviderConfig minimax = provider("llm", "minimax", true, true);
        minimax.setDisplayName("MiniMax");
        minimax.setApiBaseUrl("https://api.minimaxi.com/v1/chat/completions");
        minimax.setModelName("MiniMax-M3");
        minimax.setApiKeyCiphertext("ciphertext");
        when(repository.findAll()).thenReturn(List.of(minimax));
        when(repository.findByConfigScopeAndProviderCode(ModelProviderConfigService.SCOPE_LLM, "minimax"))
                .thenReturn(Optional.of(minimax));
        when(secretCryptoService.decrypt("ciphertext")).thenReturn("sk-minimax");

        ModelProviderConfigService service = service();
        service.reloadSettings();
        ModelProviderConfigService.ActiveProviderView provider =
                service.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);

        assertEquals("minimax", provider.provider());
        assertEquals("https://api.minimaxi.com/v1", provider.apiBaseUrl());
        assertEquals("sk-minimax", provider.apiKey());
    }

    @Test
    void resolvesEmbeddingScopeProviderIndependently() {
        ModelProviderConfigService service = service();

        ModelProviderConfigService.ActiveProviderView provider =
                service.getActiveEmbeddingProvider();

        assertEquals("minimax", provider.provider());
        assertEquals("embo-01", provider.model());
        assertEquals("https://api.minimaxi.com/v1", provider.apiBaseUrl());
        assertEquals(1536, provider.dimension());
    }

    @Test
    void embeddingAndLlmScopeAreIndependent() {
        ModelProviderConfigService service = service();

        assertEquals("minimax", service.getActiveProvider(ModelProviderConfigService.SCOPE_LLM).provider());
        assertEquals("minimax", service.getActiveEmbeddingProvider().provider());
        assertEquals("MiniMax-M3", service.getActiveProvider(ModelProviderConfigService.SCOPE_LLM).model());
        assertEquals("embo-01", service.getActiveEmbeddingProvider().model());
    }

    @Test
    void normalizesChatCompletionsEndpointToProviderBaseUrl() {
        assertEquals(
                "https://example.test/v1",
                ModelProviderConfigService.normalizeOpenAiCompatibleBaseUrl(
                        "https://example.test/v1/chat/completions/"
                )
        );
    }

    @Test
    void mergesNonDefaultProviderRowsFromDatabase() {
        ModelProviderConfig openai = provider("llm", "openai", true, true);
        openai.setDisplayName("OpenAI");
        openai.setApiBaseUrl("https://api.openai.com/v1");
        openai.setModelName("gpt-4o");
        openai.setApiKeyCiphertext("cipher-openai");
        when(repository.findAll()).thenReturn(List.of(openai));
        when(repository.findByConfigScopeAndProviderCode(ModelProviderConfigService.SCOPE_LLM, "openai"))
                .thenReturn(Optional.of(openai));
        when(secretCryptoService.decrypt("cipher-openai")).thenReturn("sk-openai");

        ModelProviderConfigService service = service();
        service.reloadSettings();

        ModelProviderConfigService.ActiveProviderView provider =
                service.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);
        assertEquals("openai", provider.provider());
        assertEquals("gpt-4o", provider.model());
        assertEquals("sk-openai", provider.apiKey());
    }

    @Test
    void adminOverviewHidesApiKeyAndReportsPresence() {
        ModelProviderConfigService service = service();

        ModelProviderConfigService.AdminProviderOverview overview =
                service.getAdminOverview(ModelProviderConfigService.SCOPE_LLM);

        assertEquals(ModelProviderConfigService.SCOPE_LLM, overview.scope());
        assertEquals("minimax", overview.activeProvider());
        ModelProviderConfigService.AdminProviderView minimax = overview.providers().get(0);
        assertEquals("minimax", minimax.provider());
        assertFalse(minimax.hasApiKey());
    }

    @Test
    void saveProviderCreatesActivatesAndReloads() {
        List<ModelProviderConfig> rows = new ArrayList<>();
        stubProviderStore(rows);
        when(secretCryptoService.encrypt("sk-openai")).thenReturn("cipher-openai");
        when(secretCryptoService.decrypt("cipher-openai")).thenReturn("sk-openai");

        ModelProviderConfigService service = service();
        ModelProviderConfigService.AdminProviderOverview overview = service.saveProvider(
                "llm",
                "openai",
                new ModelProviderConfigService.ProviderSaveCommand(
                        "OpenAI", null, "https://api.openai.com/v1/", "gpt-4o", "sk-openai", true, true, null),
                "admin");

        assertEquals("openai", overview.activeProvider());
        ModelProviderConfigService.ActiveProviderView provider =
                service.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);
        assertEquals("openai", provider.provider());
        assertEquals("sk-openai", provider.apiKey());
        assertEquals("https://api.openai.com/v1", provider.apiBaseUrl());
    }

    @Test
    void saveProviderRejectsActiveProviderWithoutApiKey() {
        stubProviderStore(new ArrayList<>());

        ModelProviderConfigService service = service();
        CustomException error = assertThrows(CustomException.class, () -> service.saveProvider(
                "llm",
                "openai",
                new ModelProviderConfigService.ProviderSaveCommand(
                        null, null, "https://api.openai.com/v1", "gpt-4o", " ", true, true, null),
                "admin"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    @Test
    void saveProviderRequiresEmbeddingDimension() {
        stubProviderStore(new ArrayList<>());

        ModelProviderConfigService service = service();
        CustomException error = assertThrows(CustomException.class, () -> service.saveProvider(
                "embedding",
                "minimax",
                new ModelProviderConfigService.ProviderSaveCommand(
                        null, null, "https://api.minimaxi.com/v1", "embo-01", "sk", true, true, null),
                "admin"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    @Test
    void loadPersistedConfigsSeedsDefaultRowsWhenScopeEmpty() {
        when(repository.findByConfigScopeOrderByProviderCodeAsc(anyString())).thenReturn(List.of());
        when(secretCryptoService.encrypt("sk-minimax")).thenReturn("cipher");

        ModelProviderConfigService service =
                new ModelProviderConfigService(repository, secretCryptoService, "sk-minimax");
        service.loadPersistedConfigs();

        verify(repository, times(2)).save(any(ModelProviderConfig.class));
        verify(secretCryptoService, times(2)).encrypt("sk-minimax");
    }

    @Test
    void loadPersistedConfigsDoesNotSeedWhenRowsExist() {
        when(repository.findByConfigScopeOrderByProviderCodeAsc(ModelProviderConfigService.SCOPE_LLM))
                .thenReturn(List.of(provider("llm", "minimax", true, true)));
        when(repository.findByConfigScopeOrderByProviderCodeAsc(ModelProviderConfigService.SCOPE_EMBEDDING))
                .thenReturn(List.of(provider("embedding", "minimax", true, true)));

        ModelProviderConfigService service = service();
        service.loadPersistedConfigs();

        verify(repository, never()).save(any(ModelProviderConfig.class));
    }

    private void stubProviderStore(List<ModelProviderConfig> rows) {
        when(repository.findAll()).thenAnswer(invocation -> List.copyOf(rows));
        when(repository.findByConfigScopeOrderByProviderCodeAsc(anyString()))
                .thenAnswer(invocation -> rows.stream()
                        .filter(row -> row.getConfigScope().equals(invocation.getArgument(0)))
                        .toList());
        when(repository.findByConfigScopeAndProviderCode(anyString(), anyString()))
                .thenAnswer(invocation -> rows.stream()
                        .filter(row -> row.getConfigScope().equals(invocation.getArgument(0))
                                && row.getProviderCode().equals(invocation.getArgument(1)))
                        .findFirst());
        when(repository.save(any(ModelProviderConfig.class))).thenAnswer(invocation -> {
            ModelProviderConfig saved = invocation.getArgument(0);
            rows.removeIf(row -> row.getConfigScope().equals(saved.getConfigScope())
                    && row.getProviderCode().equals(saved.getProviderCode()));
            rows.add(saved);
            return saved;
        });
    }

    private ModelProviderConfigService service() {
        return new ModelProviderConfigService(repository, secretCryptoService, "");
    }

    private ModelProviderConfig provider(String scope, String code, boolean enabled, boolean active) {
        ModelProviderConfig config = new ModelProviderConfig();
        config.setConfigScope(scope);
        config.setProviderCode(code);
        config.setDisplayName(code);
        config.setApiStyle(ModelProviderConfigService.API_STYLE_OPENAI);
        config.setApiBaseUrl("https://example.test/v1");
        config.setModelName("model");
        config.setEnabled(enabled);
        config.setActive(active);
        config.setUpdatedBy("test");
        return config;
    }
}
