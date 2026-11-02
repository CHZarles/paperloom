package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.DailyReqCountStat;
import io.github.chzarles.paperloom.model.UserDailyChatCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 用户每日对话次数 Repository
 *
 * @author YiHui
 * @date 2026/3/18
 */
@Repository
public interface UserDailyChatCountRepository extends JpaRepository<UserDailyChatCount, Long> {

    /**
     * 查找用户指定日期的对话记录
     */
    Optional<UserDailyChatCount> findByUserIdAndRecordDate(String userId, LocalDate recordDate);

    /**
     * 累加当天已存在的对话次数。
     *
     * @return 命中的行数。返回 0 表示当天还没有这一行。
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE user_daily_chat_count
            SET chat_request_count = chat_request_count + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = :userId AND record_date = :recordDate
            """, nativeQuery = true)
    int incrementExisting(@Param("userId") String userId,
                          @Param("recordDate") LocalDate recordDate);

    /**
     * 创建当天第一条对话次数记录。
     *
     * <p>唯一键 {@code uk_user_date} 保证并发时只有一个事务能插入成功。
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO user_daily_chat_count
                (user_id, record_date, chat_request_count, created_at, updated_at)
            VALUES (:userId, :recordDate, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, nativeQuery = true)
    int insertCount(@Param("userId") String userId,
                    @Param("recordDate") LocalDate recordDate);

    /**
     * 查询用户最近 N 天指定 Token 类型的消耗统计（按日期分组）
     */
    @Query("SELECT new io.github.chzarles.paperloom.model.DailyReqCountStat( " +
            "r.recordDate, SUM(r.chatRequestCount)) " +
            "FROM UserDailyChatCount r " +
            "WHERE r.recordDate BETWEEN :startDate AND :endDate " +
            "GROUP BY r.recordDate ORDER BY r.recordDate ASC")
    List<DailyReqCountStat> findDailyChatCountStatsByDateRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );


}

