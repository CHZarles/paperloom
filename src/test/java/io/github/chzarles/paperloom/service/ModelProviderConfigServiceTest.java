package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.ModelProviderConfig;
import io.github.chzarles.paperloom.repository.ModelProviderConfigRepository;
import io.github.chzarles.paperloom.utils.SecretCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelProviderConfigServiceTest {

    private ModelProviderConfigRepository repository;
    private SecretCryptoService secretCryptoService;

    @BeforeEach
    void setUp() {
        repository = mock(ModelProviderConfigRepository.class);
        secretCryptoService = mock(SecretCryptoService.class);
        when(repository.findAll()).thenReturn(List.of());
        when(repository.findByConfigScopeAndProviderCode(ModelProviderConfigService.SCOPE_LLM, "deepseek"))
                .thenReturn(Optional.empty());
    }

    @Test
    void defaultsToDeploymentManagedDeepSeekProvider() {
        ModelProviderConfigService service = service();

        ModelProviderConfigService.ActiveProviderView provider =
                service.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);

        assertEquals("deepseek", provider.provider());
        assertEquals("deepseek-chat", provider.model());
        assertEquals("https://api.deepseek.com/v1", provider.apiBaseUrl());
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
    void ignoresDeletedEmbeddingScopeRows() {
        ModelProviderConfig embedding = provider("embedding", "minimax", true, true);
        when(repository.findAll()).thenReturn(List.of(embedding));

        ModelProviderConfigService service = service();
        service.reloadSettings();

        assertEquals("deepseek", service.getActiveProvider(ModelProviderConfigService.SCOPE_LLM).provider());
        assertThrows(CustomException.class, () -> service.getActiveProvider("embedding"));
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

    private ModelProviderConfigService service() {
        return new ModelProviderConfigService(repository, secretCryptoService);
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
