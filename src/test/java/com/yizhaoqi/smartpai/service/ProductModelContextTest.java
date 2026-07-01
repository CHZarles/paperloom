package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductModelContextTest {

    @Test
    void defaultsUseFirstPhaseReActRoundLimit() {
        assertEquals(6, ProductModelContext.defaults().maxReActRounds());
        assertEquals(6, new ProductModelContext(0, 1600).maxReActRounds());
    }
}
