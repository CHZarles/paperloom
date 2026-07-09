package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.exception.RateLimitExceededException;
import com.yizhaoqi.smartpai.model.ConversationScopeMode;
import com.yizhaoqi.smartpai.model.ConversationScopeStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("现在有多少论文可以检索", null), fixture.session);

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
                "calling_tool".equals(payload.get("type")) && "get_session_state".equals(payload.get("toolName"))));
        verify(fixture.conversationService).recordConversation(
                eq(1L),
                eq("现在有多少论文可以检索"),
                eq("当前 session scope 内有 2 篇 READY 论文。"),
                eq("conversation-1"),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void explicitConversationIdOverridesRedisCurrentConversationForChatTurn() {
        ChatFixture fixture = chatFixture("conversation-explicit", "generation-explicit", autoScope());

        fixture.handler.processMessage(
                "1",
                new ChatHandler.ChatRequest("keep this in the visible thread", null, "conversation-explicit"),
                fixture.session
        );

        verify(fixture.conversationService).requireActiveOwnedConversationSession(1L, "conversation-explicit");
        verify(fixture.valueOperations, never()).get("user:1:current_conversation");
        verify(fixture.conversationService, never()).ensureConversationSession(eq(1L), eq("conversation-redis"), anyString());
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
        verify(fixture.valueOperations, never()).get("user:1:current_conversation");
        verify(fixture.generationStateService, never()).createGeneration(anyString(), anyString(), anyString(), anyString());
        verify(fixture.readingConversationService, never()).runTurn(any(), any(), any(), any(), any(), any(), any(), any());
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

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个来源", forgedFocus), fixture.session);

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
                new ChatHandler.ChatRequest("LoRA 的方法是什么", null, RetrievalBudgetProfile.DEEP_AUDIT),
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
    void productChatReturnsStructuredRateLimitErrorInsteadOfGenericAiUnavailable() {
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
        )).thenThrow(new RateLimitExceededException("LLM全网分钟Token预算已达上限", 42));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("现在有多少论文可以检索", null), fixture.session);

        verify(fixture.sessionRegistry).sendJsonToClient(eq("1"), eq("client-1"), argThat(payload ->
                "error".equals(payload.get("type"))
                        && Integer.valueOf(429).equals(payload.get("code"))
                        && "LLM全网分钟Token预算已达上限".equals(payload.get("message"))
                        && Long.valueOf(42).equals(payload.get("retryAfterSeconds"))
                        && !payload.containsKey("error")
        ));
    }

    @Test
    void wrappedProductChatRateLimitErrorStillReturnsStructuredMessage() {
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
                new RateLimitExceededException("LLM Token 余额不足，请联系管理员补充额度", 60)
        ));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("现在有多少论文可以检索", null), fixture.session);

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

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看这篇论文", focus), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertEquals(List.of("paper_handle_clicked"), effectiveScope.get("clickedPaperHandles"));
        verify(fixture.productPaperHandleService, never()).handleForPaperId(anyString());
    }

    @Test
    void structuredPaperIdFocusConvertsToReadingPaperHandleAfterScopeValidation() {
        ChatFixture fixture = chatFixture();
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
        ChatFixture fixture = chatFixture();

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("请看 paper_handle_clicked", null), fixture.session);

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

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看这篇论文", focus), fixture.session);

        verify(fixture.generationStateService, never()).createGeneration(anyString(), anyString(), anyString(), anyString());
        verify(fixture.productPaperHandleService, never()).handleForPaperId(anyString());
        verify(fixture.readingConversationService, never()).runTurn(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void structuredSourceQuoteFocusReachesReadingEffectiveScope() {
        ChatFixture fixture = chatFixture();
        ProductReferenceFocus focus = sourceQuoteFocus("source_quote_clicked", null);

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个引用", focus), fixture.session);

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

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个引用", focus), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertEquals(List.of("source_quote_resolved"), effectiveScope.get("clickedSourceQuoteRefs"));
        assertEquals("TRACE_SOURCE_QUOTE", effectiveScope.get("readingAction"));
    }

    @Test
    void structuredLocationFocusReachesReadingEffectiveScopeAsReadLocationAction() {
        ChatFixture fixture = chatFixture();
        ProductReferenceFocus focus = locationFocus("page_ref_clicked");

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("读取这个位置", focus), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertEquals(List.of("page_ref_clicked"), effectiveScope.get("clickedLocationRefs"));
        assertEquals("READ_LOCATION", effectiveScope.get("readingAction"));
    }

    @Test
    void typedCitationDoesNotBecomeClickedSourceQuoteOnReadingPath() {
        ChatFixture fixture = chatFixture();

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释 [1]", null), fixture.session);

        Map<String, Object> effectiveScope = capturedReadingEffectiveScope(fixture);
        assertFalse(effectiveScope.containsKey("clickedSourceQuoteRefs"));
        verify(fixture.conversationService, never()).findLatestReferenceDetail(any(), any(), any());
    }

    @Test
    void sourceQuoteFocusWithReferenceNumberDoesNotRequireLegacyPaperResolution() {
        ChatFixture fixture = chatFixture();
        ProductReferenceFocus focus = sourceQuoteFocus("source_quote_clicked", 1);

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个引用", focus), fixture.session);

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
                                "retrievalRoute", "PRODUCT_READING"
                        ))
                ));
        when(fixture.productPaperHandleService.resolvePaperHandle("paper_handle_answer"))
                .thenReturn(Optional.of("paper-id-answer"));

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
                persistedScopeCaptor.capture(),
                any(),
                any()
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
                any()
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

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("解释这个引用", null), fixture.session);

        Map<String, Object> completion = completionPayload(fixture, "finished");
        assertEquals(9001L, completion.get("conversationRecordId"));
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

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("找论文", null), fixture.session);

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

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看大纲", null), fixture.session);

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

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看论文", null), fixture.session);

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

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("看论文", null), fixture.session);

        assertTrue(completionPayload(fixture, "finished").containsKey("diagnostics"));
        verify(fixture.generationStateService, never()).markFailed(eq("generation-1"), anyString());
    }

    private static void assertSourceQuoteMapping(Map<String, Object> item) {
        assertEquals("source_quote_answer", item.get("sourceQuoteRef"));
        assertEquals("paper-id-answer", item.get("paperId"));
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
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
    }

    private static ChatFixture chatFixture() {
        return chatFixture("conversation-1", "generation-1", autoScope());
    }

    private static ChatFixture chatFixture(String conversationId,
                                           String generationId,
                                           ConversationScopeService.EffectiveConversationScope scope) {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user:1:current_conversation")).thenReturn("conversation-1");

        ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-1");
        when(sessionRegistry.getClientId(session)).thenReturn("client-1");

        ChatGenerationStateService generationStateService = mock(ChatGenerationStateService.class);
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

        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        ProductReadingConversationService readingConversationService = mock(ProductReadingConversationService.class);
        when(readingConversationService.runTurn(eq(1L), eq(conversationId), eq(generationId), anyString(), any(), any(), any(), any()))
                .thenReturn(completedTurn("reading ok", List.of()));
        ProductPaperHandleService productPaperHandleService = mock(ProductPaperHandleService.class);
        ConversationService conversationService = mock(ConversationService.class);
        ChatHandler handler = new ChatHandler(
                redisTemplate,
                mock(RateLimitService.class),
                conversationService,
                conversationScopeService,
                generationStateService,
                sessionRegistry,
                readingConversationService,
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
                readingConversationService,
                productPaperHandleService,
                valueOperations,
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

    private record ChatFixture(
            ChatHandler handler,
            WebSocketSession session,
            ChatSessionRegistry sessionRegistry,
            ConversationService conversationService,
            ConversationScopeService conversationScopeService,
            ChatGenerationStateService generationStateService,
            ProductReadingConversationService readingConversationService,
            ProductPaperHandleService productPaperHandleService,
            ValueOperations<String, String> valueOperations,
            String conversationId,
            String generationId
    ) {
    }
}
