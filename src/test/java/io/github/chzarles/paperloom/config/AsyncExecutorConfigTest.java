package io.github.chzarles.paperloom.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AsyncExecutorConfigTest {

    @Test
    void researchHarnessExecutorIsBoundedAndDoesNotQueue() {
        ThreadPoolTaskExecutor executor = new AsyncExecutorConfig().researchHarnessExecutor(16);
        try {
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            assertEquals(0, pool.getCorePoolSize());
            assertEquals(16, pool.getMaximumPoolSize());
            assertEquals(60L, pool.getKeepAliveTime(java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(SynchronousQueue.class, pool.getQueue());
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, pool.getRejectedExecutionHandler());
        } finally {
            executor.shutdown();
        }
    }
}
