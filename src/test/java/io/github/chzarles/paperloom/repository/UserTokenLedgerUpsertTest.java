package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.UserDailyChatCount;
import io.github.chzarles.paperloom.model.UserTokenRecord;
import io.github.chzarles.paperloom.service.UserTokenLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证记账的累加是原子的。
 *
 * <p>旧实现先查询再保存，并发结算会互相覆盖。这里直接打并发，断言总数不丢。
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(UserTokenLedger.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserTokenLedgerUpsertTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    @Autowired
    private UserTokenLedger ledger;

    @Autowired
    private UserTokenRecordRepository recordRepository;

    @Autowired
    private UserDailyChatCountRepository chatCountRepository;

    @Test
    void accumulatesAmountAndRequestCountIntoOneRow() {
        String userId = uniqueUserId();

        ledger.accumulateConsume(userId, DAY, "LLM", 100, 1000, 900, "对话使用");
        ledger.accumulateConsume(userId, DAY, "LLM", 200, 900, 700, "对话使用");
        ledger.accumulateConsume(userId, DAY, "LLM", 50, 700, 650, "对话使用");

        List<UserTokenRecord> records = recordsOf(userId);
        assertEquals(1, records.size(), "同一用户同一天同一类型只应有一行");
        assertEquals(350L, records.get(0).getAmount());
        assertEquals(3L, records.get(0).getRequestCount());
        assertEquals(650L, records.get(0).getBalanceAfter());
        assertEquals(1000L, records.get(0).getBalanceBefore(), "首次插入的余额快照不应被后续累加改写");
    }

    @Test
    void keepsTokenTypesSeparate() {
        String userId = uniqueUserId();

        ledger.accumulateConsume(userId, DAY, "LLM", 100, 1000, 900, "对话使用");
        ledger.accumulateConsume(userId, DAY, "EMBEDDING", 30, 500, 470, "对话使用");

        assertEquals(2, recordsOf(userId).size());
    }

    @Test
    void allowsSeveralIncreaseRowsOnSameDay() {
        String userId = uniqueUserId();

        recordRepository.save(increaseRecord(userId, 500, "注册赠送"));
        recordRepository.save(increaseRecord(userId, 300, "额度追加"));

        List<UserTokenRecord> records = recordsOf(userId);
        assertEquals(2, records.size(), "每次追加额度各写一行，唯一键不得拦住第二行");
    }

    @Test
    void concurrentConsumeWritesDoNotLoseUpdates() throws Exception {
        String userId = uniqueUserId();
        int threads = 4;
        int perThread = 5;

        runConcurrently(threads, perThread,
                () -> ledger.accumulateConsume(userId, DAY, "LLM", 10, 1000, 990, "对话使用"));

        List<UserTokenRecord> records = recordsOf(userId);
        assertEquals(1, records.size());
        assertEquals((long) threads * perThread * 10, records.get(0).getAmount());
        assertEquals((long) threads * perThread, records.get(0).getRequestCount());
    }

    @Test
    void concurrentChatCountWritesDoNotConflict() throws Exception {
        String userId = uniqueUserId();
        int threads = 4;
        int perThread = 5;

        runConcurrently(threads, perThread, () -> ledger.incrementChatRequest(userId, DAY));

        UserDailyChatCount count = chatCountRepository.findByUserIdAndRecordDate(userId, DAY).orElseThrow();
        assertEquals((long) threads * perThread, count.getChatRequestCount());
    }

    private List<UserTokenRecord> recordsOf(String userId) {
        return recordRepository.findAll().stream()
                .filter(record -> userId.equals(record.getUserId()) && DAY.equals(record.getRecordDate()))
                .toList();
    }

    private UserTokenRecord increaseRecord(String userId, long amount, String reason) {
        UserTokenRecord record = new UserTokenRecord();
        record.setUserId(userId);
        record.setRecordDate(DAY);
        record.setTokenType(UserTokenRecord.TokenType.LLM);
        record.setChangeType(UserTokenRecord.ChangeType.INCREASE);
        record.setAmount(amount);
        record.setBalanceBefore(0L);
        record.setBalanceAfter(amount);
        record.setReason(reason);
        record.setRemark("");
        record.setRequestCount(0L);
        return record;
    }

    private void runConcurrently(int threads, int perThread, Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < perThread; j++) {
                            task.run();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发写入没有在 30 秒内完成");
        } finally {
            pool.shutdownNow();
        }
    }

    private String uniqueUserId() {
        return "test-" + UUID.randomUUID();
    }
}
