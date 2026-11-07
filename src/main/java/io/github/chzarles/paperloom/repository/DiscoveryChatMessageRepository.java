package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.DiscoveryChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface DiscoveryChatMessageRepository extends JpaRepository<DiscoveryChatMessage, Long> {

    List<DiscoveryChatMessage> findTop500ByUserIdOrderByIdDesc(String userId);

    Optional<DiscoveryChatMessage> findFirstByUserIdAndIdLessThanAndRoleOrderByIdDesc(
            String userId, Long id, String role);

    Optional<DiscoveryChatMessage> findFirstByUserIdAndRoleOrderByIdDesc(String userId, String role);

    Optional<DiscoveryChatMessage> findByTurnId(String turnId);

    List<DiscoveryChatMessage> findByTurnStatus(String turnStatus);

    @Modifying
    @Transactional
    @Query("DELETE FROM DiscoveryChatMessage m WHERE m.userId = :userId")
    void deleteAllByUserId(@Param("userId") String userId);
}
