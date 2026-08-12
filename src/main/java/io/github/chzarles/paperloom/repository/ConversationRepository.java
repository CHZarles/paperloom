package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.Conversation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * 根据用户 ID 和时间范围查询对话记录。
     *
     * @param userId 用户 ID
     * @param startDate 起始日期（可选）
     * @param endDate 结束日期（可选）
     * @return 符合条件的对话记录列表
     */
    @EntityGraph(attributePaths = "user")
    List<Conversation> findByUserIdAndTimestampBetweenOrderByTimestampAsc(Long userId, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * 根据用户 ID 查询所有对话记录。
     *
     * @param userId 用户 ID
     * @return 符合条件的对话记录列表
     */
    @EntityGraph(attributePaths = "user")
    List<Conversation> findByUserIdOrderByTimestampAsc(Long userId);

    /**
     * 根据时间范围查询所有对话记录。
     *
     * @param startDate 起始日期
     * @param endDate 结束日期
     * @return 符合条件的对话记录列表
     */
    @EntityGraph(attributePaths = "user")
    List<Conversation> findByTimestampBetweenOrderByTimestampAsc(LocalDateTime startDate, LocalDateTime endDate);

    @EntityGraph(attributePaths = "user")
    List<Conversation> findAllByOrderByTimestampAsc();

    @EntityGraph(attributePaths = "user")
    List<Conversation> findByUserIdAndConversationIdOrderByTimestampAsc(Long userId, String conversationId);

    @EntityGraph(attributePaths = "user")
    @Query("""
            SELECT c FROM Conversation c
            WHERE c.user.id = :userId
              AND c.conversationId = :conversationId
              AND c.currentRevision = true
            ORDER BY COALESCE(c.answerSlotId, c.id) ASC, c.answerRevision ASC, c.id ASC
            """)
    List<Conversation> findCurrentByUserIdAndConversationIdOrderBySlotAsc(
            @Param("userId") Long userId,
            @Param("conversationId") String conversationId
    );

    @EntityGraph(attributePaths = "user")
    @Query("""
            SELECT c FROM Conversation c
            WHERE c.user.id = :userId
              AND c.conversationId = :conversationId
              AND c.currentRevision = true
              AND COALESCE(c.answerSlotId, c.id) < :answerSlotId
            ORDER BY COALESCE(c.answerSlotId, c.id) ASC, c.answerRevision ASC, c.id ASC
            """)
    List<Conversation> findCurrentBeforeAnswerSlot(
            @Param("userId") Long userId,
            @Param("conversationId") String conversationId,
            @Param("answerSlotId") Long answerSlotId
    );

    @EntityGraph(attributePaths = "user")
    Optional<Conversation> findByIdAndUserId(Long id, Long userId);

    @EntityGraph(attributePaths = "user")
    Optional<Conversation> findFirstByGenerationIdAndUserId(String generationId, Long userId);

    boolean existsByUserIdAndConversationId(Long userId, String conversationId);

    @Query("""
            SELECT DISTINCT c.conversationId FROM Conversation c
            WHERE c.user.id = :userId
              AND c.conversationId IS NOT NULL
              AND c.currentRevision = true
            """)
    List<String> findDistinctConversationIdsByUserId(@Param("userId") Long userId);

    @Query("""
            SELECT c FROM Conversation c
            WHERE c.user.id = :userId
              AND c.conversationId = :conversationId
              AND c.currentRevision = true
              AND (:beforeRecordId IS NULL OR c.id < :beforeRecordId)
            ORDER BY COALESCE(c.answerSlotId, c.id) DESC, c.id DESC
            """)
    List<Conversation> findConversationHistoryPage(
            @Param("userId") Long userId,
            @Param("conversationId") String conversationId,
            @Param("beforeRecordId") Long beforeRecordId,
            Pageable pageable
    );

    @EntityGraph(attributePaths = "user")
    @Query("""
            SELECT c FROM Conversation c
            WHERE c.user.id = :userId
              AND COALESCE(c.answerSlotId, c.id) = :answerSlotId
            ORDER BY c.answerRevision ASC, c.id ASC
            """)
    List<Conversation> findRevisionsByUserIdAndAnswerSlotId(
            @Param("userId") Long userId,
            @Param("answerSlotId") Long answerSlotId
    );

    @Modifying
    @Query("""
            UPDATE Conversation c
            SET c.currentRevision = false
            WHERE c.user.id = :userId
              AND COALESCE(c.answerSlotId, c.id) = :answerSlotId
            """)
    int clearCurrentRevision(
            @Param("userId") Long userId,
            @Param("answerSlotId") Long answerSlotId
    );

    @Modifying
    @Query("""
            UPDATE Conversation c
            SET c.currentRevision = false
            WHERE c.user.id = :userId
              AND c.conversationId = :conversationId
              AND COALESCE(c.answerSlotId, c.id) > :answerSlotId
              AND c.currentRevision = true
            """)
    int hideCurrentAnswersAfterSlot(
            @Param("userId") Long userId,
            @Param("conversationId") String conversationId,
            @Param("answerSlotId") Long answerSlotId
    );

    void deleteByUserIdAndConversationId(Long userId, String conversationId);
}
