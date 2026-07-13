package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.ModelProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelProviderConfigRepository extends JpaRepository<ModelProviderConfig, Long> {

    List<ModelProviderConfig> findByConfigScopeOrderByProviderCodeAsc(String configScope);

    Optional<ModelProviderConfig> findByConfigScopeAndProviderCode(String configScope, String providerCode);

    Optional<ModelProviderConfig> findByConfigScopeAndActiveTrue(String configScope);
}
