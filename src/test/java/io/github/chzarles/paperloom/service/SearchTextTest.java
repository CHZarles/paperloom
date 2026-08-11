package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTextTest {

    @Test
    void previewChoosesWindowWithTheMostDistinctQueryTokens() {
        String content = "An early pass@k formula compares different numbers of samples. "
                + "unrelated filler ".repeat(40)
                + "HumanEval uses 8 random samples per problem generated from Codex-12B at temperature 0.8.";

        String preview = SearchText.preview(
                content,
                SearchText.tokens("HumanEval Codex-12B random samples temperature"),
                180
        );

        assertTrue(preview.contains("8 random samples per problem"));
        assertTrue(preview.contains("temperature 0.8"));
        assertFalse(preview.contains("early pass@k formula"));
    }
}
