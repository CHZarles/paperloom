package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import com.yizhaoqi.smartpai.exception.RateLimitExceededException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceAnswerGeneratorTest {

    @Test
    void generatesAnswerOnlyWithLedgerEvidenceTokens() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        when(llm.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "**结论**\nAgent Harness 会改变检索行为。{{E1}}",
                        List.of(),
                        Map.of("role", "assistant", "content", "ok"),
                        "stop",
                        10,
                        5
                ));
        EvidenceAnswerGenerator generator = new EvidenceAnswerGenerator(llm, new EvidenceVerifier());

        EvidenceAnswerGenerator.GeneratedAnswer answer = generator.generate(
                "u1",
                "agent harness 和 grep 有什么关系",
                ledger(evidence("E1", "paper-a", "Agent harnesses adapt retrieval strategy over multiple tool calls."))
        );

        assertTrue(answer.valid());
        assertEquals("**结论**\nAgent Harness 会改变检索行为。{{E1}}", answer.rawMarkdown());
    }

    @Test
    void rejectsLlmAnswerWithNakedCitation() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        when(llm.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "Agent Harness 会改变检索行为。[1]",
                        List.of(),
                        Map.of("role", "assistant", "content", "bad"),
                        "stop",
                        10,
                        5
                ));
        EvidenceAnswerGenerator generator = new EvidenceAnswerGenerator(llm, new EvidenceVerifier());

        EvidenceAnswerGenerator.GeneratedAnswer answer = generator.generate(
                "u1",
                "agent harness 和 grep 有什么关系",
                ledger(evidence("E1", "paper-a", "Agent harnesses adapt retrieval strategy over multiple tool calls."))
        );

        assertTrue(!answer.valid());
        assertEquals("naked_citation", answer.verifierReason());
    }

    @Test
    void packsLargeLedgerBeforeCallingLlm() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        when(llm.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "**结论**\ngrep 在论文里被当作 agent 可调用的搜索工具讨论。{{E1}}",
                        List.of(),
                        Map.of("role", "assistant", "content", "ok"),
                        "stop",
                        900,
                        80
                ));
        EvidenceAnswerGenerator generator = new EvidenceAnswerGenerator(llm, new EvidenceVerifier());
        List<EvidenceItem> items = new ArrayList<>();
        for (int i = 1; i <= 80; i++) {
            items.add(evidence(
                    "E" + i,
                    "paper-a",
                    "Grep is available as a native bash tool, so the agent can construct search commands and inspect results. "
                            + "This evidence sentence is repeated to simulate a long retrieved chunk. ".repeat(20)
            ));
        }

        EvidenceAnswerGenerator.GeneratedAnswer answer = generator.generate(
                "u1",
                "介绍一下grep",
                new EvidenceLedger(
                        List.of(new PaperSource("paper-a", "Title paper-a", "paper-a.pdf")),
                        items,
                        new LedgerDiagnostics(80, 80, 1, "EXHAUSTED")
                )
        );

        assertTrue(answer.valid());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm).completeReActTurn(eq("u1"), messagesCaptor.capture(), eq(List.of()), anyInt());
        String systemPrompt = String.valueOf(messagesCaptor.getValue().get(0).get("content"));
        long includedEvidenceCount = systemPrompt.lines()
                .filter(line -> line.matches("E\\d+"))
                .count();
        assertTrue(includedEvidenceCount > 0);
        assertTrue(includedEvidenceCount < items.size());
    }

    @Test
    void usesCompactCompletionBudgetForEvidenceAnswer() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        when(llm.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "**结论**\ngrep 是 agent 可调用的搜索工具。{{E1}}",
                        List.of(),
                        Map.of("role", "assistant", "content", "ok"),
                        "stop",
                        900,
                        80
                ));
        EvidenceAnswerGenerator generator = new EvidenceAnswerGenerator(llm, new EvidenceVerifier());

        generator.generate(
                "u1",
                "介绍一下grep",
                ledger(evidence("E1", "paper-a", "Grep is available as a native bash search primitive."))
        );

        verify(llm).completeReActTurn(eq("u1"), any(), eq(List.of()), ArgumentMatchers.intThat(tokens -> tokens <= 600));
    }

    @Test
    void systemPromptUsesPaperLoomProductName() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        when(llm.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        "**结论**\ngrep 是 agent 可调用的搜索工具。{{E1}}",
                        List.of(),
                        Map.of("role", "assistant", "content", "ok"),
                        "stop",
                        120,
                        20
                ));
        EvidenceAnswerGenerator generator = new EvidenceAnswerGenerator(llm, new EvidenceVerifier());

        generator.generate(
                "u1",
                "介绍一下grep",
                ledger(evidence("E1", "paper-a", "Grep is available as a native bash search primitive."))
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm).completeReActTurn(eq("u1"), messagesCaptor.capture(), eq(List.of()), anyInt());
        String systemPrompt = String.valueOf(messagesCaptor.getValue().get(0).get("content"));
        assertTrue(systemPrompt.contains("PaperLoom"));
        assertTrue(!systemPrompt.contains("CiteWeave"));
    }

    @Test
    void reportsQuotaFailureSeparatelyFromGenericLlmFailure() {
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        when(llm.completeReActTurn(eq("u1"), any(), eq(List.of()), anyInt()))
                .thenThrow(new RateLimitExceededException("LLM Token 余额不足", 60));
        EvidenceAnswerGenerator generator = new EvidenceAnswerGenerator(llm, new EvidenceVerifier());

        EvidenceAnswerGenerator.GeneratedAnswer answer = generator.generate(
                "u1",
                "介绍一下grep",
                ledger(evidence("E1", "paper-a", "Grep is available as a native bash search primitive."))
        );

        assertTrue(!answer.valid());
        assertEquals("llm_quota_exceeded", answer.verifierReason());
    }

    private EvidenceLedger ledger(EvidenceItem item) {
        return new EvidenceLedger(
                List.of(new PaperSource(item.paperId(), item.paperTitle(), item.originalFilename())),
                List.of(item),
                new LedgerDiagnostics(1, 1, 1, "EXHAUSTED")
        );
    }

    private EvidenceItem evidence(String evidenceId, String paperId, String text) {
        return new EvidenceItem(
                evidenceId,
                paperId,
                "Title " + paperId,
                paperId + ".pdf",
                1,
                1,
                "TEXT",
                "Method",
                text,
                null,
                0.9d
        );
    }
}
