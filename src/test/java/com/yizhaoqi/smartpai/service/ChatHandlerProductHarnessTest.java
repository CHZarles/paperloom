package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.ProductReadingReactProperties;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.exception.RateLimitExceededException;
import com.yizhaoqi.smartpai.model.ConversationScopeMode;
import com.yizhaoqi.smartpai.model.ConversationScopeStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.InOrder;

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
        when(generationStateService.createGeneration(eq("1"), eq("client-1"), eq("conversation-1"), anyString()))
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
    void explicitConversationIdOverridesRedisCurrentConversationForChatTurn() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user:1:current_conversation")).thenReturn("conversation-redis");

        ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-1");
        when(sessionRegistry.getClientId(session)).thenReturn("client-1");

        ChatGenerationStateService generationStateService = mock(ChatGenerationStateService.class);
        when(generationStateService.createGeneration(eq("1"), eq("client-1"), eq("conversation-explicit"), anyString()))
                .thenReturn(new ChatGenerationStateService.GenerationSnapshot(
                        "generation-explicit",
                        "1",
                        "conversation-explicit",
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
        when(conversationScopeService.resolveForChat(1L, "conversation-explicit")).thenReturn(scope);
        when(conversationScopeService.lockForFirstMessage(1L, "conversation-explicit")).thenReturn(scope);

        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        ProductConversationService productConversationService = mock(ProductConversationService.class);
        when(productConversationService.runTurn(eq(1L), eq("conversation-explicit"), eq("generation-explicit"), anyString(), any(), any(), any(), any()))
                .thenReturn(new ProductTurnResult(
                        "ok",
                        new AnswerEnvelope(AnswerType.NON_EVIDENCE, "ok", List.of(), List.of(), List.of(), List.of(), List.of(), ""),
                        List.of(),
                        List.of(),
                        ProductStopReason.COMPLETED,
                        ProductResultStatus.COMPLETED
                ));

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

        handler.processMessage(
                "1",
                new ChatHandler.ChatRequest("keep this in the visible thread", null, "conversation-explicit"),
                session
        );

        verify(conversationService).requireActiveOwnedConversationSession(1L, "conversation-explicit");
        verify(valueOperations, never()).get("user:1:current_conversation");
        verify(conversationService, never()).ensureConversationSession(eq(1L), eq("conversation-redis"), anyString());
        verify(productConversationService).runTurn(eq(1L), eq("conversation-explicit"), eq("generation-explicit"), anyString(), any(), any(), any(), any());
    }

    @Test
    void invalidExplicitConversationIdFailsClosedWithoutRedisFallback() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-1");
        when(sessionRegistry.getClientId(session)).thenReturn("client-1");

        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.requireActiveOwnedConversationSession(1L, "foreign-conversation"))
                .thenThrow(new CustomException("对话不存在", HttpStatus.NOT_FOUND));

        ChatGenerationStateService generationStateService = mock(ChatGenerationStateService.class);
        ProductConversationService productConversationService = mock(ProductConversationService.class);
        ChatHandler handler = new ChatHandler(
                redisTemplate,
                mock(RateLimitService.class),
                conversationService,
                mock(ConversationScopeService.class),
                generationStateService,
                sessionRegistry,
                productConversationService,
                new ObjectMapper(),
                mock(ThreadPoolTaskExecutor.class)
        );

        handler.processMessage(
                "1",
                new ChatHandler.ChatRequest("do not reroute this", null, "foreign-conversation"),
                session
        );

        verify(conversationService).requireActiveOwnedConversationSession(1L, "foreign-conversation");
        verify(valueOperations, never()).get("user:1:current_conversation");
        verify(generationStateService, never()).createGeneration(anyString(), anyString(), anyString(), anyString());
        verify(productConversationService, never()).runTurn(any(), any(), any(), any(), any(), any(), any(), any());
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
        when(generationStateService.createGeneration(eq("1"), eq("client-1"), eq("conversation-1"), anyString()))
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
        when(generationStateService.createGeneration(eq("1"), eq("client-1"), eq("conversation-1"), anyString()))
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
        when(generationStateService.createGeneration(eq("1"), eq("client-1"), eq("conversation-1"), anyString()))
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
        when(generationStateService.createGeneration(eq("1"), eq("client-1"), eq("conversation-1"), anyString()))
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

    @Test
    void readingFlagDisabledKeepsSourceQuoteFocusOnProductPath() {
        ChatFixture fixture = chatFixture(false);
        ProductReferenceFocus focus = sourceQuoteFocus("source_quote_clicked", 1);

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个引用", focus), fixture.session);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> effectiveScopeCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.productConversationService).runTurn(
                eq(1L),
                eq("conversation-1"),
                eq("generation-1"),
                anyString(),
                any(),
                any(),
                effectiveScopeCaptor.capture(),
                any()
        );
        verify(fixture.readingConversationService, never()).runTurn(any(), any(), any(), any(), any(), any(), any(), any());
        assertFalse(effectiveScopeCaptor.getValue().containsKey("clickedSourceQuoteRefs"));
        verify(fixture.conversationService, never()).recordConversation(any(), any(), any(), any(), any(), any());
    }

    @Test
    void readingFlagDisabledKeepsClickedPaperHandleOnProductPath() {
        ChatFixture fixture = chatFixture(false);
        ProductReferenceFocus focus = paperHandleFocus("paper_handle_clicked");

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看这篇论文", focus), fixture.session);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> effectiveScopeCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.productConversationService).runTurn(
                eq(1L),
                eq("conversation-1"),
                eq("generation-1"),
                anyString(),
                any(),
                any(),
                effectiveScopeCaptor.capture(),
                any()
        );
        verify(fixture.readingConversationService, never()).runTurn(any(), any(), any(), any(), any(), any(), any(), any());
        assertFalse(effectiveScopeCaptor.getValue().containsKey("clickedPaperHandles"));
        verify(fixture.productPaperHandleService, never()).handleForPaperId(anyString());
    }

    @Test
    void readingFlagEnabledRoutesChatToProductReadingConversationService() {
        ChatFixture fixture = chatFixture(true);

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("阅读这篇论文", null), fixture.session);

        verify(fixture.readingConversationService).runTurn(
                eq(1L),
                eq("conversation-1"),
                eq("generation-1"),
                eq("阅读这篇论文"),
                any(),
                any(),
                any(),
                any()
        );
        verify(fixture.productConversationService, never()).runTurn(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void structuredPaperHandleFocusReachesReadingEffectiveScope() {
        ChatFixture fixture = chatFixture(true);
        ProductReferenceFocus focus = paperHandleFocus("paper_handle_clicked");

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看这篇论文", focus), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertEquals(List.of("paper_handle_clicked"), effectiveScope.get("clickedPaperHandles"));
        verify(fixture.productPaperHandleService, never()).handleForPaperId(anyString());
    }

    @Test
    void structuredPaperIdFocusConvertsToReadingPaperHandleAfterScopeValidation() {
        ChatFixture fixture = chatFixture(true);
        ProductReferenceFocus focus = paperIdFocus("paper-1");
        when(fixture.productPaperHandleService.handleForPaperId("paper-1")).thenReturn("paper_handle_converted");

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看这篇论文", focus), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertEquals(List.of("paper_handle_converted"), effectiveScope.get("clickedPaperHandles"));
        assertFalse(effectiveScope.toString().contains("paper-1"));
        InOrder inOrder = inOrder(fixture.conversationScopeService, fixture.productPaperHandleService);
        inOrder.verify(fixture.conversationScopeService, times(2)).assertReferenceFocusWithinScope(any(), any());
        inOrder.verify(fixture.productPaperHandleService).handleForPaperId("paper-1");
    }

    @Test
    void typedPaperHandleWithoutStructuredFocusDoesNotReachReadingEffectiveScope() {
        ChatFixture fixture = chatFixture(true);

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("请看 paper_handle_clicked", null), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertFalse(effectiveScope.containsKey("clickedPaperHandles"));
        verify(fixture.productPaperHandleService, never()).handleForPaperId(anyString());
    }

    @Test
    void outOfScopeStructuredPaperIdIsRejectedBeforePaperHandleConversion() {
        ChatFixture fixture = chatFixture(true);
        ProductReferenceFocus focus = paperIdFocus("outside-paper");
        doThrow(new CustomException("Reference focus is outside the conversation source scope", HttpStatus.FORBIDDEN))
                .when(fixture.conversationScopeService)
                .assertReferenceFocusWithinScope(any(), argThat(scope -> "outside-paper".equals(scope.paperId())));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看这篇论文", focus), fixture.session);

        verify(fixture.generationStateService, never()).createGeneration(anyString(), anyString(), anyString(), anyString());
        verify(fixture.productPaperHandleService, never()).handleForPaperId(anyString());
        verify(fixture.readingConversationService, never()).runTurn(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void structuredSourceQuoteFocusReachesReadingEffectiveScope() {
        ChatFixture fixture = chatFixture(true);
        ProductReferenceFocus focus = sourceQuoteFocus("source_quote_clicked", null);

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个引用", focus), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertEquals(List.of("source_quote_clicked"), effectiveScope.get("clickedSourceQuoteRefs"));
    }

    @Test
    void typedCitationDoesNotBecomeClickedSourceQuoteOnReadingPath() {
        ChatFixture fixture = chatFixture(true);

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释 [1]", null), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertFalse(effectiveScope.containsKey("clickedSourceQuoteRefs"));
        verify(fixture.conversationService, never()).findLatestReferenceDetail(any(), any(), any());
    }

    @Test
    void sourceQuoteFocusWithReferenceNumberDoesNotRequireLegacyPaperResolution() {
        ChatFixture fixture = chatFixture(true);
        ProductReferenceFocus focus = sourceQuoteFocus("source_quote_clicked", 1);

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个引用", focus), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertEquals(List.of("source_quote_clicked"), effectiveScope.get("clickedSourceQuoteRefs"));
        verify(fixture.conversationService, never()).findLatestReferenceDetail(any(), any(), any());
        verify(fixture.conversationService, never()).findReferenceDetail(any(), any(), any());
    }

    @Test
    void sourceQuoteFocusWithoutReferenceNumberIsStillStructuredFocus() {
        ChatFixture fixture = chatFixture(true);
        ProductReferenceFocus focus = sourceQuoteFocus("source_quote_clicked", null);

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个来源", focus), fixture.session);

        verify(fixture.conversationScopeService, times(2)).assertReferenceFocusWithinScope(
                any(),
                argThat(scope -> "source_quote_clicked".equals(scope.sourceQuoteRef()))
        );
        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertEquals(List.of("source_quote_clicked"), effectiveScope.get("clickedSourceQuoteRefs"));
    }

    @Test
    void readingSourceQuoteReferencesAreStoredInGenerationStateAndConversationHistory() {
        ChatFixture fixture = chatFixture(true);
        when(fixture.readingConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(completedTurn(
                        "这段引用说明了方法细节 [1]",
                        List.of(Map.of(
                                "referenceNumber", 1,
                                "sourceQuoteRef", "source_quote_answer",
                                "content", "quoted source content",
                                "paperTitle", "LoRA",
                                "retrievalRoute", "PRODUCT_READING"
                        ))
                ));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个引用", null), fixture.session);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Map<String, Object>>> updateCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.generationStateService).updateReferenceMappings(eq("generation-1"), updateCaptor.capture());
        assertSourceQuoteMapping(updateCaptor.getValue().get("1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Map<String, Object>>> persistedReferencesCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> persistedScopeCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.conversationService).recordConversation(
                eq(1L),
                eq("解释这个引用"),
                eq("这段引用说明了方法细节 [1]"),
                eq("conversation-1"),
                persistedReferencesCaptor.capture(),
                persistedScopeCaptor.capture()
        );
        assertSourceQuoteMapping(persistedReferencesCaptor.getValue().get("1"));
        assertEquals("AUTO_LIBRARY", persistedScopeCaptor.getValue().get("scopeMode"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Map<String, Object>>> completedCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.generationStateService).markCompleted(eq("generation-1"), completedCaptor.capture());
        assertSourceQuoteMapping(completedCaptor.getValue().get("1"));
    }

    @Test
    void readingCompletionSendsSanitizedProductStateItems() {
        ChatFixture fixture = chatFixture(true);
        List<Map<String, Object>> productStateItems = new java.util.ArrayList<>();
        productStateItems.add(paperChoiceItem("paper_handle_000", "First"));
        productStateItems.add(paperChoiceItem("paper_handle_000", "Duplicate"));
        productStateItems.add(Map.of(
                "kind", "READING_PAPER_CHOICE",
                "sourceTool", "find_papers_by_identity",
                "paperHandle", "not_a_handle",
                "title", "Invalid"
        ));
        productStateItems.add(Map.of(
                "kind", "OTHER",
                "sourceTool", "find_papers_by_identity",
                "paperHandle", "paper_handle_wrong_kind",
                "title", "Wrong kind"
        ));
        for (int index = 1; index <= 12; index++) {
            productStateItems.add(paperChoiceItem("paper_handle_" + index, "Paper " + index));
        }
        when(fixture.readingConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(completedTurn("请选择论文", List.of(), productStateItems));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看 Ada 的论文", null), fixture.session);

        Map<String, Object> completion = completionPayload(fixture, "finished");
        assertTrue(completion.containsKey("productStateItems"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payloadItems = (List<Map<String, Object>>) completion.get("productStateItems");
        assertEquals(10, payloadItems.size());
        assertEquals("paper_handle_000", payloadItems.get(0).get("paperHandle"));
        assertEquals("First", payloadItems.get(0).get("title"));
        assertEquals("paper_handle_9", payloadItems.get(9).get("paperHandle"));
        assertFalse(payloadItems.toString().contains("Duplicate"));
        assertFalse(payloadItems.toString().contains("not_a_handle"));
        assertFalse(payloadItems.toString().contains("wrong_kind"));
        for (Map<String, Object> item : payloadItems) {
            assertEquals("READING_PAPER_CHOICE", item.get("kind"));
            assertEquals("find_papers_by_identity", item.get("sourceTool"));
            assertFalse(item.containsKey("paperId"));
            assertFalse(item.containsKey("ordinal"));
            assertFalse(item.containsKey("preview"));
            assertFalse(item.containsKey("score"));
            assertFalse(item.containsKey("rank"));
            assertFalse(item.containsKey("locationRef"));
            assertFalse(item.containsKey("sourceQuoteRef"));
        }
        verify(fixture.conversationService).recordConversation(eq(1L), eq("看 Ada 的论文"), eq("请选择论文"), eq("conversation-1"), any(), any());
    }

    @Test
    void readingCompletionSendsListAndSearchPaperChoiceProductStateItems() {
        ChatFixture fixture = chatFixture(true);
        List<Map<String, Object>> productStateItems = List.of(
                paperChoiceItem("paper_handle_list", "Browse Paper", "list_papers"),
                paperChoiceItem("paper_handle_search", "Search Paper", "search_paper_candidates")
        );
        when(fixture.readingConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(completedTurn("请选择论文", List.of(), productStateItems));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("找论文", null), fixture.session);

        Map<String, Object> completion = completionPayload(fixture, "finished");
        assertTrue(completion.containsKey("productStateItems"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payloadItems = (List<Map<String, Object>>) completion.get("productStateItems");
        assertEquals(2, payloadItems.size());
        assertEquals("list_papers", payloadItems.get(0).get("sourceTool"));
        assertEquals("paper_handle_list", payloadItems.get(0).get("paperHandle"));
        assertEquals("search_paper_candidates", payloadItems.get(1).get("sourceTool"));
        assertEquals("paper_handle_search", payloadItems.get(1).get("paperHandle"));
        for (Map<String, Object> item : payloadItems) {
            assertEquals("READING_PAPER_CHOICE", item.get("kind"));
            assertFalse(item.containsKey("preview"));
            assertFalse(item.containsKey("ordinal"));
            assertFalse(item.containsKey("paperId"));
            assertFalse(item.containsKey("catalogTopics"));
            assertFalse(item.containsKey("paperTypes"));
            assertFalse(item.containsKey("facets"));
            assertFalse(item.containsKey("identityStatus"));
            assertFalse(item.containsKey("ambiguous"));
            assertFalse(item.containsKey("matchReasons"));
        }
    }

    @Test
    void readingCompletionRejectsUnsupportedPaperChoiceSourceTool() {
        ChatFixture fixture = chatFixture(true);
        when(fixture.readingConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(completedTurn("没有可选论文", List.of(), List.of(
                        paperChoiceItem("paper_handle_outline", "Outline Paper", "get_paper_outline")
                )));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看大纲", null), fixture.session);

        assertFalse(completionPayload(fixture, "finished").containsKey("productStateItems"));
    }

    @Test
    void readingCompletionStripsSearchPreviewAndBrowseFacetsFromPaperChoiceItems() {
        ChatFixture fixture = chatFixture(true);
        Map<String, Object> searchItem = paperChoiceItem("paper_handle_search", "Search Paper", "search_paper_candidates");
        searchItem.put("preview", "not evidence");
        searchItem.put("catalogTopics", List.of("Agents"));
        searchItem.put("paperTypes", List.of("Benchmark"));
        searchItem.put("facets", Map.of("years", List.of()));
        when(fixture.readingConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(completedTurn("请选择论文", List.of(), List.of(searchItem)));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("搜索论文", null), fixture.session);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payloadItems =
                (List<Map<String, Object>>) completionPayload(fixture, "finished").get("productStateItems");
        assertEquals(1, payloadItems.size());
        Map<String, Object> item = payloadItems.get(0);
        assertEquals("search_paper_candidates", item.get("sourceTool"));
        assertEquals("paper_handle_search", item.get("paperHandle"));
        assertFalse(item.containsKey("preview"));
        assertFalse(item.containsKey("catalogTopics"));
        assertFalse(item.containsKey("paperTypes"));
        assertFalse(item.containsKey("facets"));
        assertFalse(item.containsKey("ordinal"));
        assertFalse(item.containsKey("paperId"));
        assertFalse(item.containsKey("score"));
        assertFalse(item.containsKey("rank"));
        assertFalse(item.containsKey("locationRef"));
        assertFalse(item.containsKey("sourceQuoteRef"));
    }

    @Test
    void readingCompletionOmitsInvalidOnlyProductStateItems() {
        ChatFixture fixture = chatFixture(true);
        when(fixture.readingConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(completedTurn("没有可选论文", List.of(), List.of(Map.of(
                        "kind", "READING_PAPER_CHOICE",
                        "sourceTool", "find_papers_by_identity",
                        "paperHandle", "raw-paper-id",
                        "title", "Invalid"
                ))));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看论文", null), fixture.session);

        assertFalse(completionPayload(fixture, "finished").containsKey("productStateItems"));
    }

    @Test
    void legacyProductCompletionOmitsProductStateItemsEvenWhenResultContainsThem() {
        ChatFixture fixture = chatFixture(false);
        when(fixture.productConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(completedTurn("product ok", List.of(), List.of(paperChoiceItem("paper_handle_abc", "Legacy"))));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("列出论文", null), fixture.session);

        assertFalse(completionPayload(fixture, "finished").containsKey("productStateItems"));
        verify(fixture.readingConversationService, never()).runTurn(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedReadingCompletionOmitsProductStateItems() {
        ChatFixture fixture = chatFixture(true);
        when(fixture.readingConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(new ProductTurnResult(
                        "failed",
                        new AnswerEnvelope(AnswerType.CLARIFICATION_NEEDED, "failed", List.of(), List.of(), List.of(), List.of(), List.of(), ""),
                        List.of(),
                        List.of(),
                        List.of(paperChoiceItem("paper_handle_abc", "Should not send")),
                        ProductStopReason.TOOL_FAILED,
                        ProductResultStatus.FAILED
                ));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看论文", null), fixture.session);

        assertFalse(completionPayload(fixture, "failed").containsKey("productStateItems"));
    }

    private static void assertSourceQuoteMapping(Map<String, Object> item) {
        assertEquals("source_quote_answer", item.get("sourceQuoteRef"));
        assertEquals("quoted source content", item.get("matchedChunkText"));
        assertEquals("quoted source content", item.get("evidenceSnippet"));
        assertEquals("quoted source content", item.get("anchorText"));
    }

    private static Map<String, Object> completionPayload(ChatFixture fixture, String status) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.sessionRegistry, org.mockito.Mockito.atLeastOnce())
                .sendJsonToClient(eq("1"), eq("client-1"), payloadCaptor.capture());
        return payloadCaptor.getAllValues().stream()
                .filter(payload -> "completion".equals(payload.get("type")))
                .filter(payload -> status.equals(payload.get("status")))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> paperChoiceItem(String paperHandle, String title) {
        return paperChoiceItem(paperHandle, title, "find_papers_by_identity");
    }

    private static Map<String, Object> paperChoiceItem(String paperHandle, String title, String sourceTool) {
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("kind", "READING_PAPER_CHOICE");
        item.put("sourceTool", sourceTool);
        item.put("paperHandle", paperHandle);
        item.put("title", title);
        item.put("originalFilename", title.toLowerCase().replace(' ', '-') + ".pdf");
        item.put("authors", List.of("Ada Lovelace"));
        item.put("year", 2025);
        item.put("venue", "NeurIPS");
        item.put("matchReasons", List.of("TITLE_CONTAINS"));
        item.put("identityStatus", "AMBIGUOUS");
        item.put("ambiguous", true);
        item.put("catalogTopics", List.of("Agents"));
        item.put("paperTypes", List.of("Benchmark"));
        item.put("facets", Map.of("years", List.of()));
        item.put("paperId", "paper-raw");
        item.put("ordinal", 1);
        item.put("preview", "not evidence");
        item.put("score", 0.9);
        item.put("rank", 1);
        item.put("locationRef", "page_ref_hidden");
        item.put("sourceQuoteRef", "source_quote_hidden");
        return item;
    }

    private static Map<String, Object> capturedReadingEffectiveScope(ChatFixture fixture) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> effectiveScopeCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.readingConversationService).runTurn(
                eq(1L),
                eq("conversation-1"),
                eq("generation-1"),
                anyString(),
                any(),
                any(),
                effectiveScopeCaptor.capture(),
                any()
        );
        return effectiveScopeCaptor.getValue();
    }

    private static ProductReferenceFocus sourceQuoteFocus(String sourceQuoteRef, Integer referenceNumber) {
        return new ProductReferenceFocus(
                List.of(),
                List.of(),
                referenceNumber,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                sourceQuoteRef
        );
    }

    private static ProductReferenceFocus paperHandleFocus(String paperHandle) {
        return new ProductReferenceFocus(
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                "Clicked Paper",
                "clicked.pdf",
                null,
                null,
                null,
                null,
                List.of(),
                paperHandle
        );
    }

    private static ProductReferenceFocus paperIdFocus(String paperId) {
        return new ProductReferenceFocus(
                List.of(paperId),
                List.of("Clicked Paper"),
                null,
                null,
                null,
                null,
                paperId,
                "Clicked Paper",
                "clicked.pdf",
                null,
                null,
                null,
                null
        );
    }

    private static ProductTurnResult completedTurn(String markdown, List<Map<String, Object>> references) {
        return completedTurn(markdown, references, List.of());
    }

    private static ProductTurnResult completedTurn(String markdown,
                                                   List<Map<String, Object>> references,
                                                   List<Map<String, Object>> productStateItems) {
        return new ProductTurnResult(
                markdown,
                new AnswerEnvelope(
                        AnswerType.NON_EVIDENCE,
                        markdown,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        ""
                ),
                references,
                List.of(),
                productStateItems,
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
    }

    private static ChatFixture chatFixture(boolean readingEnabled) {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user:1:current_conversation")).thenReturn("conversation-1");

        ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-1");
        when(sessionRegistry.getClientId(session)).thenReturn("client-1");

        ChatGenerationStateService generationStateService = mock(ChatGenerationStateService.class);
        when(generationStateService.createGeneration(eq("1"), eq("client-1"), eq("conversation-1"), anyString()))
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
                .thenReturn(completedTurn("product ok", List.of()));

        ProductReadingConversationService readingConversationService = mock(ProductReadingConversationService.class);
        when(readingConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(completedTurn("reading ok", List.of()));
        ProductPaperHandleService productPaperHandleService = mock(ProductPaperHandleService.class);

        ProductReadingReactProperties readingProperties = new ProductReadingReactProperties();
        readingProperties.setEnabled(readingEnabled);
        ConversationService conversationService = mock(ConversationService.class);
        ChatHandler handler = new ChatHandler(
                redisTemplate,
                mock(RateLimitService.class),
                conversationService,
                conversationScopeService,
                generationStateService,
                sessionRegistry,
                productConversationService,
                readingConversationService,
                readingProperties,
                productPaperHandleService,
                new ObjectMapper(),
                executor
        );

        return new ChatFixture(
                handler,
                session,
                sessionRegistry,
                conversationService,
                conversationScopeService,
                generationStateService,
                productConversationService,
                readingConversationService,
                productPaperHandleService
        );
    }

    private record ChatFixture(
            ChatHandler handler,
            WebSocketSession session,
            ChatSessionRegistry sessionRegistry,
            ConversationService conversationService,
            ConversationScopeService conversationScopeService,
            ChatGenerationStateService generationStateService,
            ProductConversationService productConversationService,
            ProductReadingConversationService readingConversationService,
            ProductPaperHandleService productPaperHandleService
    ) {
    }
}
