package io.github.chzarles.paperloom.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionConfigValidatorTest {

    @Test
    void productionRequiresQdrantAuthentication() {
        MockEnvironment environment = completeProductionEnvironment();
        environment.setProperty("qdrant.api-key", "");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new ProductionConfigValidator(environment).run()
        );

        assertEquals("Missing required production config: qdrant.api-key", error.getMessage());
    }

    private MockEnvironment completeProductionEnvironment() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        environment.setActiveProfiles("prod");
        environment.setProperty("spring.datasource.url", "jdbc:mysql://database/paperloom");
        environment.setProperty("spring.datasource.username", "paperloom");
        environment.setProperty("spring.datasource.password", "secret");
        environment.setProperty("jwt.secret-key", "secret");
        environment.setProperty("deepseek.api.url", "https://model.example");
        environment.setProperty("deepseek.api.key", "secret");
        environment.setProperty("minio.endpoint", "https://minio.example");
        environment.setProperty("minio.accessKey", "access");
        environment.setProperty("minio.secretKey", "secret");
        environment.setProperty("qdrant.base-url", "https://qdrant.example");
        environment.setProperty("qdrant.api-key", "secret");
        environment.setProperty("qdrant.collection", "reading-models");
        environment.setProperty("research-harness.internal-token", "secret");
        environment.setProperty("security.allowed-origins", "https://paperloom.example");
        return environment;
    }
}
