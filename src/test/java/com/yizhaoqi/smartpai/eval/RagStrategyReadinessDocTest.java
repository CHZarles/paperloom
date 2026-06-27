package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagStrategyReadinessDocTest {

    private static final Path READINESS_DOC = Path.of("eval/rag/STRATEGY_READINESS.md");

    @Test
    void recordsWhatIsProvenAndResidualFollowUps() throws Exception {
        assertTrue(Files.exists(READINESS_DOC), "strategy readiness audit is missing");

        String markdown = Files.readString(READINESS_DOC);
        assertContains(markdown, "Goal status: complete");
        assertContains(markdown, "Project/user context");
        assertContains(markdown, "PROJECT_CONTEXT.md");
        assertContains(markdown, "Benchmark ledger");
        assertContains(markdown, "CHEATSHEET.md");
        assertContains(markdown, "expanded 10-case");
        assertContains(markdown, "10/10");
        assertContains(markdown, "QASPER Dev 200");
        assertContains(markdown, "K7");
        assertContains(markdown, "61.0%");
        assertContains(markdown, "LitSearch Full");
        assertContains(markdown, "Recall@20=67.4%");
        assertContains(markdown, "Service-backed LitSearch");
        assertContains(markdown, "scope.retrievalBudgetProfile");
        assertContains(markdown, "frontend/product-mode entry point");
        assertContains(markdown, "not default chat");
        assertContains(markdown, "SAG fast-mode rejected");
        assertContains(markdown, "Figure-heavy product QA");
        assertContains(markdown, "not a blocker");
    }

    @Test
    void livingDocsLinkToReadinessAudit() throws Exception {
        assertContains(Files.readString(Path.of("eval/rag/README.md")), "STRATEGY_READINESS.md");
        assertContains(Files.readString(Path.of("eval/rag/RETRIEVAL_STRATEGY_DECISION.md")), "STRATEGY_READINESS.md");
    }

    private void assertContains(String markdown, String expected) {
        assertTrue(markdown.contains(expected), () -> "expected markdown to contain: " + expected);
    }
}
