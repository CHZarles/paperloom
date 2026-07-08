package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.ModelProviderConfig;
import com.yizhaoqi.smartpai.repository.ModelProviderConfigRepository;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.utils.SecretCryptoService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class ModelProviderConfigServiceTest {

    private final Map<String, ModelProviderConfig> store = new LinkedHashMap<>();

    private ModelProviderConfigRepository repository;
    private PaperRepository paperRepository;
    private ModelProviderConfigService service;

    @BeforeEach
    void setUp() {
        store.clear();
        repository = Mockito.mock(ModelProviderConfigRepository.class);
        paperRepository = Mockito.mock(PaperRepository.class);

        when(repository.findAll()).thenAnswer(invocation -> new ArrayList<>(store.values()));
        when(repository.findByConfigScopeOrderByProviderCodeAsc(any())).thenAnswer(invocation -> {
            String scope = invocation.getArgument(0, String.class);
            return store.values().stream()
                    .filter(item -> scope.equals(item.getConfigScope()))
                    .sorted((left, right) -> left.getProviderCode().compareTo(right.getProviderCode()))
                    .toList();
        });
        when(repository.findByConfigScopeAndProviderCode(any(), any())).thenAnswer(invocation -> {
            String scope = invocation.getArgument(0, String.class);
            String provider = invocation.getArgument(1, String.class);
            return Optional.ofNullable(store.get(scope + ":" + provider));
        });
        when(repository.findByConfigScopeAndActiveTrue(any())).thenAnswer(invocation -> {
            String scope = invocation.getArgument(0, String.class);
            return store.values().stream()
                    .filter(item -> scope.equals(item.getConfigScope()) && item.isActive())
                    .findFirst();
        });
        when(repository.save(any(ModelProviderConfig.class))).thenAnswer(invocation -> {
            ModelProviderConfig entity = invocation.getArgument(0, ModelProviderConfig.class);
            store.put(entity.getConfigScope() + ":" + entity.getProviderCode(), entity);
            return entity;
        });
        when(paperRepository.count()).thenReturn(1L);

        SecretCryptoService secretCryptoService = new SecretCryptoService();
        ReflectionTestUtils.setField(secretCryptoService, "base64Secret", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        secretCryptoService.init();

        service = new ModelProviderConfigService(repository, secretCryptoService, null, paperRepository);
        ReflectionTestUtils.setField(service, "deepSeekApiUrl", "https://api.deepseek.com/v1");
        ReflectionTestUtils.setField(service, "deepSeekApiKey", "sk-default-deepseek");
        ReflectionTestUtils.setField(service, "deepSeekModel", "deepseek-chat");
        ReflectionTestUtils.setField(service, "embeddingApiUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        ReflectionTestUtils.setField(service, "embeddingApiKey", "sk-default-embedding");
        ReflectionTestUtils.setField(service, "embeddingModel", "text-embedding-v4");
        ReflectionTestUtils.setField(service, "embeddingDimension", 2048);
        service.reloadSettings();
    }

    @Test
    void shouldExposeDefaultProviders() {
        ModelProviderConfigService.ModelProviderSettingsView settings = service.getCurrentSettings();

        assertEquals("deepseek", settings.llm().activeProvider());
        assertEquals("aliyun", settings.embedding().activeProvider());
        assertEquals(4, settings.llm().providers().size());
        assertEquals(3, settings.embedding().providers().size());
        assertTrue(settings.llm().providers().stream().anyMatch(item -> item.provider().equals("minimax")));
        assertTrue(settings.llm().providers().stream().anyMatch(item -> item.provider().equals("qwen")));
        assertTrue(settings.embedding().providers().stream().anyMatch(item -> item.provider().equals("minimax")));
        assertTrue(settings.embedding().providers().stream().anyMatch(item -> item.provider().equals("zhipu")));

        ModelProviderConfigService.ProviderConfigView minimax = settings.llm().providers().stream()
                .filter(item -> item.provider().equals("minimax"))
                .findFirst()
                .orElseThrow();
        assertEquals("https://api.minimaxi.com/v1", minimax.apiBaseUrl());
        assertEquals("MiniMax-M3", minimax.model());

        ModelProviderConfigService.ProviderConfigView minimaxEmbedding = settings.embedding().providers().stream()
                .filter(item -> item.provider().equals("minimax"))
                .findFirst()
                .orElseThrow();
        assertEquals("https://api.minimaxi.com/v1", minimaxEmbedding.apiBaseUrl());
        assertEquals("embo-01", minimaxEmbedding.model());
        assertEquals(1536, minimaxEmbedding.dimension());
        assertEquals(ModelProviderConfigService.API_STYLE_MINIMAX_EMBEDDING, minimaxEmbedding.apiStyle());
    }

    @Test
    void shouldUpdateActiveLlmProviderAndPersistEncryptedApiKey() {
        ModelProviderConfigService.UpdateScopeRequest request = new ModelProviderConfigService.UpdateScopeRequest(
                "qwen",
                List.of(
                        new ModelProviderConfigService.ProviderUpsertRequest("deepseek", "https://api.deepseek.com/v1", "deepseek-chat", "", null, true),
                        new ModelProviderConfigService.ProviderUpsertRequest("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash", "sk-qwen-updated", null, true),
                        new ModelProviderConfigService.ProviderUpsertRequest("zhipu", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-air", "", null, true)
                )
        );

        ModelProviderConfigService.ScopeSettingsView updated = service.updateScope(ModelProviderConfigService.SCOPE_LLM, request, "admin");
        ModelProviderConfigService.ActiveProviderView activeProvider = service.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);

        assertEquals("qwen", updated.activeProvider());
        assertEquals("qwen", activeProvider.provider());
        assertEquals("qwen-flash", activeProvider.model());
        assertEquals("sk-qwen-updated", activeProvider.apiKey());

        ModelProviderConfig persisted = store.get("llm:qwen");
        assertNotNull(persisted.getApiKeyCiphertext());
        assertFalse(persisted.getApiKeyCiphertext().contains("sk-qwen-updated"));
    }

    @Test
    void shouldRejectUnsafeEmbeddingProviderSwitch() {
        ModelProviderConfigService.UpdateScopeRequest request = new ModelProviderConfigService.UpdateScopeRequest(
                "zhipu",
                List.of(
                        new ModelProviderConfigService.ProviderUpsertRequest("aliyun", "https://dashscope.aliyuncs.com/compatible-mode/v1", "text-embedding-v4", "", 2048, true),
                        new ModelProviderConfigService.ProviderUpsertRequest("minimax", "https://api.minimaxi.com/v1", "embo-01", "", 1536, true),
                        new ModelProviderConfigService.ProviderUpsertRequest("zhipu", "https://open.bigmodel.cn/api/paas/v4", "embedding-3", "", 2048, true)
                )
        );

        CustomException exception = assertThrows(CustomException.class,
                () -> service.updateScope(ModelProviderConfigService.SCOPE_EMBEDDING, request, "admin"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertTrue(exception.getMessage().contains("重嵌入"));
    }

    @Test
    void shouldAllowEmbeddingProviderSwitchAfterProductRuntimeReset() {
        when(paperRepository.count()).thenReturn(0L);
        ModelProviderConfigService.UpdateScopeRequest request = new ModelProviderConfigService.UpdateScopeRequest(
                "minimax",
                List.of(
                        new ModelProviderConfigService.ProviderUpsertRequest("aliyun", "https://dashscope.aliyuncs.com/compatible-mode/v1", "text-embedding-v4", "", 2048, true),
                        new ModelProviderConfigService.ProviderUpsertRequest("minimax", "https://api.minimaxi.com/v1", "embo-01", "", 1536, true),
                        new ModelProviderConfigService.ProviderUpsertRequest("zhipu", "https://open.bigmodel.cn/api/paas/v4", "embedding-3", "", 2048, true)
                )
        );

        ModelProviderConfigService.ScopeSettingsView updated = service.updateScope(ModelProviderConfigService.SCOPE_EMBEDDING, request, "admin");

        assertEquals("minimax", updated.activeProvider());
        assertEquals("minimax", service.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING).provider());
    }

    @Test
    void shouldReuseStoredMiniMaxLlmApiKeyWhenTestingEmbeddingConnection() throws Exception {
        ModelProviderConfigService.UpdateScopeRequest llmRequest = new ModelProviderConfigService.UpdateScopeRequest(
                "minimax",
                List.of(
                        new ModelProviderConfigService.ProviderUpsertRequest("deepseek", "https://api.deepseek.com/v1", "deepseek-chat", "", null, true),
                        new ModelProviderConfigService.ProviderUpsertRequest("minimax", "https://api.minimaxi.com/v1", "MiniMax-M3", "sk-minimax", null, true),
                        new ModelProviderConfigService.ProviderUpsertRequest("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash", "", null, true),
                        new ModelProviderConfigService.ProviderUpsertRequest("zhipu", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-air", "", null, true)
                )
        );
        service.updateScope(ModelProviderConfigService.SCOPE_LLM, llmRequest, "admin");

        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] response = """
                    {"vectors":[[0.1,0.2]],"model":"embo-01","total_tokens":8,"base_resp":{"status_code":0,"status_msg":"success"}}
                    """.getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            ModelProviderConfigService.ConnectivityTestView result = service.testConnection(
                    ModelProviderConfigService.SCOPE_EMBEDDING,
                    new ModelProviderConfigService.ProviderConnectionTestRequest(
                            "minimax",
                            "http://127.0.0.1:" + port + "/v1",
                            "embo-01",
                            "",
                            1536
                    )
            );

            assertTrue(result.success());
            assertEquals("Bearer sk-minimax", authorizationHeader.get());
            assertEquals("/v1/embeddings", requestPath.get());
            assertTrue(requestBody.get().contains("\"texts\""));
            assertTrue(requestBody.get().contains("\"type\":\"db\""));
            assertFalse(requestBody.get().contains("\"input\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReuseStoredApiKeyWhenTestingConnectionWithBlankInput() throws Exception {
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestPath.set(exchange.getRequestURI().getPath());
            byte[] response = "{\"ok\":true}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            ModelProviderConfigService.ConnectivityTestView result = service.testConnection(
                    ModelProviderConfigService.SCOPE_LLM,
                    new ModelProviderConfigService.ProviderConnectionTestRequest(
                            "deepseek",
                            "http://127.0.0.1:" + port + "/v1/chat/completions",
                            "deepseek-chat",
                            "",
                            null
                    )
            );

            assertTrue(result.success());
            assertEquals("Bearer sk-default-deepseek", authorizationHeader.get());
            assertEquals("/v1/chat/completions", requestPath.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldNormalizeOpenAiCompatibleEndpointSuffixWhenSavingConfig() {
        ModelProviderConfigService.UpdateScopeRequest request = new ModelProviderConfigService.UpdateScopeRequest(
                "zhipu",
                List.of(
                        new ModelProviderConfigService.ProviderUpsertRequest("deepseek", "https://api.deepseek.com/v1", "deepseek-chat", "", null, true),
                        new ModelProviderConfigService.ProviderUpsertRequest("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash", "", null, true),
                        new ModelProviderConfigService.ProviderUpsertRequest("zhipu", "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions", "glm-5.1", "sk-zhipu", null, true)
                )
        );

        ModelProviderConfigService.ScopeSettingsView updated = service.updateScope(ModelProviderConfigService.SCOPE_LLM, request, "admin");
        ModelProviderConfigService.ProviderConfigView zhipu = updated.providers().stream()
                .filter(item -> item.provider().equals("zhipu"))
                .findFirst()
                .orElseThrow();
        ModelProviderConfigService.ActiveProviderView activeProvider = service.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);

        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", zhipu.apiBaseUrl());
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", activeProvider.apiBaseUrl());
    }

    @Test
    void shouldNormalizePersistedEndpointSuffixWhenLoadingSettings() {
        ModelProviderConfig zhipu = new ModelProviderConfig();
        zhipu.setConfigScope("llm");
        zhipu.setProviderCode("zhipu");
        zhipu.setDisplayName("ZhipuAI");
        zhipu.setApiStyle(ModelProviderConfigService.API_STYLE_OPENAI);
        zhipu.setApiBaseUrl("https://open.bigmodel.cn/api/coding/paas/v4/chat/completions");
        zhipu.setModelName("glm-5.1");
        zhipu.setEnabled(true);
        zhipu.setActive(true);
        repository.save(zhipu);

        service.reloadSettings();

        ModelProviderConfigService.ProviderConfigView loaded = service.getCurrentSettings().llm().providers().stream()
                .filter(item -> item.provider().equals("zhipu"))
                .findFirst()
                .orElseThrow();

        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", loaded.apiBaseUrl());
    }
}
