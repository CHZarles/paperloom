package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "paperloom.react.reading-phase1")
public class ProductReadingReactProperties {
    private boolean enabled = false;
}
