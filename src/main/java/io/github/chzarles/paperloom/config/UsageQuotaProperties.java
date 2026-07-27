package io.github.chzarles.paperloom.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "usage-quota")
public class UsageQuotaProperties {

    private int retentionDays = 35;

    private TokenQuota llm = new TokenQuota();
    private TokenQuota embedding = new TokenQuota();

    @Data
    public static class TokenQuota {
        private boolean enabled = true;
        private long initTokens;
        private long adminInitTokens;
    }
}
