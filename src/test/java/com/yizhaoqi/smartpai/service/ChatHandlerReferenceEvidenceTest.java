package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.ConversationScopeMode;
import com.yizhaoqi.smartpai.model.ConversationScopeStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHandlerReferenceEvidenceTest {

    private static final String LEGACY_SOURCE_TYPE = "EVAL" + "_IMPORT";
    private static final String LEGACY_STRUCTURED_FIELD = "structured" + "Import";
    private static final String LEGACY_EVAL_FIELD = "eval" + "Import";
    private static final String LEGACY_ASSET_WARNING = "structured_" + "import_text_only";

    @Test
    void firstAcceptedMessageLocksAutoLibraryScope() {
        ChatHandlerFixture fixture = chatHandlerFixture();
        when(fixture.conversationScopeService.lockForFirstMessage(1L, "conversation-1"))
                .thenReturn(new ConversationScopeService.EffectiveConversationScope(
                        ConversationScopeMode.AUTO_LIBRARY,
                        ConversationScopeStatus.READY,
                        true,
                        "All searchable papers",
                        List.of(),
                        Map.of()
                ));
        PaperAnswerService.AnswerScope frontendScope = new PaperAnswerService.AnswerScope(
                List.of("frontend-paper"),
                List.of("Frontend Paper"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                RetrievalBudgetProfile.HIGH_RECALL
        );

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("What does agent routing do?", frontendScope),
                fixture.session);

        ArgumentCaptor<PaperAnswerService.AnswerScope> scopeCaptor =
                ArgumentCaptor.forClass(PaperAnswerService.AnswerScope.class);
        verify(fixture.conversationScopeService).lockForFirstMessage(1L, "conversation-1");
        verify(fixture.paperAnswerService)
                .answer(eq("1"), eq("conversation-1"), eq("What does agent routing do?"), scopeCaptor.capture());
        assertEquals(List.of(), scopeCaptor.getValue().paperIds());
        assertEquals(RetrievalBudgetProfile.HIGH_RECALL, scopeCaptor.getValue().retrievalBudgetProfile());
    }

    @Test
    void autoLibraryScopeStripsScalarPaperIdWhenNotReferenceFocus() {
        ChatHandlerFixture fixture = chatHandlerFixture();
        when(fixture.conversationScopeService.lockForFirstMessage(1L, "conversation-1"))
                .thenReturn(new ConversationScopeService.EffectiveConversationScope(
                        ConversationScopeMode.AUTO_LIBRARY,
                        ConversationScopeStatus.READY,
                        true,
                        "All searchable papers",
                        List.of(),
                        Map.of()
                ));
        PaperAnswerService.AnswerScope frontendScope = new PaperAnswerService.AnswerScope(
                List.of(),
                List.of("Frontend Paper"),
                null,
                null,
                null,
                null,
                "frontend-paper",
                "Frontend Paper",
                "frontend-paper.pdf",
                null,
                null,
                null,
                RetrievalBudgetProfile.DEEP_AUDIT
        );

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("What does this paper say?", frontendScope),
                fixture.session);

        ArgumentCaptor<PaperAnswerService.AnswerScope> scopeCaptor =
                ArgumentCaptor.forClass(PaperAnswerService.AnswerScope.class);
        verify(fixture.paperAnswerService)
                .answer(eq("1"), eq("conversation-1"), eq("What does this paper say?"), scopeCaptor.capture());
        PaperAnswerService.AnswerScope answerScope = scopeCaptor.getValue();
        assertEquals(List.of(), answerScope.paperIds());
        assertEquals(null, answerScope.paperId());
        assertEquals(RetrievalBudgetProfile.DEEP_AUDIT, answerScope.retrievalBudgetProfile());
    }

    @Test
    void firstAcceptedMessageLocksSnapshotScope() {
        ChatHandlerFixture fixture = chatHandlerFixture();
        when(fixture.conversationScopeService.lockForFirstMessage(1L, "conversation-1"))
                .thenReturn(snapshotScope(List.of("paper-a", "paper-c"), false));
        PaperAnswerService.AnswerScope frontendScope = new PaperAnswerService.AnswerScope(
                List.of("frontend-paper"),
                List.of("Frontend Paper"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                RetrievalBudgetProfile.INTERACTIVE
        );

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("Compare their methods", frontendScope),
                fixture.session);

        ArgumentCaptor<PaperAnswerService.AnswerScope> scopeCaptor =
                ArgumentCaptor.forClass(PaperAnswerService.AnswerScope.class);
        verify(fixture.paperAnswerService)
                .answer(eq("1"), eq("conversation-1"), eq("Compare their methods"), scopeCaptor.capture());
        assertEquals(List.of("paper-a", "paper-c"), scopeCaptor.getValue().paperIds());
    }

    @Test
    void laterMessageUsesLockedScopeEvenWhenPayloadContainsDifferentPaperIds() {
        ChatHandlerFixture fixture = chatHandlerFixture();
        when(fixture.conversationScopeService.lockForFirstMessage(1L, "conversation-1"))
                .thenReturn(snapshotScope(List.of("paper-a"), true));
        PaperAnswerService.AnswerScope frontendOverride = new PaperAnswerService.AnswerScope(
                List.of("paper-b"),
                List.of("Paper B"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                RetrievalBudgetProfile.DEEP_AUDIT
        );

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("Explain the ablation", frontendOverride),
                fixture.session);

        ArgumentCaptor<PaperAnswerService.AnswerScope> scopeCaptor =
                ArgumentCaptor.forClass(PaperAnswerService.AnswerScope.class);
        verify(fixture.paperAnswerService)
                .answer(eq("1"), eq("conversation-1"), eq("Explain the ablation"), scopeCaptor.capture());
        assertEquals(List.of("paper-a"), scopeCaptor.getValue().paperIds());
        assertEquals(RetrievalBudgetProfile.DEEP_AUDIT, scopeCaptor.getValue().retrievalBudgetProfile());
    }

    @Test
    void referenceFocusDoesNotChangeLockedScope() {
        ChatHandlerFixture fixture = chatHandlerFixture();
        ConversationScopeService.EffectiveConversationScope lockedScope = snapshotScope(List.of("paper-a"), true);
        when(fixture.conversationScopeService.resolveForChat(1L, "conversation-1"))
                .thenReturn(lockedScope);
        when(fixture.conversationScopeService.lockForFirstMessage(1L, "conversation-1"))
                .thenReturn(lockedScope);
        when(fixture.conversationService.findReferenceDetail(1L, 51L, 2))
                .thenReturn(Optional.of(referenceDetail(
                        "paper-a",
                        "Paper A",
                        "paper-a.pdf",
                        7,
                        4,
                        "Reference evidence text from the locked paper."
                )));
        PaperAnswerService.AnswerScope referenceFocus = new PaperAnswerService.AnswerScope(
                List.of("paper-b"),
                List.of("Paper B"),
                2,
                51L,
                7,
                4,
                "paper-a",
                "Paper A",
                "paper-a.pdf",
                "Reference evidence text from the locked paper.",
                "{\"coordinateSystem\":\"top_left_1000\"}",
                "TEXT",
                RetrievalBudgetProfile.HIGH_RECALL
        );

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("Explain this citation", referenceFocus),
                fixture.session);

        ArgumentCaptor<PaperAnswerService.AnswerScope> scopeCaptor =
                ArgumentCaptor.forClass(PaperAnswerService.AnswerScope.class);
        verify(fixture.conversationScopeService, times(2)).assertReferenceFocusWithinScope(eq(lockedScope), any());
        verify(fixture.conversationScopeService, never()).updateUnlockedScope(any(), anyString(), any());
        verify(fixture.paperAnswerService)
                .answer(eq("1"), eq("conversation-1"), eq("Explain this citation"), scopeCaptor.capture());
        PaperAnswerService.AnswerScope answerScope = scopeCaptor.getValue();
        assertEquals(List.of("paper-a"), answerScope.paperIds());
        assertEquals("paper-a", answerScope.paperId());
        assertEquals(2, answerScope.referenceNumber());
        assertEquals(51L, answerScope.conversationRecordId());
        assertEquals(7, answerScope.chunkId());
        assertEquals("Reference evidence text from the locked paper.", answerScope.matchedText());
        assertEquals(RetrievalBudgetProfile.HIGH_RECALL, answerScope.retrievalBudgetProfile());
    }

    @Test
    void referenceFocusFromPersistedRecordOutsideLockedScopeDoesNotAnswer() {
        ChatHandlerFixture fixture = chatHandlerFixture();
        ConversationScopeService.EffectiveConversationScope lockedScope = snapshotScope(List.of("paper-a"), true);
        when(fixture.conversationScopeService.resolveForChat(1L, "conversation-1"))
                .thenReturn(lockedScope);
        when(fixture.conversationScopeService.lockForFirstMessage(1L, "conversation-1"))
                .thenReturn(lockedScope);
        when(fixture.conversationService.findReferenceDetail(1L, 10L, 1))
                .thenReturn(Optional.of(referenceDetail("paper-b")));
        doAnswer(invocation -> {
            PaperAnswerService.AnswerScope focus = invocation.getArgument(1);
            if ("paper-b".equals(focus.paperId())) {
                throw new RuntimeException("Reference focus is outside the conversation source scope");
            }
            return null;
        }).when(fixture.conversationScopeService).assertReferenceFocusWithinScope(eq(lockedScope), any());
        PaperAnswerService.AnswerScope referenceFocus = new PaperAnswerService.AnswerScope(
                List.of(),
                List.of(),
                1,
                10L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                RetrievalBudgetProfile.INTERACTIVE
        );

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("Explain saved reference", referenceFocus),
                fixture.session);

        verify(fixture.conversationService).findReferenceDetail(1L, 10L, 1);
        verify(fixture.paperAnswerService, never()).answer(anyString(), anyString(), anyString(), any());
    }

    @Test
    void scalarPaperIdDoesNotOverridePersistedReferencePaperOutsideLockedScope() {
        ChatHandlerFixture fixture = chatHandlerFixture();
        ConversationScopeService.EffectiveConversationScope lockedScope = snapshotScope(List.of("paper-a"), true);
        when(fixture.conversationScopeService.resolveForChat(1L, "conversation-1"))
                .thenReturn(lockedScope);
        when(fixture.conversationScopeService.lockForFirstMessage(1L, "conversation-1"))
                .thenReturn(lockedScope);
        when(fixture.conversationService.findReferenceDetail(1L, 10L, 1))
                .thenReturn(Optional.of(referenceDetail("paper-b")));
        doAnswer(invocation -> {
            PaperAnswerService.AnswerScope focus = invocation.getArgument(1);
            if ("paper-b".equals(focus.paperId())) {
                throw new RuntimeException("Reference focus is outside the conversation source scope");
            }
            return null;
        }).when(fixture.conversationScopeService).assertReferenceFocusWithinScope(eq(lockedScope), any());
        PaperAnswerService.AnswerScope referenceFocus = new PaperAnswerService.AnswerScope(
                List.of(),
                List.of("Paper A"),
                1,
                10L,
                null,
                null,
                "paper-a",
                "Paper A",
                "paper-a.pdf",
                null,
                null,
                null,
                RetrievalBudgetProfile.INTERACTIVE
        );

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("Explain saved reference", referenceFocus),
                fixture.session);

        verify(fixture.conversationService).findReferenceDetail(1L, 10L, 1);
        verify(fixture.paperAnswerService, never()).answer(anyString(), anyString(), anyString(), any());
    }

    @Test
    void persistedReferencePaperOverridesConflictingScalarPaperIdBeforeAnswering() {
        ChatHandlerFixture fixture = chatHandlerFixture();
        ConversationScopeService.EffectiveConversationScope lockedScope = snapshotScope(List.of("paper-a"), true);
        when(fixture.conversationScopeService.resolveForChat(1L, "conversation-1"))
                .thenReturn(lockedScope);
        when(fixture.conversationScopeService.lockForFirstMessage(1L, "conversation-1"))
                .thenReturn(lockedScope);
        when(fixture.conversationService.findReferenceDetail(1L, 10L, 1))
                .thenReturn(Optional.of(referenceDetail("paper-a")));
        PaperAnswerService.AnswerScope referenceFocus = new PaperAnswerService.AnswerScope(
                List.of(),
                List.of("Paper B"),
                1,
                10L,
                null,
                null,
                "paper-b",
                "Paper B",
                "paper-b.pdf",
                null,
                null,
                null,
                RetrievalBudgetProfile.DEEP_AUDIT
        );

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("Explain saved reference", referenceFocus),
                fixture.session);

        ArgumentCaptor<PaperAnswerService.AnswerScope> scopeCaptor =
                ArgumentCaptor.forClass(PaperAnswerService.AnswerScope.class);
        verify(fixture.conversationService).findReferenceDetail(1L, 10L, 1);
        verify(fixture.conversationScopeService, times(2)).assertReferenceFocusWithinScope(eq(lockedScope), any());
        verify(fixture.paperAnswerService)
                .answer(eq("1"), eq("conversation-1"), eq("Explain saved reference"), scopeCaptor.capture());
        PaperAnswerService.AnswerScope answerScope = scopeCaptor.getValue();
        assertEquals(List.of("paper-a"), answerScope.paperIds());
        assertEquals("paper-a", answerScope.paperId());
        assertEquals("Resolved Paper paper-a", answerScope.paperTitle());
        assertEquals("paper-a.pdf", answerScope.originalFilename());
        assertEquals(1, answerScope.referenceNumber());
        assertEquals(10L, answerScope.conversationRecordId());
        assertEquals("Persisted matched chunk for paper-a", answerScope.matchedText());
        assertEquals(RetrievalBudgetProfile.DEEP_AUDIT, answerScope.retrievalBudgetProfile());
    }

    @Test
    void referenceFocusFromPersistedRecordInsideLockedScopeIsEnrichedBeforeAnswering() {
        ChatHandlerFixture fixture = chatHandlerFixture();
        ConversationScopeService.EffectiveConversationScope lockedScope = snapshotScope(List.of("paper-a"), true);
        when(fixture.conversationScopeService.resolveForChat(1L, "conversation-1"))
                .thenReturn(lockedScope);
        when(fixture.conversationScopeService.lockForFirstMessage(1L, "conversation-1"))
                .thenReturn(lockedScope);
        when(fixture.conversationService.findReferenceDetail(1L, 10L, 1))
                .thenReturn(Optional.of(referenceDetail("paper-a")));
        PaperAnswerService.AnswerScope referenceFocus = new PaperAnswerService.AnswerScope(
                List.of(),
                List.of(),
                1,
                10L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                RetrievalBudgetProfile.HIGH_RECALL
        );

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("Explain saved reference", referenceFocus),
                fixture.session);

        ArgumentCaptor<PaperAnswerService.AnswerScope> scopeCaptor =
                ArgumentCaptor.forClass(PaperAnswerService.AnswerScope.class);
        verify(fixture.conversationService).findReferenceDetail(1L, 10L, 1);
        verify(fixture.conversationScopeService, times(2)).assertReferenceFocusWithinScope(eq(lockedScope), any());
        verify(fixture.paperAnswerService)
                .answer(eq("1"), eq("conversation-1"), eq("Explain saved reference"), scopeCaptor.capture());
        PaperAnswerService.AnswerScope answerScope = scopeCaptor.getValue();
        assertEquals(List.of("paper-a"), answerScope.paperIds());
        assertEquals("paper-a", answerScope.paperId());
        assertEquals("Resolved Paper paper-a", answerScope.paperTitle());
        assertEquals("paper-a.pdf", answerScope.originalFilename());
        assertEquals(1, answerScope.referenceNumber());
        assertEquals(10L, answerScope.conversationRecordId());
        assertEquals(7, answerScope.chunkId());
        assertEquals(3, answerScope.pageNumber());
        assertEquals("Persisted matched chunk for paper-a", answerScope.matchedText());
        assertEquals("{\"x\":1}", answerScope.bboxJson());
        assertEquals("TEXT", answerScope.sourceKind());
        assertEquals(RetrievalBudgetProfile.HIGH_RECALL, answerScope.retrievalBudgetProfile());
    }

    @Test
    void outOfScopeReferenceFocusRejectsBeforeLockAndGenerationCreation() {
        ChatHandlerFixture fixture = chatHandlerFixture();
        ConversationScopeService.EffectiveConversationScope resolvedScope = snapshotScope(List.of("paper-a"), true);
        when(fixture.conversationScopeService.resolveForChat(1L, "conversation-1"))
                .thenReturn(resolvedScope);
        PaperAnswerService.AnswerScope referenceFocus = new PaperAnswerService.AnswerScope(
                List.of(),
                List.of(),
                1,
                51L,
                7,
                null,
                "paper-b",
                "Paper B",
                "paper-b.pdf",
                "Reference evidence from a different paper.",
                null,
                "TEXT",
                RetrievalBudgetProfile.INTERACTIVE
        );
        doThrow(new RuntimeException("Reference focus is outside the conversation source scope"))
                .when(fixture.conversationScopeService)
                .assertReferenceFocusWithinScope(eq(resolvedScope), any());

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("Explain this citation", referenceFocus),
                fixture.session);

        verify(fixture.conversationScopeService).resolveForChat(1L, "conversation-1");
        verify(fixture.conversationScopeService, never()).lockForFirstMessage(any(), anyString());
        verify(fixture.generationStateService, never()).createGeneration(anyString(), anyString(), anyString());
        verify(fixture.paperAnswerService, never()).answer(anyString(), anyString(), anyString(), any());
    }

    @Test
    void generationCreationFailureDoesNotLockFirstMessageScope() {
        ChatHandlerFixture fixture = chatHandlerFixture();
        ConversationScopeService.EffectiveConversationScope resolvedScope = new ConversationScopeService.EffectiveConversationScope(
                ConversationScopeMode.AUTO_LIBRARY,
                ConversationScopeStatus.READY,
                false,
                "All searchable papers",
                List.of(),
                Map.of()
        );
        when(fixture.conversationScopeService.resolveForChat(1L, "conversation-1"))
                .thenReturn(resolvedScope);
        when(fixture.generationStateService.createGeneration(eq("1"), eq("conversation-1"), anyString()))
                .thenThrow(new RuntimeException("generation unavailable"));

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("What does agent routing do?", null),
                fixture.session);

        verify(fixture.conversationScopeService).resolveForChat(1L, "conversation-1");
        verify(fixture.conversationScopeService, never()).lockForFirstMessage(any(), anyString());
        verify(fixture.paperAnswerService, never()).answer(anyString(), anyString(), anyString(), any());
    }

    @Test
    void autoLibraryReferenceFocusKeepsScalarEvidenceWithoutBackfillingPaperIds() {
        ChatHandlerFixture fixture = chatHandlerFixture();
        ConversationScopeService.EffectiveConversationScope autoScope = new ConversationScopeService.EffectiveConversationScope(
                ConversationScopeMode.AUTO_LIBRARY,
                ConversationScopeStatus.READY,
                true,
                "All searchable papers",
                List.of(),
                Map.of()
        );
        when(fixture.conversationScopeService.resolveForChat(1L, "conversation-1"))
                .thenReturn(autoScope);
        when(fixture.conversationScopeService.lockForFirstMessage(1L, "conversation-1"))
                .thenReturn(autoScope);
        PaperAnswerService.AnswerScope referenceFocus = new PaperAnswerService.AnswerScope(
                List.of(),
                List.of(),
                null,
                null,
                7,
                null,
                "paper-a",
                "Paper A",
                "paper-a.pdf",
                "Reference seed text from paper A.",
                null,
                "TEXT",
                RetrievalBudgetProfile.HIGH_RECALL
        );

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("Explain this evidence", referenceFocus),
                fixture.session);

        ArgumentCaptor<PaperAnswerService.AnswerScope> scopeCaptor =
                ArgumentCaptor.forClass(PaperAnswerService.AnswerScope.class);
        verify(fixture.conversationScopeService, times(2)).assertReferenceFocusWithinScope(eq(autoScope), any());
        verify(fixture.paperAnswerService)
                .answer(eq("1"), eq("conversation-1"), eq("Explain this evidence"), scopeCaptor.capture());
        PaperAnswerService.AnswerScope answerScope = scopeCaptor.getValue();
        assertEquals(List.of(), answerScope.paperIds());
        assertEquals("paper-a", answerScope.paperId());
        assertEquals("Reference seed text from paper A.", answerScope.matchedText());
        assertEquals(7, answerScope.chunkId());
        assertEquals(RetrievalBudgetProfile.HIGH_RECALL, answerScope.retrievalBudgetProfile());
    }

    @Test
    void referenceFocusIsRevalidatedAgainstFinalLockedScopeBeforeAnswering() {
        ChatHandlerFixture fixture = chatHandlerFixture();
        ConversationScopeService.EffectiveConversationScope resolvedScope = snapshotScope(List.of("paper-a"), false);
        ConversationScopeService.EffectiveConversationScope lockedScope = snapshotScope(List.of("paper-b"), true);
        when(fixture.conversationScopeService.resolveForChat(1L, "conversation-1"))
                .thenReturn(resolvedScope);
        when(fixture.conversationScopeService.lockForFirstMessage(1L, "conversation-1"))
                .thenReturn(lockedScope);
        PaperAnswerService.AnswerScope referenceFocus = new PaperAnswerService.AnswerScope(
                List.of(),
                List.of(),
                null,
                null,
                7,
                null,
                "paper-a",
                "Paper A",
                "paper-a.pdf",
                "Reference seed text from paper A.",
                null,
                "TEXT",
                RetrievalBudgetProfile.INTERACTIVE
        );
        doThrow(new RuntimeException("Reference focus is outside the final locked scope"))
                .when(fixture.conversationScopeService)
                .assertReferenceFocusWithinScope(eq(lockedScope), any());

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("Explain this evidence", referenceFocus),
                fixture.session);

        InOrder inOrder = org.mockito.Mockito.inOrder(
                fixture.conversationScopeService,
                fixture.generationStateService
        );
        inOrder.verify(fixture.conversationScopeService).resolveForChat(1L, "conversation-1");
        inOrder.verify(fixture.conversationScopeService).assertReferenceFocusWithinScope(eq(resolvedScope), any());
        inOrder.verify(fixture.generationStateService).createGeneration(eq("1"), eq("conversation-1"), anyString());
        inOrder.verify(fixture.conversationScopeService).lockForFirstMessage(1L, "conversation-1");
        inOrder.verify(fixture.conversationScopeService).assertReferenceFocusWithinScope(eq(lockedScope), any());
        verify(fixture.paperAnswerService, never()).answer(anyString(), anyString(), anyString(), any());
    }

    @Test
    void bareMatchedTextIsNotReferenceFocus() {
        ChatHandlerFixture fixture = chatHandlerFixture();
        when(fixture.conversationScopeService.lockForFirstMessage(1L, "conversation-1"))
                .thenReturn(new ConversationScopeService.EffectiveConversationScope(
                        ConversationScopeMode.AUTO_LIBRARY,
                        ConversationScopeStatus.READY,
                        true,
                        "All searchable papers",
                        List.of(),
                        Map.of()
                ));
        PaperAnswerService.AnswerScope textOnlyScope = new PaperAnswerService.AnswerScope(
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "floating text without an anchor",
                null,
                null,
                RetrievalBudgetProfile.DEEP_AUDIT
        );

        fixture.handler.processMessage("1", new ChatHandler.ChatRequest("Use this text", textOnlyScope),
                fixture.session);

        ArgumentCaptor<PaperAnswerService.AnswerScope> scopeCaptor =
                ArgumentCaptor.forClass(PaperAnswerService.AnswerScope.class);
        verify(fixture.conversationScopeService, never()).assertReferenceFocusWithinScope(any(), any());
        verify(fixture.paperAnswerService)
                .answer(eq("1"), eq("conversation-1"), eq("Use this text"), scopeCaptor.capture());
        PaperAnswerService.AnswerScope answerScope = scopeCaptor.getValue();
        assertEquals(List.of(), answerScope.paperIds());
        assertEquals(null, answerScope.paperId());
        assertEquals(null, answerScope.matchedText());
        assertEquals(RetrievalBudgetProfile.DEEP_AUDIT, answerScope.retrievalBudgetProfile());
    }

    @Test
    void buildsReferenceEvidenceFromSearchResultWithStructuredProvenance() {
        ChatHandler handler = newHandler();
        SearchResult result = new SearchResult(
                "paper-1",
                17,
                "full chunk text",
                0.82d,
                "2",
                "TEAM_A",
                false,
                "Parsed Paper Title",
                "uploaded-paper.pdf",
                3,
                "anchor text",
                "HYBRID",
                "matched chunk text",
                "PARAGRAPH",
                "Method",
                2,
                "{\"x1\":10,\"y1\":20,\"x2\":300,\"y2\":360}",
                "OpenDataLoader",
                "2.4.7",
                "TABLE",
                "table-17",
                "Metric: Accuracy PaperLoom: 91.2",
                "| Metric | Accuracy |\n| --- | --- |\n| PaperLoom | 91.2 |",
                true
        );
        result.setSourceType("PDF");
        result.setEvidenceAssetLevel("PDF_VISUAL");
        result.setPdfEvidenceAvailable(true);
        result.setPageScreenshotAvailable(true);
        result.setFigureScreenshotAvailable(false);
        result.setAssetWarnings(List.of());

        ChatHandler.ReferenceInfo detail = ReflectionTestUtils.invokeMethod(
                handler,
                "buildReferenceInfo",
                result,
                "Parsed Paper Title",
                "bandit method"
        );

        assertEquals("paper-1", detail.paperId());
        assertEquals("Parsed Paper Title", detail.paperTitle());
        assertEquals("uploaded-paper.pdf", detail.originalFilename());
        assertEquals(3, detail.pageNumber());
        assertEquals(17, detail.chunkId());
        assertEquals("HYBRID", detail.retrievalMode());
        assertEquals("matched chunk text", detail.matchedChunkText());
        assertEquals("PARAGRAPH", detail.elementType());
        assertEquals("Method", detail.sectionTitle());
        assertEquals(2, detail.sectionLevel());
        assertEquals("{\"x1\":10,\"y1\":20,\"x2\":300,\"y2\":360}", detail.bboxJson());
        assertEquals("OpenDataLoader", detail.parserName());
        assertEquals("2.4.7", detail.parserVersion());
        assertEquals("TABLE", detail.sourceKind());
        assertEquals("table-17", detail.tableId());
        assertEquals("Metric: Accuracy PaperLoom: 91.2", detail.tableText());
        assertEquals("| Metric | Accuracy |\n| --- | --- |\n| PaperLoom | 91.2 |", detail.tableMarkdown());
        assertEquals(true, detail.tableScreenshotAvailable());
        assertEquals("PDF", detail.sourceType());
        assertEquals("PDF_VISUAL", detail.evidenceAssetLevel());
        assertEquals(true, detail.pdfEvidenceAvailable());
        assertEquals(true, detail.pageScreenshotAvailable());
        assertEquals(false, detail.figureScreenshotAvailable());
        assertEquals(List.of(), detail.assetWarnings());
    }

    @Test
    @SuppressWarnings("unchecked")
    void serializesReferenceEvidenceWithPaperAndParserProvenance() {
        ChatHandler handler = newHandler();
        Map<Integer, ChatHandler.ReferenceInfo> references = Map.of(
                1, new ChatHandler.ReferenceInfo(
                        "paper-1",
                        "Parsed Paper Title",
                        "uploaded-paper.pdf",
                        3,
                        "anchor text",
                        "HYBRID",
                        "混合召回",
                        "bandit method",
                        "matched chunk text",
                        "evidence snippet",
                        0.82d,
                        17,
                        "PARAGRAPH",
                        "Method",
                        2,
                        "{\"x1\":10,\"y1\":20,\"x2\":300,\"y2\":360}",
                        "OpenDataLoader",
                        "2.4.7",
                        "TABLE",
                        "table-17",
                        "Metric: Accuracy PaperLoom: 91.2",
                        "| Metric | Accuracy |\n| --- | --- |\n| PaperLoom | 91.2 |",
                        true,
                        "PDF",
                        "PDF_PENDING_ASSETS",
                        false,
                        false,
                        false,
                        List.of("page_screenshots_missing")
                )
        );

        Map<String, Map<String, Object>> serialized = ReflectionTestUtils.invokeMethod(
                handler,
                "toSerializableReferenceMappings",
                references
        );

        Map<String, Object> detail = serialized.get("1");
        assertEquals("paper-1", detail.get("paperId"));
        assertEquals("Parsed Paper Title", detail.get("paperTitle"));
        assertEquals("uploaded-paper.pdf", detail.get("originalFilename"));
        assertEquals(3, detail.get("pageNumber"));
        assertEquals(17, detail.get("chunkId"));
        assertEquals("HYBRID", detail.get("retrievalMode"));
        assertEquals(0.82d, detail.get("score"));
        assertEquals("PARAGRAPH", detail.get("elementType"));
        assertEquals("Method", detail.get("sectionTitle"));
        assertEquals(2, detail.get("sectionLevel"));
        assertEquals("{\"x1\":10,\"y1\":20,\"x2\":300,\"y2\":360}", detail.get("bboxJson"));
        assertEquals("OpenDataLoader", detail.get("parserName"));
        assertEquals("2.4.7", detail.get("parserVersion"));
        assertEquals("TABLE", detail.get("sourceKind"));
        assertEquals("table-17", detail.get("tableId"));
        assertEquals("Metric: Accuracy PaperLoom: 91.2", detail.get("tableText"));
        assertEquals("| Metric | Accuracy |\n| --- | --- |\n| PaperLoom | 91.2 |", detail.get("tableMarkdown"));
        assertEquals(true, detail.get("tableScreenshotAvailable"));
        assertEquals("PDF", detail.get("sourceType"));
        assertEquals("PDF_PENDING_ASSETS", detail.get("evidenceAssetLevel"));
        assertEquals(false, detail.get("pdfEvidenceAvailable"));
        assertEquals(false, detail.containsKey(LEGACY_STRUCTURED_FIELD));
        assertEquals(false, detail.containsKey(LEGACY_EVAL_FIELD));
        assertEquals(false, detail.get("pageScreenshotAvailable"));
        assertEquals(false, detail.get("figureScreenshotAvailable"));
        assertEquals(List.of("page_screenshots_missing"), detail.get("assetWarnings"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void restoresSerializedReferenceEvidenceForHistoryDetail() {
        ChatHandler handler = newHandler();
        Map<String, Object> serializedDetail = new LinkedHashMap<>();
        serializedDetail.put("paperId", "paper-1");
        serializedDetail.put("paperTitle", "Parsed Paper Title");
        serializedDetail.put("originalFilename", "uploaded-paper.pdf");
        serializedDetail.put("pageNumber", 3);
        serializedDetail.put("anchorText", "anchor text");
        serializedDetail.put("retrievalMode", "HYBRID");
        serializedDetail.put("retrievalLabel", "混合召回");
        serializedDetail.put("retrievalQuery", "bandit method");
        serializedDetail.put("matchedChunkText", "matched chunk text");
        serializedDetail.put("evidenceSnippet", "evidence snippet");
        serializedDetail.put("score", 0.82d);
        serializedDetail.put("chunkId", 17);
        serializedDetail.put("elementType", "PARAGRAPH");
        serializedDetail.put("sectionTitle", "Method");
        serializedDetail.put("sectionLevel", 2);
        serializedDetail.put("bboxJson", "{\"x1\":10,\"y1\":20,\"x2\":300,\"y2\":360}");
        serializedDetail.put("parserName", "OpenDataLoader");
        serializedDetail.put("parserVersion", "2.4.7");
        serializedDetail.put("sourceKind", "TABLE");
        serializedDetail.put("tableId", "table-17");
        serializedDetail.put("tableText", "Metric: Accuracy PaperLoom: 91.2");
        serializedDetail.put("tableMarkdown", "| Metric | Accuracy |\n| --- | --- |\n| PaperLoom | 91.2 |");
        serializedDetail.put("tableScreenshotAvailable", true);
        serializedDetail.put("sourceType", LEGACY_SOURCE_TYPE);
        serializedDetail.put("evidenceAssetLevel", "TEXT_ONLY");
        serializedDetail.put("pdfEvidenceAvailable", false);
        serializedDetail.put(LEGACY_STRUCTURED_FIELD, true);
        serializedDetail.put(LEGACY_EVAL_FIELD, true);
        serializedDetail.put("pageScreenshotAvailable", false);
        serializedDetail.put("figureScreenshotAvailable", false);
        serializedDetail.put("assetWarnings", List.of(LEGACY_ASSET_WARNING));

        Map<Integer, ChatHandler.ReferenceInfo> restored = ReflectionTestUtils.invokeMethod(
                handler,
                "toReferenceInfoMap",
                Map.of("1", serializedDetail)
        );

        ChatHandler.ReferenceInfo detail = restored.get(1);
        assertEquals("paper-1", detail.paperId());
        assertEquals("Parsed Paper Title", detail.paperTitle());
        assertEquals("uploaded-paper.pdf", detail.originalFilename());
        assertEquals(3, detail.pageNumber());
        assertEquals(17, detail.chunkId());
        assertEquals("HYBRID", detail.retrievalMode());
        assertEquals(0.82d, detail.score());
        assertEquals("PARAGRAPH", detail.elementType());
        assertEquals("Method", detail.sectionTitle());
        assertEquals(2, detail.sectionLevel());
        assertEquals("{\"x1\":10,\"y1\":20,\"x2\":300,\"y2\":360}", detail.bboxJson());
        assertEquals("OpenDataLoader", detail.parserName());
        assertEquals("2.4.7", detail.parserVersion());
        assertEquals("TABLE", detail.sourceKind());
        assertEquals("table-17", detail.tableId());
        assertEquals("Metric: Accuracy PaperLoom: 91.2", detail.tableText());
        assertEquals("| Metric | Accuracy |\n| --- | --- |\n| PaperLoom | 91.2 |", detail.tableMarkdown());
        assertEquals(true, detail.tableScreenshotAvailable());
        assertEquals("PDF", detail.sourceType());
        assertEquals("PDF_PENDING_ASSETS", detail.evidenceAssetLevel());
        assertEquals(false, detail.pdfEvidenceAvailable());
        assertEquals(false, detail.pageScreenshotAvailable());
        assertEquals(false, detail.figureScreenshotAvailable());
        assertEquals(List.of(), detail.assetWarnings());
    }

    @Test
    void restoresLegacyReferenceEvidenceWithConservativeDefaults() {
        ChatHandler handler = newHandler();
        Map<String, Object> serializedDetail = new LinkedHashMap<>();
        serializedDetail.put("paperId", "paper-1");
        serializedDetail.put("paperTitle", "Parsed Paper Title");
        serializedDetail.put("originalFilename", "uploaded-paper.pdf");
        serializedDetail.put("matchedChunkText", "matched chunk text");

        Map<Integer, ChatHandler.ReferenceInfo> restored = ReflectionTestUtils.invokeMethod(
                handler,
                "toReferenceInfoMap",
                Map.of("1", serializedDetail)
        );

        ChatHandler.ReferenceInfo detail = restored.get(1);
        assertEquals("PDF", detail.sourceType());
        assertEquals("PDF_PENDING_ASSETS", detail.evidenceAssetLevel());
        assertEquals(false, detail.pdfEvidenceAvailable());
        assertEquals(false, detail.pageScreenshotAvailable());
        assertEquals(false, detail.figureScreenshotAvailable());
        assertEquals(List.of(), detail.assetWarnings());
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistsMinerUEvidenceFieldsForHistoryDetail() {
        ChatHandler handler = newHandler();
        SearchResult result = new SearchResult(
                "paper-1",
                23,
                "Figure 2 shows the benchmark accuracy.",
                0.91d,
                "2",
                "TEAM_A",
                false,
                "Parsed Paper Title",
                "uploaded-paper.pdf",
                5,
                "Figure 2",
                "EXPANDED_HYBRID",
                "Figure 2 shows the benchmark accuracy.",
                "FIGURE",
                "Experiments",
                2,
                "{\"x1\":10,\"y1\":20,\"x2\":300,\"y2\":360}",
                "MinerU",
                "1.3.0",
                "FIGURE",
                null,
                null,
                null,
                false
        );
        result.setFigureId("figure-2");
        result.setFormulaId("formula-1");
        result.setEvidenceRole("FIGURE_CAPTION");
        result.setRetrievalQuery("experimental results");
        result.setRetrievalRoute("EXPANDED_HYBRID");
        result.setIntent("EXPERIMENT_RESULT");
        result.setRankReason("experiment-intent:FIGURE:FIGURE_CAPTION");
        result.setSourceType("PDF");
        result.setEvidenceAssetLevel("PDF_VISUAL");
        result.setPdfEvidenceAvailable(true);
        result.setPageScreenshotAvailable(true);
        result.setFigureScreenshotAvailable(true);

        ChatHandler.ReferenceInfo reference = ReflectionTestUtils.invokeMethod(
                handler,
                "buildReferenceInfo",
                result,
                "Parsed Paper Title",
                "有实验数据吗"
        );

        Map<String, Map<String, Object>> serialized = ReflectionTestUtils.invokeMethod(
                handler,
                "toSerializableReferenceMappings",
                Map.of(1, reference)
        );
        Map<Integer, ChatHandler.ReferenceInfo> restored = ReflectionTestUtils.invokeMethod(
                handler,
                "toReferenceInfoMap",
                serialized
        );

        ChatHandler.ReferenceInfo detail = restored.get(1);
        assertEquals("FIGURE", detail.sourceKind());
        assertEquals("figure-2", detail.figureId());
        assertEquals("formula-1", detail.formulaId());
        assertEquals("FIGURE_CAPTION", detail.evidenceRole());
        assertEquals("experimental results", detail.retrievalQuery());
        assertEquals("EXPANDED_HYBRID", detail.retrievalRoute());
        assertEquals("EXPERIMENT_RESULT", detail.intent());
        assertEquals("experiment-intent:FIGURE:FIGURE_CAPTION", detail.rankReason());
        assertEquals("PDF_VISUAL", detail.evidenceAssetLevel());
        assertEquals(true, detail.pageScreenshotAvailable());
        assertEquals(true, detail.figureScreenshotAvailable());
    }

    private static ChatHandler newHandler() {
        return new ChatHandler(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper(),
                null
        );
    }

    private static ConversationScopeService.EffectiveConversationScope snapshotScope(List<String> paperIds, boolean locked) {
        return new ConversationScopeService.EffectiveConversationScope(
                ConversationScopeMode.SOURCE_SET_SNAPSHOT,
                ConversationScopeStatus.READY,
                locked,
                "Agent papers",
                paperIds,
                Map.of("recipe", "test")
        );
    }

    private static Map<String, Object> referenceDetail(String paperId) {
        return referenceDetail(
                paperId,
                "Resolved Paper " + paperId,
                paperId + ".pdf",
                7,
                3,
                "Persisted matched chunk for " + paperId
        );
    }

    private static Map<String, Object> referenceDetail(String paperId,
                                                       String paperTitle,
                                                       String originalFilename,
                                                       Integer chunkId,
                                                       Integer pageNumber,
                                                       String matchedChunkText) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("paperId", paperId);
        detail.put("paperTitle", paperTitle);
        detail.put("originalFilename", originalFilename);
        detail.put("pageNumber", pageNumber);
        detail.put("chunkId", chunkId);
        detail.put("matchedChunkText", matchedChunkText);
        detail.put("bboxJson", "{\"x\":1}");
        detail.put("sourceKind", "TEXT");
        return detail;
    }

    private static ChatHandlerFixture chatHandlerFixture() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user:1:current_conversation")).thenReturn("conversation-1");

        PaperAnswerService paperAnswerService = mock(PaperAnswerService.class);
        when(paperAnswerService.answer(anyString(), anyString(), anyString(), any()))
                .thenReturn(new PaperAnswerService.AnswerResult(
                        "Evidence answer",
                        Map.of(),
                        PaperAnswerService.Intent.AUTO_SOURCE_QA,
                        0,
                        0,
                        false
                ));

        ChatGenerationStateService generationStateService = mock(ChatGenerationStateService.class);
        when(generationStateService.createGeneration(eq("1"), eq("conversation-1"), anyString()))
                .thenReturn(new ChatGenerationStateService.GenerationSnapshot(
                        "generation-1",
                        "1",
                        "conversation-1",
                        "question",
                        ChatGenerationStateService.GenerationStatus.STREAMING,
                        "",
                        "2026-06-28T12:00:00",
                        "2026-06-28T12:00:00",
                        null,
                        Map.of(),
                        Map.of()
                ));

        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        ConversationScopeService conversationScopeService = mock(ConversationScopeService.class);
        ConversationService conversationService = mock(ConversationService.class);
        ChatHandler handler = new ChatHandler(
                redisTemplate,
                null,
                null,
                paperAnswerService,
                null,
                mock(RateLimitService.class),
                conversationService,
                conversationScopeService,
                generationStateService,
                mock(ChatSessionRegistry.class),
                null,
                new ObjectMapper(),
                executor
        );

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-1");
        return new ChatHandlerFixture(handler, paperAnswerService, conversationService, conversationScopeService,
                generationStateService, session);
    }

    private record ChatHandlerFixture(
            ChatHandler handler,
            PaperAnswerService paperAnswerService,
            ConversationService conversationService,
            ConversationScopeService conversationScopeService,
            ChatGenerationStateService generationStateService,
            WebSocketSession session
    ) {
    }
}
