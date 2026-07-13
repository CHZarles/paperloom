package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.RateLimitConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateLimitConfigRepository extends JpaRepository<RateLimitConfig, String> {
}
