package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.repository.UserDailyChatCountRepository;
import io.github.chzarles.paperloom.repository.UserTokenRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

/**
 * Token 记账的唯一写入点。
 *
 * <p>每次写入在独立事务中提交。余额写在 Redis，不受数据库事务控制；记账如果跟着
 * 结算事务一起回滚，余额和账本就会不一致。所以这里用 REQUIRES_NEW。
 *
 * <p>累加顺序是「先 UPDATE，没有命中再 INSERT」。这样日常路径只有一条语句。
 * 并发首写同一天第一行时，唯一键只放行一个事务，其余事务收到重复键异常后
 * 整个事务回滚，由 {@link #runWithRetry} 用新事务重试，重试时 UPDATE 会命中。
 */
@Component
public class UserTokenLedger {

    private static final Logger logger = LoggerFactory.getLogger(UserTokenLedger.class);

    private static final int MAX_ATTEMPTS = 3;

    private final UserTokenRecordRepository recordRepository;
    private final UserDailyChatCountRepository chatCountRepository;
    private final TransactionTemplate requiresNewTransaction;

    public UserTokenLedger(UserTokenRecordRepository recordRepository,
                           UserDailyChatCountRepository chatCountRepository,
                           PlatformTransactionManager transactionManager) {
        this.recordRepository = recordRepository;
        this.chatCountRepository = chatCountRepository;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 累加当天的 Token 消耗。
     */
    public void accumulateConsume(String userId,
                                  LocalDate day,
                                  String tokenType,
                                  long amount,
                                  long balanceBefore,
                                  long balanceAfter,
                                  String reason) {
        runWithRetry(() -> requiresNewTransaction.executeWithoutResult(status -> {
            int updated = recordRepository.addConsumeAmount(userId, day, tokenType, amount, balanceAfter);
            if (updated > 0) {
                return;
            }
            recordRepository.insertConsume(userId, day, tokenType, amount, balanceBefore, balanceAfter, reason);
        }));
    }

    /**
     * 累加当天的对话次数。
     */
    public void incrementChatRequest(String userId, LocalDate day) {
        runWithRetry(() -> requiresNewTransaction.executeWithoutResult(status -> {
            int updated = chatCountRepository.incrementExisting(userId, day);
            if (updated > 0) {
                return;
            }
            chatCountRepository.insertCount(userId, day);
        }));
    }

    /**
     * 执行一次记账写入。并发首写冲突时用新事务重试。
     */
    private void runWithRetry(Runnable write) {
        for (int attempt = 1; ; attempt++) {
            try {
                write.run();
                return;
            } catch (DataIntegrityViolationException | PessimisticLockingFailureException concurrentWrite) {
                if (attempt >= MAX_ATTEMPTS) {
                    logger.error("记账重试 {} 次后仍然失败", MAX_ATTEMPTS, concurrentWrite);
                    throw concurrentWrite;
                }
                logger.debug("记账遇到并发首写冲突，重试第 {} 次", attempt);
            }
        }
    }
}
