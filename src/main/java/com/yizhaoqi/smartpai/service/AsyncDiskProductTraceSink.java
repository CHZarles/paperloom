package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.ProductTraceProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AsyncDiskProductTraceSink implements ProductTraceSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncDiskProductTraceSink.class);
    private static final Path DEFAULT_ROOT = Path.of("data", "traces", "product-react");

    private final ObjectMapper objectMapper;
    private final Path traceRoot;
    private final boolean enabled;
    private final int queueCapacity;
    private final BlockingQueue<ProductTracePayload> queue;
    private final ExecutorService writerExecutor;
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    @Autowired
    public AsyncDiskProductTraceSink(ObjectMapper objectMapper, ProductTraceProperties properties) {
        this(
                objectMapper,
                properties == null ? DEFAULT_ROOT : properties.getRoot(),
                properties == null ? 1000 : properties.getQueueCapacity(),
                properties == null ? 1 : properties.getWriterThreads(),
                properties == null || properties.isEnabled()
        );
    }

    public AsyncDiskProductTraceSink(ObjectMapper objectMapper,
                                     Path traceRoot,
                                     int queueCapacity,
                                     int writerThreads,
                                     boolean enabled) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.traceRoot = traceRoot == null ? DEFAULT_ROOT : traceRoot;
        this.enabled = enabled;
        this.queueCapacity = Math.max(0, queueCapacity);
        this.queue = enabled && this.queueCapacity > 0 ? new ArrayBlockingQueue<>(this.queueCapacity) : null;
        int threadCount = enabled && this.queueCapacity > 0 ? Math.max(1, writerThreads) : 0;
        this.writerExecutor = threadCount > 0
                ? Executors.newFixedThreadPool(threadCount, runnable -> {
                    Thread thread = new Thread(runnable, "paperloom-product-trace-writer");
                    thread.setDaemon(true);
                    return thread;
                })
                : null;
        for (int index = 0; index < threadCount; index++) {
            this.writerExecutor.submit(this::writeLoop);
        }
    }

    @Override
    public void submit(ProductTracePayload payload) {
        if (!enabled || payload == null) {
            return;
        }
        if (queue == null) {
            log.warn("trace_dropped_queue_full conversationId={} generationId={} artifactType={} queueSize={}",
                    payload.conversationId(), payload.generationId(), payload.artifactType(), 0);
            return;
        }
        if (!accepting.get() || !queue.offer(payload)) {
            log.warn("trace_dropped_queue_full conversationId={} generationId={} artifactType={} queueSize={}",
                    payload.conversationId(), payload.generationId(), payload.artifactType(), queue.size());
        }
    }

    private void writeLoop() {
        while (accepting.get() || (queue != null && !queue.isEmpty())) {
            try {
                ProductTracePayload payload = queue.poll(200, TimeUnit.MILLISECONDS);
                if (payload != null) {
                    writePayload(payload);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void writePayload(ProductTracePayload payload) {
        Path target = null;
        Path temp = null;
        try {
            Path directory = traceRoot.resolve("conversation-" + safeSegment(payload.conversationId()));
            Files.createDirectories(directory);
            target = directory.resolve(safeFilename(payload.filename()));
            temp = directory.resolve(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), payload.traceJson());
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception exception) {
            deleteTempQuietly(temp);
            log.warn("trace_write_failed conversationId={} generationId={} artifactType={} targetPath={}",
                    payload.conversationId(),
                    payload.generationId(),
                    payload.artifactType(),
                    target == null ? "" : target,
                    exception);
        }
    }

    private void deleteTempQuietly(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (Exception ignored) {
            // Trace cleanup is best effort and must not affect the business path.
        }
    }

    @Override
    @PreDestroy
    public void close() {
        accepting.set(false);
        if (writerExecutor == null) {
            return;
        }
        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS)) {
                writerExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writerExecutor.shutdownNow();
        }
    }

    private String safeFilename(String value) {
        String raw = value == null || value.isBlank() ? "trace.json" : value.trim();
        return raw.replaceAll("[/\\\\]", "_").replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String safeSegment(String value) {
        String raw = value == null || value.isBlank() ? "unknown" : value.trim();
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
