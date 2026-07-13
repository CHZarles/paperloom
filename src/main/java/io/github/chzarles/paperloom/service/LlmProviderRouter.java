package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.config.AiProperties;
import io.github.chzarles.paperloom.config.OutboundWebClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Service
public class LlmProviderRouter {

    private static final Logger logger = LoggerFactory.getLogger(LlmProviderRouter.class);
    private static final int REACT_PROVIDER_MAX_ATTEMPTS = 3;
    private static final long REACT_PROVIDER_RETRY_BACKOFF_MILLIS = 200L;

    private final AiProperties aiProperties;
    private final RateLimitService rateLimitService;
    private final UsageQuotaService usageQuotaService;
    private final ModelProviderConfigService modelProviderConfigService;
    private final ObjectMapper objectMapper;
    private final OutboundWebClientFactory outboundWebClientFactory;

    public LlmProviderRouter(AiProperties aiProperties,
                             RateLimitService rateLimitService,
                             UsageQuotaService usageQuotaService,
                             ModelProviderConfigService modelProviderConfigService,
                             ObjectMapper objectMapper) {
        this(aiProperties, rateLimitService, usageQuotaService, modelProviderConfigService, objectMapper,
                new OutboundWebClientFactory());
    }

    @Autowired
    public LlmProviderRouter(AiProperties aiProperties,
                             RateLimitService rateLimitService,
                             UsageQuotaService usageQuotaService,
                             ModelProviderConfigService modelProviderConfigService,
                             ObjectMapper objectMapper,
                             OutboundWebClientFactory outboundWebClientFactory) {
        this.aiProperties = aiProperties;
        this.rateLimitService = rateLimitService;
        this.usageQuotaService = usageQuotaService;
        this.modelProviderConfigService = modelProviderConfigService;
        this.objectMapper = objectMapper;
        this.outboundWebClientFactory = outboundWebClientFactory;
    }

    public ReActTurn completeReActTurn(String requesterId,
                                       List<Map<String, Object>> messages,
                                       List<ToolDefinition> tools,
                                       int maxCompletionTokens) {
        ModelProviderConfigService.ActiveProviderView provider =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);
        Map<String, Object> request = buildReActRequest(provider.model(), messages, tools, maxCompletionTokens, false);

        int estimatedPromptTokens = estimateObjectMessagesTokens(messages)
                + (tools == null || tools.isEmpty() ? 0 : estimateToolsTokens(tools));
        UsageQuotaService.TokenReservationBundle reservation = rateLimitService.reserveLlmUsage(
                requesterId, estimatedPromptTokens, maxCompletionTokens);

        for (int attempt = 1; attempt <= REACT_PROVIDER_MAX_ATTEMPTS; attempt++) {
            try {
                String responseBody = buildClient(provider)
                        .post()
                        .uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(90));
                ReActTurn turn = parseReActTurn(responseBody, estimatedPromptTokens);
                usageQuotaService.settleReservation(reservation, turn.promptTokens() + turn.completionTokens());
                logger.info("ReAct 回合完成: provider={}, model={}, finishReason={}, toolCalls={}, contentChars={}, attempt={}",
                        provider.provider(), provider.model(), turn.finishReason(), turn.toolCalls().size(), turn.content().length(), attempt);
                return turn;
            } catch (Exception exception) {
                if (attempt < REACT_PROVIDER_MAX_ATTEMPTS && isRetryableReactProviderException(exception)) {
                    logger.warn("ReAct 模型回合遇到临时错误，将重试: provider={}, model={}, attempt={}/{}, error={}",
                            provider.provider(), provider.model(), attempt, REACT_PROVIDER_MAX_ATTEMPTS, shortExceptionMessage(exception));
                    if (!sleepBeforeReactProviderRetry(attempt)) {
                        usageQuotaService.abortReservation(reservation);
                        throw new RuntimeException("Interrupted before retrying ReAct provider request", exception);
                    }
                    continue;
                }
                usageQuotaService.abortReservation(reservation);
                String failure = reactProviderFailureMessage(provider, request, exception);
                logger.warn(failure);
                logger.debug(failure, exception);
                throw new RuntimeException(failure, exception);
            }
        }
        usageQuotaService.abortReservation(reservation);
        throw new RuntimeException("ReAct 模型回合调用失败: retry loop exited unexpectedly");
    }

    private WebClient buildClient(ModelProviderConfigService.ActiveProviderView provider) {
        WebClient.Builder builder = outboundWebClientFactory
                .builder(ModelProviderConfigService.normalizeOpenAiCompatibleBaseUrl(provider.apiBaseUrl()));
        if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
        }
        return builder.build();
    }

    private String reactProviderFailureMessage(ModelProviderConfigService.ActiveProviderView provider,
                                               Map<String, Object> request,
                                               Throwable error) {
        WebClientResponseException responseException = findResponseException(error);
        StringBuilder builder = new StringBuilder("ReAct 模型回合调用失败");
        if (provider != null) {
            builder.append(": provider=").append(provider.provider())
                    .append(", model=").append(provider.model());
        }
        builder.append(", ").append(reactRequestDiagnostics(request));
        if (responseException != null) {
            builder.append(", HTTP ").append(responseException.getStatusCode().value())
                    .append(", responseBody=").append(limitText(responseException.getResponseBodyAsString(), 2000));
        } else if (error != null && error.getMessage() != null) {
            builder.append(", error=").append(limitText(error.getMessage(), 500));
        }
        return builder.toString();
    }

    private WebClientResponseException findResponseException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof WebClientResponseException responseException) {
                return responseException;
            }
            current = current.getCause();
        }
        return null;
    }

    private boolean isRetryableReactProviderException(Throwable error) {
        WebClientResponseException responseException = findResponseException(error);
        if (responseException != null) {
            int status = responseException.getStatusCode().value();
            return status == 429 || status == 529 || status >= 500;
        }
        return hasCause(error, TimeoutException.class)
                || hasCause(error, SocketTimeoutException.class)
                || hasCause(error, WebClientRequestException.class);
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> causeType) {
        Throwable current = error;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean sleepBeforeReactProviderRetry(int attempt) {
        try {
            Thread.sleep(Math.min(REACT_PROVIDER_RETRY_BACKOFF_MILLIS * Math.max(attempt, 1), 1000L));
            return true;
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String shortExceptionMessage(Throwable error) {
        if (error == null || error.getMessage() == null) {
            return "";
        }
        return limitText(error.getMessage(), 300);
    }

    @SuppressWarnings("unchecked")
    private String reactRequestDiagnostics(Map<String, Object> request) {
        List<Map<String, Object>> messages = request == null || !(request.get("messages") instanceof List<?> rawMessages)
                ? List.of()
                : (List<Map<String, Object>>) rawMessages;
        List<String> roles = new ArrayList<>();
        int toolMessageCount = 0;
        int assistantToolCallCount = 0;
        int contentChars = 0;
        for (Map<String, Object> message : messages) {
            String role = String.valueOf(message.getOrDefault("role", ""));
            roles.add(role);
            if ("tool".equals(role)) {
                toolMessageCount++;
            }
            Object content = message.get("content");
            if (content != null) {
                contentChars += String.valueOf(content).length();
            }
            if (message.get("tool_calls") instanceof List<?> toolCalls) {
                assistantToolCallCount += toolCalls.size();
            }
        }
        int toolSpecCount = request != null && request.get("tools") instanceof List<?> tools ? tools.size() : 0;
        return "messageCount=" + messages.size()
                + ", roles=" + String.join(",", roles)
                + ", toolSpecCount=" + toolSpecCount
                + ", assistantToolCallCount=" + assistantToolCallCount
                + ", toolMessageCount=" + toolMessageCount
                + ", messageContentChars=" + contentChars;
    }

    private Map<String, Object> buildReActRequest(String model,
                                                  List<Map<String, Object>> messages,
                                                  List<ToolDefinition> tools,
                                                  int maxCompletionTokens,
                                                  boolean stream) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", stream);
        if (maxCompletionTokens > 0) {
            request.put("max_tokens", maxCompletionTokens);
        }
        disableMiniMaxM3Thinking(model, request);
        if (stream) {
            request.put("stream_options", Map.of("include_usage", true));
        }

        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (tools != null && !tools.isEmpty()) {
            request.put("tools", buildOpenAiTools(tools));
            request.put("tool_choice", "auto");
        }
        return request;
    }

    private void disableMiniMaxM3Thinking(String model, Map<String, Object> request) {
        if ("MiniMax-M3".equalsIgnoreCase(model)) {
            request.put("thinking", Map.of("type", "disabled"));
        }
    }

    private String limitText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(maxChars, 0)) + "...";
    }

    private List<Map<String, Object>> buildOpenAiTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> openAiTools = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", tool.parameters());

            Map<String, Object> toolSchema = new LinkedHashMap<>();
            toolSchema.put("type", "function");
            toolSchema.put("function", function);
            openAiTools.add(toolSchema);
        }
        return openAiTools;
    }

    private int estimateToolsTokens(List<ToolDefinition> tools) {
        int tokens = 0;
        for (ToolDefinition tool : tools) {
            tokens += usageQuotaService.estimateTextTokens(tool.name());
            tokens += usageQuotaService.estimateTextTokens(tool.description());
            try {
                tokens += usageQuotaService.estimateTextTokens(objectMapper.writeValueAsString(tool.parameters()));
            } catch (Exception ignored) {
                tokens += 80;
            }
        }
        return tokens;
    }

    private int estimateObjectMessagesTokens(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        for (Map<String, Object> message : messages) {
            tokens += 8;
            tokens += usageQuotaService.estimateTextTokens(String.valueOf(message.getOrDefault("role", "")));
            tokens += usageQuotaService.estimateTextTokens(String.valueOf(message.getOrDefault("content", "")));
            Object reasoningContent = message.get("reasoning_content");
            if (reasoningContent != null) {
                tokens += usageQuotaService.estimateTextTokens(String.valueOf(reasoningContent));
            }
            Object toolCalls = message.get("tool_calls");
            if (toolCalls != null) {
                try {
                    tokens += usageQuotaService.estimateTextTokens(objectMapper.writeValueAsString(toolCalls));
                } catch (Exception ignored) {
                    tokens += 128;
                }
            }
            Object toolCallId = message.get("tool_call_id");
            if (toolCallId != null) {
                tokens += usageQuotaService.estimateTextTokens(String.valueOf(toolCallId));
            }
        }
        return Math.max(tokens, 1);
    }

    private ReActTurn parseReActTurn(String responseBody, int estimatedPromptTokens) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("ReAct 模型响应为空");
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choice = root.path("choices").path(0);
            JsonNode messageNode = choice.path("message");
            if (!messageNode.isObject()) {
                throw new IllegalStateException("ReAct 模型响应缺少 message");
            }

            Map<String, Object> assistantMessage = objectMapper.convertValue(
                    messageNode,
                    new TypeReference<Map<String, Object>>() {
                    });
            assistantMessage.put("role", "assistant");
            if (!assistantMessage.containsKey("content") || assistantMessage.get("content") == null) {
                assistantMessage.put("content", "");
            }

            List<ToolCallDecision> toolCalls = new ArrayList<>();
            JsonNode toolCallsNode = messageNode.path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode call : toolCallsNode) {
                    JsonNode function = call.path("function");
                    String name = function.path("name").asText("");
                    if (name.isBlank()) {
                        continue;
                    }
                    String argumentsJson = function.path("arguments").asText("{}");
                    Map<String, Object> arguments = objectMapper.readValue(
                            argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson,
                            new TypeReference<Map<String, Object>>() {
                            });
                    toolCalls.add(new ToolCallDecision(call.path("id").asText(""), name, arguments));
                }
            }

            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(estimatedPromptTokens);
            int completionTokens = usage.path("completion_tokens").asInt(
                    usageQuotaService.estimateTextTokens(messageNode.path("content").asText(""))
                            + estimateObjectMessagesTokens(List.of(assistantMessage))
            );
            return new ReActTurn(
                    messageNode.path("content").asText("").trim(),
                    toolCalls,
                    assistantMessage,
                    choice.path("finish_reason").asText("unknown"),
                    promptTokens,
                    completionTokens
            );
        } catch (Exception exception) {
            throw new RuntimeException("解析 ReAct 模型响应失败", exception);
        }
    }

    public record StreamCompletion(
            String finishReason,
            int promptTokens,
            int completionTokens,
            int responseChars
    ) {
    }

    public record ToolCallDecision(
            String id,
            String name,
            Map<String, Object> arguments
    ) {
    }

    public record ReActTurn(
            String content,
            List<ToolCallDecision> toolCalls,
            Map<String, Object> assistantMessage,
            String finishReason,
            int promptTokens,
            int completionTokens
    ) {
    }

}
