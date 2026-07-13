package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridSearchServicePlanTest {

    @Test
    void semanticBranchDoesNotRequireTextMatchButKeywordBranchDoes() {
        HybridSearchService.SearchBranchPlan plan = HybridSearchService.SearchBranchPlan.forQuery("讲一讲高噪声场景");

        assertTrue(plan.semanticEnabled());
        assertFalse(plan.semanticRequiresTextMatch());
        assertTrue(plan.keywordEnabled());
        assertTrue(plan.keywordRequiresTextMatch());
    }

    @Test
    void keywordBranchMatchesMetadataInjectedRetrievalText() {
        HybridSearchService.SearchBranchPlan plan = HybridSearchService.SearchBranchPlan.forQuery("post-hoc hallucination detection");
        Method keywordMatchField = assertDoesNotThrow(
                () -> HybridSearchService.SearchBranchPlan.class.getMethod("keywordMatchField"),
                "SearchBranchPlan should expose the keyword match field used by the ES text branch"
        );

        assertEquals("retrievalTextContent", assertDoesNotThrow(() -> keywordMatchField.invoke(plan)));
    }

    @Test
    void queryEmbeddingTimeoutIsBoundedByRetrievalLatencyBudget() {
        Duration timeout = HybridSearchService.queryEmbeddingTimeout(
                new RetrievalBudget(Duration.ofSeconds(6), 9_000, 0.3d, 0.03d, 24)
        );

        assertEquals(Duration.ofSeconds(2), timeout);
    }
}
