package com.yizhaoqi.smartpai.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ProductReadingReactPropertiesTest {

    @Test
    void readingPhaseOneFlagDefaultsDisabled() {
        assertFalse(new ProductReadingReactProperties().isEnabled());
    }
}
