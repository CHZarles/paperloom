package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.exception.QuotaExceededException;
import io.github.chzarles.paperloom.model.ConversationScopeMode;
import io.github.chzarles.paperloom.model.ConversationScopeStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHandlerProductHarnessTest {

    @Test
    void chatPathAlwaysUsesProductReadingConversationService() {
        ChatFixture fixture = chatFixture();
        when(fixture.readingConversationService.runTurn(
                eq(1L),
                eq("conversation-1"),
                eq("generation-1"),
                anyString(),
                any(),
                any(),
                any(),
                any()
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<ToolProgressEvent> progressListener = invocation.getArgument(7, Consumer.class);
            progressListener.accept(new ToolProgressEvent("calling_tool", "get_session_state"));
            return completedTurn("当前 session scope 内有 2 篇 READY 论文。", List.of());
        });

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("现在有多少论文可以检索", null, "conversation-1"), fixture.session);

        verify(fixture.readingConversationService).runTurn(
                eq(1L),
                eq("conversation-1"),
                eq("generation-1"),
                eq("现在有多少论文可以检索"),
                any(),
                any(),
                any(),
                any()
        );
        verify(fixture.sessionRegistry, times(1)).sendJsonToClient(eq("1"), eq("client-1"), argThat(payload ->
                "research_progress".equals(payload.get("type"))
                        && "calling_tool".equals(payload.get("eventType"))
                        && "get_session_state".equals(payload.get("tool"))));
        verify(fixture.conversationService).recordConversation(
                eq(1L),
                eq("现在有多少论文可以检索"),
                eq("当前 session scope 内有 2 篇 READY 论文。"),
                eq("conversation-1"),
                any(),
                any(),
                any(),
                any(),
                any(),
                eq("generation-1"),
                isNull(),
                eq("research")
        );
    }

    @Test
    void explicitConversationIdIsRequiredForChatTurn() {
        ChatFixture fixture = chatFixture("conversation-explicit", "generation-explicit", autoScope());

        fixture.handler.processMessage(
                "1",
                new ChatHandler.ChatRequest("keep this in the visible thread", null, "conversation-explicit"),
                fixture.session
        );

        verify(fixture.conversationService).requireActiveOwnedConversationSession(1L, "conversation-explicit");
        verify(fixture.readingConversationService).runTurn(
                eq(1L),
                eq("conversation-explicit"),
                eq("generation-explicit"),
                anyString(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void invalidExplicitConversationIdFailsClosedWithoutRedisFallback() {
        ChatFixture fixture = chatFixture();
        when(fixture.conversationService.requireActiveOwnedConversationSession(1L, "foreign-conversation"))
                .thenThrow(new CustomException("对话不存在", HttpStatus.NOT_FOUND));

        fixture.handler.processMessage(
                "1",
                new ChatHandler.ChatRequest("do not reroute this", null, "foreign-conversation"),
                fixture.session
        );

        verify(fixture.conversationService).requireActiveOwnedConversationSession(1L, "foreign-conversation");
        verify(fixture.generationStateService, never()).createGeneration(anyString(), anyString(), anyString(), anyString());
        verify(fixture.readingConversationService, never()).runTurn(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void concurrentTurnInTheSameConversationIsRejected() {
        ChatFixture fixture = chatFixture();
        when(fixture.generationStateService.createGeneration("1", "client-1", "conversation-1", "second question"))
                .thenThrow(new ChatGenerationStateService.GenerationInProgressException("conversation-1"));

        fixture.handler.processMessage(
                "1",
                new ChatHandler.ChatRequest("second question", null, "conversation-1"),
                fixture.session
        );

        verify(fixture.readingConversationService, never())
                .submitTurn(any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.sessionRegistry).sendJsonToClient(eq("1"), eq("client-1"), argThat(payload ->
                Integer.valueOf(409).equals(payload.get("code"))
                        && "当前对话还有回答在生成，请先停止或等待完成".equals(payload.get("message"))));
    }

    @Test
    void silentGenerationRenewsItsConversationLeaseOnSchedule() {
        ChatFixture fixture = chatFixture();
        CompletableFuture<ProductTurnResult> pendingTurn = new CompletableFuture<>();
        when(fixture.readingConversationService.submitTurn(
                eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(pendingTurn);
        when(fixture.generationStateService.renewConversationLease("generation-1")).thenReturn(true);

        fixture.handler.processMessage(
                "1",
                new ChatHandler.ChatRequest("question", null, "conversation-1"),
                fixture.session
        );

        ArgumentCaptor<Runnable> renewalCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.leaseScheduler).scheduleAtFixedRate(
                renewalCaptor.capture(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        renewalCaptor.getValue().run();
        verify(fixture.generationStateService).renewConversationLease("generation-1");

        pendingTurn.complete(completedTurn("answer", List.of()));
        verify(fixture.leaseRenewal).cancel(false);
    }

    @Test
    void leaseLossCancelsTheOldGeneration() {
        ChatFixture fixture = chatFixture();
        CompletableFuture<ProductTurnResult> pendingTurn = new CompletableFuture<>();
        when(fixture.readingConversationService.submitTurn(
                eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(pendingTurn);
        when(fixture.generationStateService.renewConversationLease("generation-1")).thenReturn(false);
        when(fixture.generationStateService.getGeneration("generation-1")).thenReturn(Optional.empty());

        fixture.handler.processMessage(
                "1",
                new ChatHandler.ChatRequest("question", null, "conversation-1"),
                fixture.session
        );

        ArgumentCaptor<Runnable> renewalCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.leaseScheduler).scheduleAtFixedRate(
                renewalCaptor.capture(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        renewalCaptor.getValue().run();

        verify(fixture.readingConversationService).cancelTurn("generation-1");
        verify(fixture.generationStateService).markFailed(
                "generation-1", "Generation lost its conversation lease");
        verify(fixture.leaseRenewal).cancel(false);
    }

    @Test
    void retryGenerationCreatesNewGenerationAndReusesAnswerSlot() {
        ChatFixture fixture = chatFixture("conversation-1", "generation-retry", sourceSetScope("paper-1"));
        ConversationRetryContext retryContext = new ConversationRetryContext(
                "USER_UNSATISFIED",
                "generation-parent",
                12L,
                12L,
                2,
                "user_unsatisfied",
                "old answer",
                List.of("source_quote_1"),
                "conversation-1",
                "Question",
                Map.of("paperIds", List.of("paper-1"))
        );
        when(fixture.generationStateService.getActiveGenerationForUserAndClient("1", "client-1"))
                .thenReturn(Optional.empty());
        when(fixture.generationStateService.getGenerationForUser("generation-parent", "1"))
                .thenReturn(Optional.of(new ChatGenerationStateService.GenerationSnapshot(
                        "generation-parent",
                        "1",
                        "conversation-1",
                        "Question",
                        ChatGenerationStateService.GenerationStatus.COMPLETED,
                        "old answer",
                        "2026-06-29T12:00:00",
                        "2026-06-29T12:00:01",
                        null,
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        12L
                )));
        when(fixture.conversationService.prepareUserRetry(
                1L,
                "generation-parent",
                12L,
                "user_unsatisfied",
                3
        )).thenReturn(Optional.of(retryContext));
        when(fixture.generationStateService.createRetryGeneration(
                "1",
                "client-1",
                "conversation-1",
                "Question",
                retryContext
        )).thenReturn(new ChatGenerationStateService.GenerationSnapshot(
                "generation-retry",
                "1",
                "conversation-1",
                "Question",
                ChatGenerationStateService.GenerationStatus.STREAMING,
                "",
                "2026-06-29T12:00:02",
                "2026-06-29T12:00:02",
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null
        ));
        when(fixture.readingConversationService.submitRetryTurn(
                eq(1L),
                eq("conversation-1"),
                eq("generation-retry"),
                eq("Question"),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(CompletableFuture.completedFuture(completedTurn("new answer", List.of())));
        when(fixture.conversationService.recordConversation(
                eq(1L),
                eq("Question"),
                eq("new answer"),
                eq("conversation-1"),
                any(),
                any(),
                any(),
                any(),
                any(),
                eq("generation-retry"),
                eq(retryContext),
                eq("research")
        )).thenReturn(99L);

        Map<String, Object> started = fixture.handler.retryGeneration(
                "1",
                "generation-parent",
                "client-1",
                "user_unsatisfied"
        );

        assertEquals("generation-retry", started.get("generationId"));
        assertEquals(12L, started.get("answerSlotId"));
        assertEquals(2, started.get("answerRevision"));
        assertEquals(true, started.get("replaceMessage"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> retryPayloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.readingConversationService).submitRetryTurn(
                eq(1L),
                eq("conversation-1"),
                eq("generation-retry"),
                eq("Question"),
                argThat(scope -> scope.paperIds().equals(List.of("paper-1"))),
                any(),
                any(),
                retryPayloadCaptor.capture(),
                any()
        );
        assertEquals("generation-parent", retryPayloadCaptor.getValue().get("retry_of_generation_id"));
        assertEquals(12L, retryPayloadCaptor.getValue().get("answer_slot_id"));
        verify(fixture.sessionRegistry).sendJsonToClient(eq("1"), eq("client-1"), argThat(payload ->
                "start".equals(payload.get("type"))
                        && "generation-retry".equals(payload.get("generationId"))
                        && Boolean.TRUE.equals(payload.get("replaceMessage"))
                        && Long.valueOf(12).equals(payload.get("answerSlotId"))
                        && Integer.valueOf(2).equals(payload.get("answerRevision"))));
        verify(fixture.sessionRegistry).sendJsonToClient(eq("1"), eq("client-1"), argThat(payload ->
                "completion".equals(payload.get("type"))
                        && "generation-retry".equals(payload.get("generationId"))
                        && Long.valueOf(99).equals(payload.get("conversationRecordId"))
                        && Long.valueOf(12).equals(payload.get("answerSlotId"))));
    }

    @Test
    void retryGenerationFallsBackToMySqlWhenRedisSnapshotExpired() {
        ChatFixture fixture = chatFixture("conversation-1", "generation-retry", sourceSetScope("paper-1"));
        ConversationRetryContext retryContext = new ConversationRetryContext(
                "USER_UNSATISFIED",
                "generation-expired",
                12L,
                12L,
                2,
                "user_unsatisfied",
                "old answer",
                List.of(),
                "conversation-1",
                "Question",
                Map.of("paperIds", List.of("paper-1"))
        );
        when(fixture.generationStateService.getActiveGenerationForUserAndClient("1", "client-1"))
                .thenReturn(Optional.empty());
        when(fixture.generationStateService.getGenerationForUser("generation-expired", "1"))
                .thenReturn(Optional.empty());
        when(fixture.conversationService.prepareUserRetry(
                1L,
                "generation-expired",
                null,
                "user_unsatisfied",
                3
        )).thenReturn(Optional.of(retryContext));
        when(fixture.generationStateService.createRetryGeneration(
                "1",
                "client-1",
                "conversation-1",
                "Question",
                retryContext
        )).thenReturn(new ChatGenerationStateService.GenerationSnapshot(
                "generation-retry",
                "1",
                "conversation-1",
                "Question",
                ChatGenerationStateService.GenerationStatus.STREAMING,
                "",
                "2026-06-29T12:00:02",
                "2026-06-29T12:00:02",
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null
        ));
        when(fixture.readingConversationService.submitRetryTurn(
                eq(1L),
                eq("conversation-1"),
                eq("generation-retry"),
                eq("Question"),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(CompletableFuture.completedFuture(completedTurn("new answer", List.of())));

        fixture.handler.retryGeneration("1", "generation-expired", "client-1", "user_unsatisfied");

        verify(fixture.conversationService).prepareUserRetry(
                1L,
                "generation-expired",
                null,
                "user_unsatisfied",
                3
        );
        verify(fixture.conversationService, never()).prepareUserRetry(
                1L,
                "generation-expired",
                12L,
                "user_unsatisfied",
                3
        );
    }

    @Test
    void rawPaperIdReferenceFocusDoesNotOverrideProductSessionScope() {
        ChatFixture fixture = chatFixture();
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

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个来源", forgedFocus, "conversation-1"), fixture.session);

        ArgumentCaptor<SourceScope> scopeCaptor = ArgumentCaptor.forClass(SourceScope.class);
        verify(fixture.readingConversationService).runTurn(
                eq(1L),
                eq("conversation-1"),
                eq("generation-1"),
                anyString(),
                scopeCaptor.capture(),
                any(),
                any(),
                any()
        );
        assertEquals(List.of(), scopeCaptor.getValue().paperIds());
    }

    @Test
    void productChatIgnoresClientRetrievalBudgetProfile() {
        ConversationScopeService.EffectiveConversationScope scope =
                new ConversationScopeService.EffectiveConversationScope(
                        ConversationScopeMode.SOURCE_SET_SNAPSHOT,
                        ConversationScopeStatus.READY,
                        true,
                        "Selected papers",
                        List.of("paper-1"),
                        Map.of()
                );
        ChatFixture fixture = chatFixture("conversation-1", "generation-1", scope);

        fixture.handler.processMessage(
                "1",
                new ChatHandler.ChatRequest("LoRA 的方法是什么", null, RetrievalBudgetProfile.DEEP_AUDIT, "conversation-1"),
                fixture.session
        );

        ArgumentCaptor<SourceScope> scopeCaptor = ArgumentCaptor.forClass(SourceScope.class);
        verify(fixture.readingConversationService).runTurn(
                eq(1L),
                eq("conversation-1"),
                eq("generation-1"),
                anyString(),
                scopeCaptor.capture(),
                any(),
                any(),
                any()
        );
        assertEquals(List.of("paper-1"), scopeCaptor.getValue().paperIds());
        assertEquals(RetrievalBudgetProfile.INTERACTIVE, scopeCaptor.getValue().retrievalBudgetProfile());
    }

    @Test
    void productChatReturnsStructuredQuotaErrorInsteadOfGenericAiUnavailable() {
        ChatFixture fixture = chatFixture();
        when(fixture.readingConversationService.runTurn(
                eq(1L),
                eq("conversation-1"),
                eq("generation-1"),
                anyString(),
                any(),
                any(),
                any(),
                any()
        )).thenThrow(new QuotaExceededException("LLM Token 额度已达上限", 42));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("现在有多少论文可以检索", null, "conversation-1"), fixture.session);

        verify(fixture.sessionRegistry).sendJsonToClient(eq("1"), eq("client-1"), argThat(payload ->
                "error".equals(payload.get("type"))
                        && Integer.valueOf(429).equals(payload.get("code"))
                        && "LLM Token 额度已达上限".equals(payload.get("message"))
                        && Long.valueOf(42).equals(payload.get("retryAfterSeconds"))
                        && !payload.containsKey("error")
        ));
    }

    @Test
    void wrappedProductChatQuotaErrorStillReturnsStructuredMessage() {
        ChatFixture fixture = chatFixture();
        when(fixture.readingConversationService.runTurn(
                eq(1L),
                eq("conversation-1"),
                eq("generation-1"),
                anyString(),
                any(),
                any(),
                any(),
                any()
        )).thenThrow(new RuntimeException(
                "ReAct 模型回合调用失败",
                new QuotaExceededException("LLM Token 余额不足，请联系管理员补充额度", 60)
        ));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("现在有多少论文可以检索", null, "conversation-1"), fixture.session);

        verify(fixture.sessionRegistry).sendJsonToClient(eq("1"), eq("client-1"), argThat(payload ->
                "error".equals(payload.get("type"))
                        && Integer.valueOf(429).equals(payload.get("code"))
                        && "LLM Token 余额不足，请联系管理员补充额度".equals(payload.get("message"))
                        && Long.valueOf(60).equals(payload.get("retryAfterSeconds"))
                        && !payload.containsKey("error")
        ));
    }

    @Test
    void structuredPaperHandleFocusReachesReadingEffectiveScope() {
        ChatFixture fixture = chatFixture();
        ProductReferenceFocus focus = paperHandleFocus("paper_handle_clicked");

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看这篇论文", focus, "conversation-1"), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertEquals(List.of("paper_handle_clicked"), effectiveScope.get("clickedPaperHandles"));
        verify(fixture.productPaperHandleService, never()).handleForPaperId(anyString());
    }

    @Test
    void structuredPaperIdFocusConvertsToReadingPaperHandleAfterScopeValidation() {
        ChatFixture fixture = chatFixture();
        ProductReferenceFocus focus = paperIdFocus("paper-1");
        when(fixture.productPaperHandleService.handleForPaperId("paper-1")).thenReturn("paper_handle_converted");

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看这篇论文", focus, "conversation-1"), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertEquals(List.of("paper_handle_converted"), effectiveScope.get("clickedPaperHandles"));
        assertFalse(effectiveScope.toString().contains("paper-1"));
        InOrder inOrder = inOrder(fixture.conversationScopeService, fixture.productPaperHandleService);
        inOrder.verify(fixture.conversationScopeService, times(2)).assertReferenceFocusWithinScope(any(), any());
        inOrder.verify(fixture.productPaperHandleService).handleForPaperId("paper-1");
    }

    @Test
    void typedPaperHandleWithoutStructuredFocusDoesNotReachReadingEffectiveScope() {
        ChatFixture fixture = chatFixture();

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("请看 paper_handle_clicked", null, "conversation-1"), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertFalse(effectiveScope.containsKey("clickedPaperHandles"));
        verify(fixture.productPaperHandleService, never()).handleForPaperId(anyString());
    }

    @Test
    void outOfScopeStructuredPaperIdIsRejectedBeforePaperHandleConversion() {
        ChatFixture fixture = chatFixture();
        ProductReferenceFocus focus = paperIdFocus("outside-paper");
        doThrow(new CustomException("Reference focus is outside the conversation source scope", HttpStatus.FORBIDDEN))
                .when(fixture.conversationScopeService)
                .assertReferenceFocusWithinScope(any(), argThat(scope -> "outside-paper".equals(scope.paperId())));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看这篇论文", focus, "conversation-1"), fixture.session);

        verify(fixture.generationStateService, never()).createGeneration(anyString(), anyString(), anyString(), anyString());
        verify(fixture.productPaperHandleService, never()).handleForPaperId(anyString());
        verify(fixture.readingConversationService, never()).runTurn(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void structuredSourceQuoteFocusReachesReadingEffectiveScope() {
        ChatFixture fixture = chatFixture();
        ProductReferenceFocus focus = sourceQuoteFocus("source_quote_clicked", null);

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个引用", focus, "conversation-1"), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertEquals(List.of("source_quote_clicked"), effectiveScope.get("clickedSourceQuoteRefs"));
        assertEquals("TRACE_SOURCE_QUOTE", effectiveScope.get("readingAction"));
    }

    @Test
    void resolvedReferenceNumberSourceQuoteReachesReadingEffectiveScope() {
        ChatFixture fixture = chatFixture();
        ProductReferenceFocus focus = sourceQuoteFocus(null, 1);
        when(fixture.conversationService.findLatestReferenceDetail(1L, "conversation-1", 1))
                .thenReturn(java.util.Optional.of(Map.of(
                        "paperId", "paper-1",
                        "paperTitle", "Clicked Paper",
                        "sourceQuoteRef", "source_quote_resolved",
                        "matchedChunkText", "resolved quote"
                )));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个引用", focus, "conversation-1"), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertEquals(List.of("source_quote_resolved"), effectiveScope.get("clickedSourceQuoteRefs"));
        assertEquals("TRACE_SOURCE_QUOTE", effectiveScope.get("readingAction"));
    }

    @Test
    void structuredLocationFocusReachesReadingEffectiveScopeAsReadLocationAction() {
        ChatFixture fixture = chatFixture();
        ProductReferenceFocus focus = locationFocus("page_ref_clicked");

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("读取这个位置", focus, "conversation-1"), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertEquals(List.of("page_ref_clicked"), effectiveScope.get("clickedLocationRefs"));
        assertEquals("READ_LOCATION", effectiveScope.get("readingAction"));
    }

    @Test
    void typedCitationDoesNotBecomeClickedSourceQuoteOnReadingPath() {
        ChatFixture fixture = chatFixture();

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释 [1]", null, "conversation-1"), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertFalse(effectiveScope.containsKey("clickedSourceQuoteRefs"));
        verify(fixture.conversationService, never()).findLatestReferenceDetail(any(), any(), any());
    }

    @Test
    void sourceQuoteFocusWithReferenceNumberDoesNotRequirePaperResolution() {
        ChatFixture fixture = chatFixture();
        ProductReferenceFocus focus = sourceQuoteFocus("source_quote_clicked", 1);

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个引用", focus, "conversation-1"), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertEquals(List.of("source_quote_clicked"), effectiveScope.get("clickedSourceQuoteRefs"));
        verify(fixture.conversationService, never()).findLatestReferenceDetail(any(), any(), any());
        verify(fixture.conversationService, never()).findReferenceDetail(any(), any(), any());
    }

    @Test
    void readingSourceQuoteReferencesAreStoredInGenerationStateAndConversationHistory() {
        ChatFixture fixture = chatFixture();
        when(fixture.readingConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(completedTurn(
                        "这段引用说明了方法细节 [1]",
                        List.of(Map.of(
                                "referenceNumber", 1,
                                "sourceQuoteRef", "source_quote_answer",
                                "content", "quoted source content",
                                "paperHandle", "paper_handle_answer",
                                "paperTitle", "LoRA",
                                "sourceSpanJson", "{\"bbox\":{\"pageNumber\":3}}",
                                "visualRegions", List.of(Map.of(
                                        "pageNumber", 3,
                                        "left", 100,
                                        "top", 120,
                                        "right", 300,
                                        "bottom", 180,
                                        "unit", "mineru_1000",
                                        "coordinateSystem", "top_left_1000"
                                )),
                                "retrievalRoute", "PRODUCT_READING"
                        ))
                ));
        when(fixture.productPaperHandleService.resolvePaperHandle("paper_handle_answer"))
                .thenReturn(Optional.of("paper-id-answer"));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个引用", null, "conversation-1"), fixture.session);

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
                persistedScopeCaptor.capture(),
                any(),
                any(),
                any(),
                eq("generation-1"),
                isNull(),
                eq("research")
        );
        assertSourceQuoteMapping(persistedReferencesCaptor.getValue().get("1"));
        assertEquals("AUTO_LIBRARY", persistedScopeCaptor.getValue().get("scopeMode"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Map<String, Object>>> completedCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.generationStateService).markCompleted(eq("generation-1"), completedCaptor.capture());
        assertSourceQuoteMapping(completedCaptor.getValue().get("1"));
    }

    @Test
    void readingCompletionSendsConversationRecordIdForDurableCitationDetailClick() {
        ChatFixture fixture = chatFixture();
        when(fixture.conversationService.recordConversation(
                eq(1L),
                eq("解释这个引用"),
                anyString(),
                eq("conversation-1"),
                any(),
                any(),
                any(),
                any(),
                any(),
                eq("generation-1"),
                isNull(),
                eq("research")
        )).thenReturn(9001L);
        when(fixture.readingConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(completedTurn(
                        "这段引用说明了方法细节 [1]",
                        List.of(Map.of(
                                "referenceNumber", 1,
                                "sourceQuoteRef", "source_quote_answer",
                                "content", "quoted source content",
                                "paperId", "paper-id-answer",
                                "paperTitle", "LoRA",
                                "retrievalRoute", "PRODUCT_READING"
                        ))
                ));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个引用", null, "conversation-1"), fixture.session);

        Map<String, Object> completion = completionPayload(fixture, "finished");
        assertEquals(9001L, completion.get("conversationRecordId"));
        assertEquals("research", completion.get("answerMode"));
        verify(fixture.generationStateService).updateConversationRecordId("generation-1", 9001L);
    }

    @Test
    void readingCompletionSendsSanitizedProductStateItems() {
        ChatFixture fixture = chatFixture();
        List<Map<String, Object>> productStateItems = new java.util.ArrayList<>();
        productStateItems.add(paperChoiceItem("paper_handle_000", "First"));
        productStateItems.add(paperChoiceItem("paper_handle_000", "Duplicate"));
        productStateItems.add(Map.of(
                "kind", "READING_PAPER_CHOICE",
                "sourceTool", "find_papers_by_identity",
                "paperHandle", "not_a_handle",
                "title", "Invalid"
        ));
        for (int index = 1; index <= 12; index++) {
            productStateItems.add(paperChoiceItem("paper_handle_" + index, "Paper " + index));
        }
        when(fixture.readingConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(completedTurn("请选择论文", List.of(), productStateItems));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看 Ada 的论文", null, "conversation-1"), fixture.session);

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
        for (Map<String, Object> item : payloadItems) {
            assertEquals("READING_PAPER_CHOICE", item.get("kind"));
            assertFalse(item.containsKey("paperId"));
            assertFalse(item.containsKey("ordinal"));
            assertFalse(item.containsKey("preview"));
            assertFalse(item.containsKey("score"));
            assertFalse(item.containsKey("rank"));
            assertFalse(item.containsKey("locationRef"));
            assertFalse(item.containsKey("sourceQuoteRef"));
        }
    }

    @Test
    void readingCompletionSendsListAndSearchPaperChoiceProductStateItems() {
        ChatFixture fixture = chatFixture();
        List<Map<String, Object>> productStateItems = List.of(
                paperChoiceItem("paper_handle_list", "Browse Paper", "list_papers"),
                paperChoiceItem("paper_handle_search", "Search Paper", "search_paper_candidates")
        );
        when(fixture.readingConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(completedTurn("请选择论文", List.of(), productStateItems));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("找论文", null, "conversation-1"), fixture.session);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payloadItems =
                (List<Map<String, Object>>) completionPayload(fixture, "finished").get("productStateItems");
        assertEquals(2, payloadItems.size());
        assertEquals("list_papers", payloadItems.get(0).get("sourceTool"));
        assertEquals("paper_handle_list", payloadItems.get(0).get("paperHandle"));
        assertEquals("search_paper_candidates", payloadItems.get(1).get("sourceTool"));
        assertEquals("paper_handle_search", payloadItems.get(1).get("paperHandle"));
    }

    @Test
    void readingCompletionRejectsUnsupportedPaperChoiceSourceTool() {
        ChatFixture fixture = chatFixture();
        when(fixture.readingConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(completedTurn("没有可选论文", List.of(), List.of(
                        paperChoiceItem("paper_handle_outline", "Outline Paper", "get_paper_outline")
                )));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看大纲", null, "conversation-1"), fixture.session);

        assertFalse(completionPayload(fixture, "finished").containsKey("productStateItems"));
    }

    @Test
    void failedReadingCompletionOmitsProductStateItems() {
        ChatFixture fixture = chatFixture();
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

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看论文", null, "conversation-1"), fixture.session);

        assertFalse(completionPayload(fixture, "failed").containsKey("productStateItems"));
    }

    @Test
    void incompletePreciseReadingCompletionFinishesInsteadOfServiceError() {
        ChatFixture fixture = chatFixture();
        String answer = "I understand your goal as: inspect the current paper.\n\n"
                + "Short answer: A validated answer is not ready yet.\n\n"
                + "Start here: the current reading target.\n\n"
                + "How to verify: choose a concrete passage.\n\n"
                + "Not verified yet: no quoted passage was validated.\n\n"
                + "Next step: open a readable location.";
        when(fixture.readingConversationService.runTurn(eq(1L), eq("conversation-1"), eq("generation-1"), anyString(), any(), any(), any(), any()))
                .thenReturn(new ProductTurnResult(
                        answer,
                        new AnswerEnvelope(
                                AnswerType.INSUFFICIENT_EVIDENCE,
                                answer,
                                List.of(),
                                List.of(),
                                List.of("A validated reading observation is required before answering."),
                                List.of(),
                                List.of("validated_final_answer"),
                                ProductStopReason.TOOL_FAILED.name()
                        ),
                        List.of(),
                        List.of(),
                        ProductStopReason.TOOL_FAILED,
                        ProductResultStatus.INCOMPLETE_PRECISE
                ));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看论文", null, "conversation-1"), fixture.session);

        assertTrue(completionPayload(fixture, "finished").containsKey("diagnostics"));
        verify(fixture.generationStateService, never()).markFailed(eq("generation-1"), anyString());
    }

    private static void assertSourceQuoteMapping(Map<String, Object> item) {
        assertEquals("source_quote_answer", item.get("sourceQuoteRef"));
        assertEquals("paper-id-answer", item.get("paperId"));
        assertEquals("quoted source content", item.get("matchedChunkText"));
        assertEquals("quoted source content", item.get("evidenceSnippet"));
        assertEquals("quoted source content", item.get("anchorText"));
        assertEquals("{\"bbox\":{\"pageNumber\":3}}", item.get("sourceSpanJson"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> visualRegions = (List<Map<String, Object>>) item.get("visualRegions");
        assertEquals(1, visualRegions.size());
        assertEquals("mineru_1000", visualRegions.get(0).get("unit"));
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
                eq(fixture.conversationId),
                eq(fixture.generationId),
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

    private static ProductReferenceFocus locationFocus(String locationRef) {
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
                List.of("paper_handle_clicked"),
                "paper_handle_clicked",
                "READ_LOCATION",
                locationRef
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
                null,
                null,
                null,
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED,
                Map.of(),
                "research"
        );
    }

    private static ChatFixture chatFixture() {
        return chatFixture("conversation-1", "generation-1", autoScope());
    }

    private static ChatFixture chatFixture(String conversationId,
                                           String generationId,
                                           ConversationScopeService.EffectiveConversationScope scope) {
        ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-1");
        when(sessionRegistry.getClientId(session)).thenReturn("client-1");

        ChatGenerationStateService generationStateService = mock(ChatGenerationStateService.class);
        when(generationStateService.renewConversationLease(generationId)).thenReturn(true);
        when(generationStateService.createGeneration(eq("1"), eq("client-1"), eq(conversationId), anyString()))
                .thenReturn(new ChatGenerationStateService.GenerationSnapshot(
                        generationId,
                        "1",
                        conversationId,
                        "question",
                        ChatGenerationStateService.GenerationStatus.STREAMING,
                        "",
                        "2026-06-29T12:00:00",
                        "2026-06-29T12:00:00",
                        null,
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        null
                ));

        ConversationScopeService conversationScopeService = mock(ConversationScopeService.class);
        when(conversationScopeService.resolveForChat(1L, conversationId)).thenReturn(scope);
        when(conversationScopeService.lockForFirstMessage(1L, conversationId)).thenReturn(scope);
        when(conversationScopeService.authorizedPaperIdsForHarness(1L, scope)).thenReturn(scope.paperIds());

        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        ProductReadingConversationService readingConversationService = mock(ProductReadingConversationService.class);
        when(readingConversationService.runTurn(eq(1L), eq(conversationId), eq(generationId), anyString(), any(), any(), any(), any()))
                .thenReturn(completedTurn("reading ok", List.of()));
        when(readingConversationService.submitTurn(
                eq(1L), eq(conversationId), eq(generationId), anyString(), any(), any(), any(), any()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(readingConversationService.runTurn(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        invocation.getArgument(4),
                        invocation.getArgument(5),
                        invocation.getArgument(6),
                        (Consumer<ToolProgressEvent>) event -> {
                            @SuppressWarnings("unchecked")
                            Consumer<Map<String, Object>> progressListener = invocation.getArgument(7, Consumer.class);
                            progressListener.accept(Map.of(
                                    "type", event.type(),
                                    "tool", event.toolName(),
                                    "status", "executing"
                            ));
                        }
                )));
        ProductPaperHandleService productPaperHandleService = mock(ProductPaperHandleService.class);
        ConversationService conversationService = mock(ConversationService.class);
        ScheduledExecutorService leaseScheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> leaseRenewal = mock(ScheduledFuture.class);
        doReturn(leaseRenewal).when(leaseScheduler)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        ChatHandler handler = new ChatHandler(
                mock(UsageQuotaService.class),
                conversationService,
                conversationScopeService,
                generationStateService,
                sessionRegistry,
                readingConversationService,
                productPaperHandleService,
                new ObjectMapper(),
                3,
                executor,
                leaseScheduler
        );

        return new ChatFixture(
                handler,
                session,
                sessionRegistry,
                conversationService,
                conversationScopeService,
                generationStateService,
                readingConversationService,
                productPaperHandleService,
                leaseScheduler,
                leaseRenewal,
                conversationId,
                generationId
        );
    }

    private static ConversationScopeService.EffectiveConversationScope autoScope() {
        return new ConversationScopeService.EffectiveConversationScope(
                ConversationScopeMode.AUTO_LIBRARY,
                ConversationScopeStatus.READY,
                true,
                "All readable papers",
                List.of(),
                Map.of()
        );
    }

    private static ConversationScopeService.EffectiveConversationScope sourceSetScope(String paperId) {
        return new ConversationScopeService.EffectiveConversationScope(
                ConversationScopeMode.SOURCE_SET_SNAPSHOT,
                ConversationScopeStatus.READY,
                true,
                "Selected papers",
                List.of(paperId),
                Map.of()
        );
    }

    private record ChatFixture(
            ChatHandler handler,
            WebSocketSession session,
            ChatSessionRegistry sessionRegistry,
            ConversationService conversationService,
            ConversationScopeService conversationScopeService,
            ChatGenerationStateService generationStateService,
            ProductReadingConversationService readingConversationService,
            ProductPaperHandleService productPaperHandleService,
            ScheduledExecutorService leaseScheduler,
            ScheduledFuture<?> leaseRenewal,
            String conversationId,
            String generationId
    ) {
    }
}
