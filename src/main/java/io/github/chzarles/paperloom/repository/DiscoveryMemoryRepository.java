package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.DiscoveryMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DiscoveryMemoryRepository extends JpaRepository<DiscoveryMemory, Long> {

    List<DiscoveryMemory> findByUserIdOrderByUpdatedAtAsc(String userId);

    Optional<DiscoveryMemory> findByIdAndUserId(Long id, String userId);
}
