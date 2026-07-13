package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.ConversationSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSession, Long> {

    List<ConversationSession> findByUserIdAndStatusOrderByUpdatedAtDesc(Long userId, ConversationSession.SessionStatus status);

    List<ConversationSession> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<ConversationSession> findByConversationIdAndUserId(String conversationId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ConversationSession s WHERE s.conversationId = :conversationId AND s.user.id = :userId")
    Optional<ConversationSession> findByConversationIdAndUserIdForUpdate(@Param("conversationId") String conversationId,
                                                                         @Param("userId") Long userId);

    boolean existsByConversationId(String conversationId);
}
