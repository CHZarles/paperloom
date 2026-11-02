package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.DailyUsageStat;
import io.github.chzarles.paperloom.model.UserTokenRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
 * 用户 Token 记录 Repository
 *
 * @author YiHui
 * @date 2026/3/18
 */
@Repository
public interface UserTokenRecordRepository extends JpaRepository<UserTokenRecord, Long> {
    /**
     * 查找用户指定日期、指定 Token 类型和变动类型的记录
     */
    Optional<UserTokenRecord> findByUserIdAndRecordDateAndTokenTypeAndChangeType(
            String userId,
            LocalDate recordDate,
            UserTokenRecord.TokenType tokenType,
            UserTokenRecord.ChangeType changeType
    );

    /**
     * 累加当天已存在的消耗行。
     *
     * <p>一条 UPDATE 完成「读 + 加 + 写」，数据库对目标行加锁。并发结算不会互相覆盖。
     *
     * <p>条件用 {@code consume_day} 而不是 {@code record_date}：迁移前的历史行
     * {@code consume_day} 为空，不会被这条语句命中，因此不会重复累加。
     *
     * @return 命中的行数。返回 0 表示当天还没有这一行。
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE user_token_record
            SET amount = amount + :amount,
                request_count = request_count + 1,
                balance_after = :balanceAfter
            WHERE user_id = :userId
              AND consume_day = :recordDate
              AND token_type = :tokenType
              AND change_type = 'CONSUME'
            """, nativeQuery = true)
    int addConsumeAmount(@Param("userId") String userId,
                         @Param("recordDate") LocalDate recordDate,
                         @Param("tokenType") String tokenType,
                         @Param("amount") long amount,
                         @Param("balanceAfter") long balanceAfter);

    /**
     * 创建当天第一条消耗行。
     *
     * <p>唯一键 {@code uk_consume_day_type} 保证并发时只有一个事务能插入成功。
     * 其余事务会收到重复键异常，改为调用 {@link #addConsumeAmount}。
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO user_token_record
                (user_id, record_date, consume_day, token_type, change_type, amount,
                 balance_before, balance_after, reason, request_count, created_at)
            VALUES (:userId, :recordDate, :recordDate, :tokenType, 'CONSUME', :amount,
                    :balanceBefore, :balanceAfter, :reason, 1, CURRENT_TIMESTAMP)
            """, nativeQuery = true)
    int insertConsume(@Param("userId") String userId,
                      @Param("recordDate") LocalDate recordDate,
                      @Param("tokenType") String tokenType,
                      @Param("amount") long amount,
                      @Param("balanceBefore") long balanceBefore,
                      @Param("balanceAfter") long balanceAfter,
                      @Param("reason") String reason);


    /**
     * 获取用户指定 Token 类型的 Token 总数（求和）
     */
    @Query("SELECT COALESCE(SUM(r.amount), 0L) FROM UserTokenRecord r WHERE r.userId = :userId AND r.tokenType = :tokenType AND r.changeType = :changeType")
    long sumAmountByUserIdAndTokenTypeAndChangeType(
            @Param("userId") String userId,
            @Param("tokenType") UserTokenRecord.TokenType tokenType,
            @Param("changeType") UserTokenRecord.ChangeType changeType
    );

    /**
     * 查询用户最近 N 天指定 Token 类型的消耗统计（按日期分组）
     */
    @Query("SELECT new io.github.chzarles.paperloom.model.DailyUsageStat( " +
            "r.recordDate, SUM(r.amount), SUM(r.requestCount)) " +
            "FROM UserTokenRecord r " +
            "WHERE r.tokenType = :tokenType AND r.changeType = 'CONSUME' " +
            "AND r.recordDate BETWEEN :startDate AND :endDate " +
            "GROUP BY r.recordDate ORDER BY r.recordDate ASC")
    List<DailyUsageStat> findDailyUsageStatsByDateRangeAndTokenType(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("tokenType") UserTokenRecord.TokenType tokenType
    );

    /**
     * 查询今日各用户的 Token 消耗统计（用于排行榜）
     */
    @Query("SELECT r " +
            "FROM UserTokenRecord r " +
            "WHERE r.tokenType = :tokenType AND r.changeType = 'CONSUME' " +
            "AND r.recordDate = :today " +
            "ORDER BY r.amount DESC")
    List<UserTokenRecord> findTodayTopConsumersByTokenType(
            @Param("today") LocalDate today,
            @Param("tokenType") UserTokenRecord.TokenType tokenType,
            Pageable pageable
    );

    /**
     * 查询每个用户指定 Token 类型的最新余额不足记录。
     */
    @Query("SELECT u " +
            "FROM UserTokenRecord u " +
            "WHERE u.tokenType = :tokenType " +
            "AND u.balanceAfter <= :minBalance " +
            "AND u.createdAt = (" +
            "    SELECT MAX(ur.createdAt) " +
            "    FROM UserTokenRecord ur " +
            "    WHERE ur.userId = u.userId " +
            "    AND ur.tokenType = :tokenType " +
            ")")
    List<UserTokenRecord> findUsersWithLowBalance(
            @Param("tokenType") UserTokenRecord.TokenType tokenType,
            @Param("minBalance") long minBalance
    );

    /**
     * 分页查询用户所有 Token 记录
     */
    Page<UserTokenRecord> findByUserIdOrderByRecordDateDesc(String userId, Pageable pageable);
}
