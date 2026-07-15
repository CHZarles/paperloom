package io.github.chzarles.paperloom.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRetrievalStrategyDecisionDocTest {

    private static final Path DECISION_DOC = Path.of("eval/rag/RETRIEVAL_STRATEGY_DECISION.md");

    @Test
    void recordsCurrentRoutedRetrievalDecision() throws Exception {
        assertTrue(Files.exists(DECISION_DOC), "retrieval strategy decision snapshot is missing");

        String markdown = Files.readString(DECISION_DOC);
        assertContains(markdown, "routed retrieval strategy");
        assertContains(markdown, "Literature search");
        assertContains(markdown, "paper-level metadata retrieval first");
        assertContains(markdown, "paper_search first-stage retrieval");
        assertContains(markdown, "Scoped paper QA");
        assertContains(markdown, "hybrid chunk retrieval plus page-window inspection");
        assertContains(markdown, "Known page/reference follow-up");
        assertContains(markdown, "deterministic inspection");
        assertContains(markdown, "SAG");
        assertContains(markdown, "post-retrieval expansion only");
        assertContains(markdown, "chunk-aware page rerank");
        assertContains(markdown, "scientific-qa-chunk-window");
        assertContains(markdown, "diverse-window");
        assertContains(markdown, "service-backed-scoped-diverse-window");
        assertContains(markdown, "service-backed-scoped-diverse-window-k5");
        assertContains(markdown, "service-backed-scoped-diverse-window-k7");
        assertContains(markdown, "high-recall budget");
        assertContains(markdown, "deep-recall budget");
        assertContains(markdown, "Production integration boundary");
        assertContains(markdown, "trusted-anchor only");
        assertContains(markdown, "do not wire K7 into default chat");
        assertContains(markdown, "forQaHighRecallPageWindows");
        assertContains(markdown, "forQaDeepAuditPageWindows");
        assertContains(markdown, "explicit budget selector");
        assertContains(markdown, "LitSearch Full is now scored locally");
        assertContains(markdown, "Recall@20=67.4%");
        assertContains(markdown, "litsearch-service-slice-k5");
        assertContains(markdown, "metadata fallback");
        assertContains(markdown, "Recall@20=58.4%");
    }

    @Test
    void livingDocsLinkToDecisionSnapshot() throws Exception {
        assertContains(Files.readString(Path.of("eval/rag/README.md")), "RETRIEVAL_STRATEGY_DECISION.md");
        assertContains(Files.readString(Path.of("eval/rag/RAG_METHOD_EXPLORATION.md")), "RETRIEVAL_STRATEGY_DECISION.md");
        assertContains(
                Files.readString(Path.of("eval/rag/CHEATSHEET.md")),
                "RETRIEVAL_STRATEGY_DECISION.md"
        );
    }

    private void assertContains(String markdown, String expected) {
        assertTrue(markdown.contains(expected), () -> "expected markdown to contain: " + expected);
    }
}
