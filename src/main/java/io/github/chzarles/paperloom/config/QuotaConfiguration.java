package io.github.chzarles.paperloom.config;

import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.service.UsageBalanceDashboardService;
import io.github.chzarles.paperloom.service.UsageBalanceQuotaService;
import io.github.chzarles.paperloom.service.UsageDashboardService;
import io.github.chzarles.paperloom.service.UsageQuotaService;
import io.github.chzarles.paperloom.service.UserTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 *
 * @author YiHui
 * @date 2026/3/19
 */
@Configuration
@RequiredArgsConstructor
public class QuotaConfiguration {

    private final UsageQuotaProperties usageQuotaProperties;

    @Bean
    public UsageQuotaService usageQuotaService(UserTokenService userTokenService,
                                               UserRepository userRepository) {
        return new UsageBalanceQuotaService(usageQuotaProperties, userTokenService, userRepository);
    }

    @Bean
    public UsageDashboardService usageDashboardService(UserRepository userRepository,
                                                       UsageQuotaService usageQuotaService,
                                                       UserTokenService userTokenService) {
        return new UsageBalanceDashboardService(userRepository, usageQuotaService, userTokenService);
    }

}
