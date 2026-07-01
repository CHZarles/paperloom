package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.ConversationSession;
import com.yizhaoqi.smartpai.repository.ConversationSessionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductMemoryServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadsPersistedStructuredConversationMemory() {
        ConversationSessionRepository repository = mock(ConversationSessionRepository.class);
        ConversationSession session = new ConversationSession();
        session.setConversationMemoryJson("""
                {"userGoals":["compare LoRA"],"confirmedConstraints":["use current scope"]}
                """);
        when(repository.findByConversationIdAndUserId("conversation-1", 7L))
                .thenReturn(Optional.of(session));
        ProductMemoryService service = new ProductMemoryService(repository, objectMapper);

        Map<String, Object> memory = service.loadMemory(7L, "conversation-1");

        assertEquals(List.of("compare LoRA"), memory.get("userGoals"));
        assertEquals(List.of("use current scope"), memory.get("confirmedConstraints"));
    }

    @Test
    void loadMemoryStripsPersistedNonOpaquePaperIdentityFields() {
        ConversationSessionRepository repository = mock(ConversationSessionRepository.class);
        ConversationSession session = new ConversationSession();
        session.setConversationMemoryJson("""
                {
                  "userGoals": [],
                  "confirmedConstraints": [],
                  "openQuestions": [],
                  "papersDiscussed": [
                    {
                      "id": "2507.21504",
                      "paperId": "raw-paper-id",
                      "paperRef": "2507.21504",
                      "title": "Evaluation and Benchmarking of LLM Agents: A Survey"
                    }
                  ],
                  "referencesDiscussed": [],
                  "decisions": [],
                  "failedAttempts": [],
                  "sessionScope": {"scopeSnapshotId": "AUTO_SOURCE:1", "immutable": true}
                }
                """);
        when(repository.findByConversationIdAndUserId("conversation-1", 7L))
                .thenReturn(Optional.of(session));
        ProductMemoryService service = new ProductMemoryService(repository, objectMapper);

        Map<String, Object> memory = service.loadMemory(7L, "conversation-1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> papers = (List<Map<String, Object>>) memory.get("papersDiscussed");
        assertEquals(1, papers.size());
        assertEquals("Evaluation and Benchmarking of LLM Agents: A Survey", papers.get(0).get("title"));
        assertFalse(papers.get(0).containsKey("id"));
        assertFalse(papers.get(0).containsKey("paperId"));
        assertFalse(papers.get(0).containsKey("paperRef"));
    }

    @Test
    void savesStructuredConversationMemoryOnSession() {
        ConversationSessionRepository repository = mock(ConversationSessionRepository.class);
        ConversationSession session = new ConversationSession();
        when(repository.findByConversationIdAndUserId("conversation-1", 7L))
                .thenReturn(Optional.of(session));
        ProductMemoryService service = new ProductMemoryService(repository, objectMapper);

        service.saveMemory(7L, "conversation-1", Map.of(
                "userGoals", List.of("read papers"),
                "sessionScope", Map.of("immutable", true)
        ));

        assertNotNull(session.getConversationMemoryJson());
        verify(repository).save(any(ConversationSession.class));
    }

    @Test
    void updateMemoryStripsNonOpaquePaperIdentityFields() {
        ConversationSessionRepository repository = mock(ConversationSessionRepository.class);
        ConversationSession session = new ConversationSession();
        when(repository.findByConversationIdAndUserId("conversation-1", 7L))
                .thenReturn(Optional.of(session));
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        when(llm.completeReActTurn(eq("7"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        """
                        {
                          "userGoals": ["read agent eval survey"],
                          "confirmedConstraints": [],
                          "openQuestions": [],
                          "papersDiscussed": [
                            {
                              "id": "2507.21504",
                              "paperId": "0459707f3340c6453baa9cadd3a0b09d",
                              "paperRef": "2507.21504",
                              "title": "Evaluation and Benchmarking of LLM Agents: A Survey",
                              "originalFilename": "2507.21504.pdf"
                            },
                            {
                              "paperRef": "paper_dfc0e064a5ba42bf",
                              "title": "Evaluation and Benchmarking of LLM Agents: A Survey"
                            }
                          ],
                          "referencesDiscussed": [],
                          "decisions": [],
                          "failedAttempts": [],
                          "sessionScope": {
                            "scopeSnapshotId": "AUTO_SOURCE:1",
                            "immutable": true
                          }
                        }
                        """,
                        List.of(),
                        Map.of("role", "assistant", "content", "{}"),
                        "stop",
                        20,
                        20
                ));
        ProductMemoryService service = new ProductMemoryService(repository, objectMapper, llm);

        ProductMemoryService.MemoryUpdateResult result = service.updateMemory(
                7L,
                "conversation-1",
                Map.of(),
                "详细介绍一下 Evaluation and Benchmarking of LLM Agents: A Survey",
                new ProductTurnResult(
                        "无法提取证据。",
                        new AnswerEnvelope(
                                AnswerType.INSUFFICIENT_EVIDENCE,
                                "无法提取证据。",
                                List.of(),
                                List.of(),
                                List.of("reference resolution failed"),
                                List.of(),
                                List.of(),
                                ""
                        ),
                        List.of(),
                        List.of(),
                        ProductStopReason.COMPLETED,
                        ProductResultStatus.COMPLETED
                ),
                SourceScope.auto()
        );

        assertEquals(true, result.success());
        String savedMemory = session.getConversationMemoryJson();
        assertNotNull(savedMemory);
        assertTrue(savedMemory.contains("Evaluation and Benchmarking of LLM Agents: A Survey"));
        assertTrue(savedMemory.contains("paper_dfc0e064a5ba42bf"));
        assertFalse(savedMemory.contains("\"id\""));
        assertFalse(savedMemory.contains("\"paperId\""));
        assertFalse(savedMemory.contains("\"paperRef\":\"2507.21504\""));
    }

    @Test
    void updatesMemoryWithSeparateLlmCompressionCall() {
        ConversationSessionRepository repository = mock(ConversationSessionRepository.class);
        ConversationSession session = new ConversationSession();
        when(repository.findByConversationIdAndUserId("conversation-1", 7L))
                .thenReturn(Optional.of(session));
        LlmProviderRouter llm = mock(LlmProviderRouter.class);
        when(llm.completeReActTurn(eq("7"), any(), eq(List.of()), anyInt()))
                .thenReturn(new LlmProviderRouter.ReActTurn(
                        """
                        {
                          "userGoals": ["read LoRA"],
                          "confirmedConstraints": [],
                          "openQuestions": [],
                          "papersDiscussed": ["LoRA"],
                          "referencesDiscussed": ["citation_generation-1_1"],
                          "decisions": [],
                          "failedAttempts": [],
                          "sessionScope": {
                            "scopeSnapshotId": "AUTO_SOURCE:1",
                            "immutable": true
                          }
                        }
                        """,
                        List.of(),
                        Map.of("role", "assistant", "content", "{}"),
                        "stop",
                        20,
                        20
                ));
        ProductMemoryService service = new ProductMemoryService(repository, objectMapper, llm);

        ProductMemoryService.MemoryUpdateResult result = service.updateMemory(
                7L,
                "conversation-1",
                Map.of(),
                "LoRA 的方法是什么",
                new ProductTurnResult(
                        "LoRA 使用低秩适配 [1]。",
                        new AnswerEnvelope(
                                AnswerType.EVIDENCE_ANSWER,
                                "LoRA 使用低秩适配。",
                                List.of(Map.of("claim", "LoRA 使用低秩适配。", "evidenceRefs", List.of("ev_1"))),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                ""
                        ),
                        List.of(Map.of("referenceNumber", 1, "citationRef", "citation_generation-1_1")),
                        List.of(),
                        ProductStopReason.COMPLETED,
                        ProductResultStatus.COMPLETED
                ),
                SourceScope.auto()
        );

        assertEquals(true, result.success());
        assertEquals(List.of("read LoRA"), result.memory().get("userGoals"));
        assertNotNull(session.getConversationMemoryJson());
        verify(repository).save(any(ConversationSession.class));
    }
}
