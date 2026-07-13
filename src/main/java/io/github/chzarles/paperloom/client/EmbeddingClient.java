package io.github.chzarles.paperloom.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.config.OutboundWebClientFactory;
import io.github.chzarles.paperloom.service.ModelProviderConfigService;
import io.github.chzarles.paperloom.service.RateLimitService;
import io.github.chzarles.paperloom.service.UsageQuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 嵌入向量生成客户端
@Component
public class EmbeddingClient {

    public enum UsageType {
        UPLOAD,
        QUERY
    }

    @Value("${embedding.api.batch-size:100}")
    private int batchSize;

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingClient.class);
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final UsageQuotaService usageQuotaService;
    private final ModelProviderConfigService modelProviderConfigService;
    private final OutboundWebClientFactory outboundWebClientFactory;

    public EmbeddingClient(ObjectMapper objectMapper,
                           RateLimitService rateLimitService,
                           UsageQuotaService usageQuotaService,
                           ModelProviderConfigService modelProviderConfigService,
                           OutboundWebClientFactory outboundWebClientFactory) {
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
        this.usageQuotaService = usageQuotaService;
        this.modelProviderConfigService = modelProviderConfigService;
        this.outboundWebClientFactory = outboundWebClientFactory;
    }

    @PostConstruct
    public void init() {
        ModelProviderConfigService.ActiveProviderView provider = modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        logger.info("EmbeddingClient 初始化 - Provider: {}, 模型: {}, 批次大小: {}, 维度: {}, API地址: {}",
                provider.provider(), provider.model(), batchSize, provider.dimension(), provider.apiBaseUrl());
    }

    /**
     * 调用通义千问 API 生成向量
     * @param texts 输入文本列表
     * @return 对应的向量列表
     */
    public List<float[]> embed(List<String> texts) {
        return embedWithUsage(texts, "system", UsageType.UPLOAD).vectors();
    }

    public List<float[]> embed(List<String> texts, String requesterId) {
        return embedWithUsage(texts, requesterId, UsageType.UPLOAD).vectors();
    }

    public List<float[]> embed(List<String> texts, String requesterId, UsageType usageType) {
        return embedWithUsage(texts, requesterId, usageType).vectors();
    }

    public List<float[]> embed(List<String> texts, String requesterId, UsageType usageType, Duration timeout) {
        return embedWithUsage(texts, requesterId, usageType, timeout).vectors();
    }

    public EmbeddingUsageResult embedWithUsage(List<String> texts, String requesterId, UsageType usageType) {
        return embedWithUsage(texts, requesterId, usageType, Duration.ofSeconds(30));
    }

    public EmbeddingUsageResult embedWithUsage(List<String> texts, String requesterId, UsageType usageType, Duration timeout) {
        try {
            String normalizedRequesterId = requesterId == null || requesterId.isBlank() ? "unknown" : requesterId;
            logger.info("开始生成向量，文本数量: {}", texts.size());

            List<float[]> all = new ArrayList<>(texts.size());
            int totalTokens = 0;
            for (int start = 0; start < texts.size(); start += batchSize) {
                int end = Math.min(start + batchSize, texts.size());
                List<String> sub = texts.subList(start, end);
                UsageQuotaService.TokenReservationBundle reservation = usageType == UsageType.QUERY
                        ? rateLimitService.reserveEmbeddingQueryUsage(normalizedRequesterId, sub)
                        : rateLimitService.reserveEmbeddingUploadUsage(normalizedRequesterId, sub);
                logger.debug("调用向量 API, 批次: {}-{} (size={})", start, end - 1, sub.size());
                try {
                    ModelProviderConfigService.ActiveProviderView provider = modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
                    String response = callApiOnce(provider, sub, usageType, timeout);
                    EmbeddingApiResponse parsedResponse = parseEmbeddingResponse(provider, response, sub);
                    usageQuotaService.settleReservation(reservation, parsedResponse.totalTokens());
                    all.addAll(parsedResponse.vectors());
                    totalTokens += parsedResponse.totalTokens();
                } catch (Exception e) {
                    usageQuotaService.abortReservation(reservation);
                    throw e;
                }
            }
            logger.info("成功生成向量，总数量: {}", all.size());
            return new EmbeddingUsageResult(all, totalTokens, currentModelVersion());
        } catch (WebClientResponseException e) {
            // 提供详细的API响应错误信息
            logger.error("API调用失败 - 状态码: {}, 响应: {}, 请求头: {}",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString(),
                    e.getHeaders());
            throw new RuntimeException(String.format(
                    "向量生成失败 - API错误: HTTP %d - %s",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString()), e);
        } catch (Exception e) {
            logger.error("调用向量化 API 失败: {} - 类型: {}",
                    e.getMessage(),
                    e.getClass().getSimpleName(), e);
            throw new RuntimeException("向量生成失败: " + e.getMessage(), e);
        }
    }

    private String callApiOnce(List<String> batch) {
        ModelProviderConfigService.ActiveProviderView provider = modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        return callApiOnce(provider, batch, UsageType.UPLOAD, Duration.ofSeconds(30));
    }

    private String callApiOnce(List<String> batch, Duration timeout) {
        ModelProviderConfigService.ActiveProviderView provider = modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        return callApiOnce(provider, batch, UsageType.UPLOAD, timeout);
    }

    private String callApiOnce(ModelProviderConfigService.ActiveProviderView provider,
                               List<String> batch,
                               UsageType usageType,
                               Duration timeout) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", provider.model());
        if (usesMiniMaxNativeEmbeddingApi(provider)) {
            requestBody.put("texts", batch);
            requestBody.put("type", usageType == UsageType.QUERY ? "query" : "db");
        } else {
            requestBody.put("input", batch);
            if (provider.dimension() != null) {
                requestBody.put("dimension", provider.dimension());
            }
            requestBody.put("encoding_format", "float");
        }

        logger.debug("发送嵌入请求 - Provider: {}, 模型: {}, 维度: {}, 批次大小: {}, 文本预览: {}",
                provider.provider(), provider.model(), provider.dimension(), batch.size(),
                batch.isEmpty() ? "空" : batch.get(0).substring(0, Math.min(50, batch.get(0).length())) + "...");

        return buildClient(provider).post()
                .uri("/embeddings")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1))
                        .filter(e -> e instanceof WebClientResponseException)
                        .doBeforeRetry(signal -> logger.warn("重试API调用 - 尝试: {}, 错误: {}",
                                signal.totalRetries() + 1, signal.failure().getMessage())))
                .block(safeTimeout(timeout));
    }

    private Duration safeTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            return Duration.ofSeconds(30);
        }
        return timeout;
    }

    private WebClient buildClient(ModelProviderConfigService.ActiveProviderView provider) {
        WebClient.Builder builder = outboundWebClientFactory
                .builder(ModelProviderConfigService.normalizeOpenAiCompatibleBaseUrl(provider.apiBaseUrl()))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // WebClient 的默认缓冲区大小限制（256KB）, 这里调高到 16MB
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));
        if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
        }
        return builder.build();
    }

    private boolean usesMiniMaxNativeEmbeddingApi(ModelProviderConfigService.ActiveProviderView provider) {
        return provider != null && ModelProviderConfigService.API_STYLE_MINIMAX_EMBEDDING.equals(provider.apiStyle());
    }

    private EmbeddingApiResponse parseEmbeddingResponse(ModelProviderConfigService.ActiveProviderView provider,
                                                        String response,
                                                        List<String> inputTexts) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(response);
        if (usesMiniMaxNativeEmbeddingApi(provider)) {
            return parseMiniMaxEmbeddingResponse(jsonNode, inputTexts);
        }
        JsonNode data = jsonNode.get("data");  // 兼容模式下使用data字段
        if (data == null || !data.isArray()) {
            throw new RuntimeException("API 响应格式错误: data 字段不存在或不是数组");
        }
        
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embedding = item.get("embedding");
            if (embedding != null && embedding.isArray()) {
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vector);
            }
        }

        JsonNode usage = jsonNode.path("usage");
        int totalTokens = usage.path("total_tokens").asInt(usage.path("input_tokens").asInt(0));
        return new EmbeddingApiResponse(vectors, totalTokens > 0 ? totalTokens : usageQuotaService.estimateEmbeddingTokens(inputTexts));
    }

    private EmbeddingApiResponse parseMiniMaxEmbeddingResponse(JsonNode jsonNode, List<String> inputTexts) {
        JsonNode baseResponse = jsonNode.path("base_resp");
        int statusCode = baseResponse.path("status_code").asInt(0);
        if (statusCode != 0) {
            String statusMessage = baseResponse.path("status_msg").asText("MiniMax Embedding 请求失败");
            throw new RuntimeException("MiniMax Embedding API error " + statusCode + ": " + statusMessage);
        }

        JsonNode vectorsNode = jsonNode.path("vectors");
        if (!vectorsNode.isArray()) {
            throw new RuntimeException("MiniMax Embedding 响应格式错误: vectors 字段不存在或不是数组");
        }

        List<float[]> vectors = new ArrayList<>();
        for (JsonNode vectorNode : vectorsNode) {
            if (!vectorNode.isArray()) {
                continue;
            }
            float[] vector = new float[vectorNode.size()];
            for (int i = 0; i < vectorNode.size(); i++) {
                vector[i] = (float) vectorNode.get(i).asDouble();
            }
            vectors.add(vector);
        }

        int totalTokens = jsonNode.path("total_tokens").asInt(0);
        return new EmbeddingApiResponse(vectors, totalTokens > 0 ? totalTokens : usageQuotaService.estimateEmbeddingTokens(inputTexts));
    }

    public String currentModelVersion() {
        ModelProviderConfigService.ActiveProviderView provider = modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        return provider.provider() + ":" + provider.model() + ":" + provider.dimension();
    }

    private record EmbeddingApiResponse(List<float[]> vectors, int totalTokens) {
    }

    public record EmbeddingUsageResult(List<float[]> vectors, int totalTokens, String modelVersion) {
    }
}
