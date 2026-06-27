package com.yizhaoqi.smartpai.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagProjectContextDocTest {

    private static final Path CONTEXT_DOC = Path.of("eval/rag/PROJECT_CONTEXT.md");

    @Test
    void recordsPaperLoomPurposeUsersBenchmarksAndRetrievalRoutes() throws Exception {
        assertTrue(Files.exists(CONTEXT_DOC), "project context doc is missing");

        String markdown = Files.readString(CONTEXT_DOC);
        assertContains(markdown, "evidence-grounded research-paper RAG workbench");
        assertContains(markdown, "not a generic enterprise knowledge-base assistant");
        assertContains(markdown, "Researcher");
        assertContains(markdown, "literature-review");
        assertContains(markdown, "page evidence");
        assertContains(markdown, "chunk provenance");
        assertContains(markdown, "referenceMappings");
        assertContains(markdown, "QASPER");
        assertContains(markdown, "LitSearch");
        assertContains(markdown, "Product Rescue Smoke");
        assertContains(markdown, "routed retrieval");
        assertContains(markdown, "paper_search");
        assertContains(markdown, "INSPECT_PAGE");
        assertContains(markdown, "default chat budget");
        assertContains(markdown, "explicit budget");
        assertContains(markdown, "SAG");
        assertContains(markdown, "post-retrieval expansion only");
        assertContains(markdown, "Do not run QASPER or LitSearch through OCR");
    }

    @Test
    void livingDocsLinkToProjectContext() throws Exception {
        assertContains(Files.readString(Path.of("eval/rag/README.md")), "PROJECT_CONTEXT.md");
        assertContains(Files.readString(Path.of("eval/rag/RETRIEVAL_STRATEGY_DECISION.md")), "PROJECT_CONTEXT.md");
    }

    private void assertContains(String markdown, String expected) {
        assertTrue(markdown.contains(expected), () -> "expected markdown to contain: " + expected);
    }
}
