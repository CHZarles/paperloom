package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductModelContextTest {

    @Test
    void defaultsUseExpandedReActRoundLimitAndUnlimitedCompletionTokens() {
        assertEquals(100, ProductModelContext.defaults().maxReActRounds());
        assertEquals(100, new ProductModelContext(0, 1600).maxReActRounds());
        assertEquals(0, ProductModelContext.defaults().maxCompletionTokens());
        assertEquals(0, new ProductModelContext(100, -1).maxCompletionTokens());
        assertEquals(1600, new ProductModelContext(100, 1600).maxCompletionTokens());
    }
}
