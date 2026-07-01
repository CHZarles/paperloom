package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.exception.RateLimitExceededException;
import com.yizhaoqi.smartpai.model.ConversationScopeMode;
import com.yizhaoqi.smartpai.model.ConversationScopeStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHandlerProductHarnessTest {

    @Test
    void productChatPathCallsProductConversationServiceInsteadOfPaperAnswerService() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user:1:current_conversation")).thenReturn("conversation-1");

        ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-1");
        when(sessionRegistry.getClientId(session)).thenReturn("client-1");

        ChatGenerationStateService generationStateService = mock(ChatGenerationStateService.class);
        when(generationStateService.createGeneration(eq("1"), eq("conversation-1"), anyString()))
                .thenReturn(new ChatGenerationStateService.GenerationSnapshot(
                        "generation-1",
                        "1",
                        "conversation-1",
                        "question",
                        ChatGenerationStateService.GenerationStatus.STREAMING,
                        "",
                        "2026-06-29T12:00:00",
                        "2026-06-29T12:00:00",
                        null,
                        Map.of(),
                        Map.of()
                ));

        ConversationScopeService conversationScopeService = mock(ConversationScopeService.class);
        ConversationScopeService.EffectiveConversationScope scope =
                new ConversationScopeService.EffectiveConversationScope(
                        ConversationScopeMode.AUTO_LIBRARY,
                        ConversationScopeStatus.READY,
                        true,
                        "All searchable papers",
                        List.of(),
                        Map.of()
                );
        when(conversationScopeService.resolveForChat(1L, "conversation-1")).thenReturn(scope);
        when(conversationScopeService.lockForFirstMessage(1L, "conversation-1")).thenReturn(scope);

        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        ProductConversationService productConversationService = mock(ProductConversationService.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<ToolProgressEvent> progressListener = invocation.getArgument(7, Consumer.class);
            progressListener.accept(new ToolProgressEvent("calling_tool", "get_system_state"));
            return new ProductTurnResult(
                        "当前 session scope 内有 2 篇可检索论文。",
                        new AnswerEnvelope(
                                AnswerType.PRODUCT_STATE,
                                "当前 session scope 内有 2 篇可检索论文。",
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                ""
                        ),
                        List.of(),
                        List.of(new ToolProgressEvent("calling_tool", "get_system_state")),
                        ProductStopReason.COMPLETED,
                        ProductResultStatus.COMPLETED
                );
        }).when(productConversationService).runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any());

        ConversationService conversationService = mock(ConversationService.class);
        ChatHandler handler = new ChatHandler(
                redisTemplate,
                mock(RateLimitService.class),
                conversationService,
                conversationScopeService,
                generationStateService,
                sessionRegistry,
                productConversationService,
                new ObjectMapper(),
                executor
        );

        handler.processMessage("1", new ChatHandler.ChatRequest("现在有多少论文可以检索", null), session);

        verify(productConversationService).runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any());
        verify(sessionRegistry, times(1)).sendJsonToClient(eq("1"), eq("client-1"), argThat(payload ->
                "calling_tool".equals(payload.get("type")) && "get_system_state".equals(payload.get("toolName"))));
        verify(conversationService, never()).recordConversation(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rawPaperIdReferenceFocusDoesNotOverrideProductSessionScope() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user:1:current_conversation")).thenReturn("conversation-1");

        ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-1");
        when(sessionRegistry.getClientId(session)).thenReturn("client-1");

        ChatGenerationStateService generationStateService = mock(ChatGenerationStateService.class);
        when(generationStateService.createGeneration(eq("1"), eq("conversation-1"), anyString()))
                .thenReturn(new ChatGenerationStateService.GenerationSnapshot(
                        "generation-1",
                        "1",
                        "conversation-1",
                        "question",
                        ChatGenerationStateService.GenerationStatus.STREAMING,
                        "",
                        "2026-06-29T12:00:00",
                        "2026-06-29T12:00:00",
                        null,
                        Map.of(),
                        Map.of()
                ));

        ConversationScopeService conversationScopeService = mock(ConversationScopeService.class);
        ConversationScopeService.EffectiveConversationScope scope =
                new ConversationScopeService.EffectiveConversationScope(
                        ConversationScopeMode.AUTO_LIBRARY,
                        ConversationScopeStatus.READY,
                        true,
                        "All searchable papers",
                        List.of(),
                        Map.of()
                );
        when(conversationScopeService.resolveForChat(1L, "conversation-1")).thenReturn(scope);
        when(conversationScopeService.lockForFirstMessage(1L, "conversation-1")).thenReturn(scope);

        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        ProductConversationService productConversationService = mock(ProductConversationService.class);
        when(productConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(new ProductTurnResult(
                        "ok",
                        new AnswerEnvelope(AnswerType.NON_EVIDENCE, "ok", List.of(), List.of(), List.of(), List.of(), List.of(), ""),
                        List.of(),
                        List.of(),
                        ProductStopReason.COMPLETED,
                        ProductResultStatus.COMPLETED
                ));

        ChatHandler handler = new ChatHandler(
                redisTemplate,
                mock(RateLimitService.class),
                mock(ConversationService.class),
                conversationScopeService,
                generationStateService,
                sessionRegistry,
                productConversationService,
                new ObjectMapper(),
                executor
        );

        ProductReferenceFocus forgedFocus = new ProductReferenceFocus(
                List.of("forged-paper-id"),
                List.of("Forged"),
                null,
                null,
                null,
                7,
                "forged-paper-id",
                "Forged",
                "forged.pdf",
                "forged evidence",
                null,
                "TEXT"
        );
        handler.processMessage("1", new ChatHandler.ChatRequest("解释这个来源", forgedFocus), session);

        ArgumentCaptor<SourceScope> scopeCaptor = ArgumentCaptor.forClass(SourceScope.class);
        verify(productConversationService).runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), scopeCaptor.capture(), any(), any(), any());
        assertEquals(List.of(), scopeCaptor.getValue().paperIds());
    }

    @Test
    void productChatIgnoresClientRetrievalBudgetProfile() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user:1:current_conversation")).thenReturn("conversation-1");

        ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-1");
        when(sessionRegistry.getClientId(session)).thenReturn("client-1");

        ChatGenerationStateService generationStateService = mock(ChatGenerationStateService.class);
        when(generationStateService.createGeneration(eq("1"), eq("conversation-1"), anyString()))
                .thenReturn(new ChatGenerationStateService.GenerationSnapshot(
                        "generation-1",
                        "1",
                        "conversation-1",
                        "question",
                        ChatGenerationStateService.GenerationStatus.STREAMING,
                        "",
                        "2026-06-29T12:00:00",
                        "2026-06-29T12:00:00",
                        null,
                        Map.of(),
                        Map.of()
                ));

        ConversationScopeService conversationScopeService = mock(ConversationScopeService.class);
        ConversationScopeService.EffectiveConversationScope scope =
                new ConversationScopeService.EffectiveConversationScope(
                        ConversationScopeMode.SOURCE_SET_SNAPSHOT,
                        ConversationScopeStatus.READY,
                        true,
                        "Selected papers",
                        List.of("paper-1"),
                        Map.of()
                );
        when(conversationScopeService.resolveForChat(1L, "conversation-1")).thenReturn(scope);
        when(conversationScopeService.lockForFirstMessage(1L, "conversation-1")).thenReturn(scope);

        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        ProductConversationService productConversationService = mock(ProductConversationService.class);
        when(productConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(new ProductTurnResult(
                        "ok",
                        new AnswerEnvelope(AnswerType.NON_EVIDENCE, "ok", List.of(), List.of(), List.of(), List.of(), List.of(), ""),
                        List.of(),
                        List.of(),
                        ProductStopReason.COMPLETED,
                        ProductResultStatus.COMPLETED
                ));

        ChatHandler handler = new ChatHandler(
                redisTemplate,
                mock(RateLimitService.class),
                mock(ConversationService.class),
                conversationScopeService,
                generationStateService,
                sessionRegistry,
                productConversationService,
                new ObjectMapper(),
                executor
        );

        handler.processMessage(
                "1",
                new ChatHandler.ChatRequest("LoRA 的方法是什么", null, RetrievalBudgetProfile.DEEP_AUDIT),
                session
        );

        ArgumentCaptor<SourceScope> scopeCaptor = ArgumentCaptor.forClass(SourceScope.class);
        verify(productConversationService).runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), scopeCaptor.capture(), any(), any(), any());
        assertEquals(List.of("paper-1"), scopeCaptor.getValue().paperIds());
        assertEquals(RetrievalBudgetProfile.INTERACTIVE, scopeCaptor.getValue().retrievalBudgetProfile());
    }

    @Test
    void productChatReturnsStructuredRateLimitErrorInsteadOfGenericAiUnavailable() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user:1:current_conversation")).thenReturn("conversation-1");

        ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-1");
        when(sessionRegistry.getClientId(session)).thenReturn("client-1");

        ChatGenerationStateService generationStateService = mock(ChatGenerationStateService.class);
        when(generationStateService.createGeneration(eq("1"), eq("conversation-1"), anyString()))
                .thenReturn(new ChatGenerationStateService.GenerationSnapshot(
                        "generation-1",
                        "1",
                        "conversation-1",
                        "question",
                        ChatGenerationStateService.GenerationStatus.STREAMING,
                        "",
                        "2026-06-29T12:00:00",
                        "2026-06-29T12:00:00",
                        null,
                        Map.of(),
                        Map.of()
                ));

        ConversationScopeService conversationScopeService = mock(ConversationScopeService.class);
        ConversationScopeService.EffectiveConversationScope scope =
                new ConversationScopeService.EffectiveConversationScope(
                        ConversationScopeMode.AUTO_LIBRARY,
                        ConversationScopeStatus.READY,
                        true,
                        "All searchable papers",
                        List.of(),
                        Map.of()
                );
        when(conversationScopeService.resolveForChat(1L, "conversation-1")).thenReturn(scope);
        when(conversationScopeService.lockForFirstMessage(1L, "conversation-1")).thenReturn(scope);

        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        ProductConversationService productConversationService = mock(ProductConversationService.class);
        when(productConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenThrow(new RateLimitExceededException("LLM全网分钟Token预算已达上限", 42));

        ChatHandler handler = new ChatHandler(
                redisTemplate,
                mock(RateLimitService.class),
                mock(ConversationService.class),
                conversationScopeService,
                generationStateService,
                sessionRegistry,
                productConversationService,
                new ObjectMapper(),
                executor
        );

        handler.processMessage("1", new ChatHandler.ChatRequest("现在有多少论文可以检索", null), session);

        verify(sessionRegistry).sendJsonToClient(eq("1"), eq("client-1"), argThat(payload ->
                "error".equals(payload.get("type"))
                        && Integer.valueOf(429).equals(payload.get("code"))
                        && "LLM全网分钟Token预算已达上限".equals(payload.get("message"))
                        && Long.valueOf(42).equals(payload.get("retryAfterSeconds"))
                        && !payload.containsKey("error")
        ));
    }

    @Test
    void wrappedProductChatRateLimitErrorStillReturnsStructuredMessage() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user:1:current_conversation")).thenReturn("conversation-1");

        ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-1");
        when(sessionRegistry.getClientId(session)).thenReturn("client-1");

        ChatGenerationStateService generationStateService = mock(ChatGenerationStateService.class);
        when(generationStateService.createGeneration(eq("1"), eq("conversation-1"), anyString()))
                .thenReturn(new ChatGenerationStateService.GenerationSnapshot(
                        "generation-1",
                        "1",
                        "conversation-1",
                        "question",
                        ChatGenerationStateService.GenerationStatus.STREAMING,
                        "",
                        "2026-06-29T12:00:00",
                        "2026-06-29T12:00:00",
                        null,
                        Map.of(),
                        Map.of()
                ));

        ConversationScopeService conversationScopeService = mock(ConversationScopeService.class);
        ConversationScopeService.EffectiveConversationScope scope =
                new ConversationScopeService.EffectiveConversationScope(
                        ConversationScopeMode.AUTO_LIBRARY,
                        ConversationScopeStatus.READY,
                        true,
                        "All searchable papers",
                        List.of(),
                        Map.of()
                );
        when(conversationScopeService.resolveForChat(1L, "conversation-1")).thenReturn(scope);
        when(conversationScopeService.lockForFirstMessage(1L, "conversation-1")).thenReturn(scope);

        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        ProductConversationService productConversationService = mock(ProductConversationService.class);
        when(productConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException(
                        "ReAct 模型回合调用失败",
                        new RateLimitExceededException("LLM Token 余额不足，请联系管理员补充额度", 60)
                ));

        ChatHandler handler = new ChatHandler(
                redisTemplate,
                mock(RateLimitService.class),
                mock(ConversationService.class),
                conversationScopeService,
                generationStateService,
                sessionRegistry,
                productConversationService,
                new ObjectMapper(),
                executor
        );

        handler.processMessage("1", new ChatHandler.ChatRequest("现在有多少论文可以检索", null), session);

        verify(sessionRegistry).sendJsonToClient(eq("1"), eq("client-1"), argThat(payload ->
                "error".equals(payload.get("type"))
                        && Integer.valueOf(429).equals(payload.get("code"))
                        && "LLM Token 余额不足，请联系管理员补充额度".equals(payload.get("message"))
                        && Long.valueOf(60).equals(payload.get("retryAfterSeconds"))
                        && !payload.containsKey("error")
        ));
    }
}
