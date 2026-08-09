package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
@ConditionalOnProperty(name = "research-harness.transport", havingValue = "redis")
public class RedisResearchHarnessTransport implements ResearchHarnessTransport {

    private static final Logger logger = LoggerFactory.getLogger(RedisResearchHarnessTransport.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final UsageQuotaService usageQuotaService;
    private final ResearchHarnessPayloadFactory payloadFactory;
    private final ResearchHarnessResultMapper resultMapper;
    private final String jobStreamKey;
    private final String statusKeyPrefix;
    private final String eventKeyPrefix;
    private final String cancelKeyPrefix;
    private final int queueMaxDepth;
    private final Duration eventReadTimeout;
    private final Duration eventBlockTimeout;
    private final Duration statusTtl;
    private final Duration cancelTtl;
    private final ExecutorService eventExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "research-harness-redis-events");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, ActiveRequest> activeRequests = new ConcurrentHashMap<>();

    public RedisResearchHarnessTransport(
            ObjectMapper objectMapper,
            StringRedisTemplate redisTemplate,
            UsageQuotaService usageQuotaService,
            ResearchHarnessPayloadFactory payloadFactory,
            ResearchHarnessResultMapper resultMapper,
            @Value("${research-harness.redis.jobs-stream:paperloom:research:harness:jobs}") String jobStreamKey,
            @Value("${research-harness.redis.status-prefix:paperloom:research:harness:status:}") String statusKeyPrefix,
            @Value("${research-harness.redis.events-prefix:paperloom:research:harness:events:}") String eventKeyPrefix,
            @Value("${research-harness.redis.cancel-prefix:paperloom:research:harness:cancel:}") String cancelKeyPrefix,
            @Value("${research-harness.redis.queue-max-depth:200}") int queueMaxDepth,
            @Value("${research-harness.redis.event-read-timeout-seconds:930}") long eventReadTimeoutSeconds,
            @Value("${research-harness.redis.event-block-timeout-ms:2000}") long eventBlockTimeoutMs,
            @Value("${research-harness.redis.status-ttl-seconds:1800}") long statusTtlSeconds,
            @Value("${research-harness.redis.cancel-ttl-seconds:1800}") long cancelTtlSeconds) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.usageQuotaService = usageQuotaService;
        this.payloadFactory = payloadFactory;
        this.resultMapper = resultMapper;
        this.jobStreamKey = jobStreamKey;
        this.statusKeyPrefix = statusKeyPrefix;
        this.eventKeyPrefix = eventKeyPrefix;
        this.cancelKeyPrefix = cancelKeyPrefix;
        this.queueMaxDepth = Math.max(1, queueMaxDepth);
        this.eventReadTimeout = Duration.ofSeconds(Math.max(1, eventReadTimeoutSeconds));
        this.eventBlockTimeout = Duration.ofMillis(Math.max(100, eventBlockTimeoutMs));
        this.statusTtl = Duration.ofSeconds(Math.max(60, statusTtlSeconds));
        this.cancelTtl = Duration.ofSeconds(Math.max(60, cancelTtlSeconds));
    }

    @Override
    public ProductTurnResult run(ProductTurnRequest request) {
        return submit(request, event -> {}).join();
    }

    @Override
    public CompletableFuture<ProductTurnResult> submit(ProductTurnRequest request,
                                                       Consumer<Map<String, Object>> progressListener) {
        if (request.lockedScope().paperIds().isEmpty()) {
            throw new IllegalArgumentException("The Python harness requires an authorized paper scope");
        }
        assertQueueCapacity();
        UsageQuotaService.TokenReservation reservation = usageQuotaService.reserveLlmTokens(
                String.valueOf(request.userId()),
                0,
                1
        );
        CompletableFuture<ProductTurnResult> future = new CompletableFuture<>();
        AtomicBoolean reservationFinished = new AtomicBoolean(false);
        String generationId = request.generationId();
        ActiveRequest active = new ActiveRequest(future);
        if (activeRequests.putIfAbsent(generationId, active) != null) {
            usageQuotaService.abortReservation(reservation);
            throw new IllegalStateException("Research generation is already active: " + generationId);
        }
        future.whenComplete((result, error) -> {
            activeRequests.remove(generationId, active);
            if (error != null && reservationFinished.compareAndSet(false, true)) {
                usageQuotaService.abortReservation(reservation);
            }
        });
        try {
            enqueue(request);
            eventExecutor.execute(() -> readEvents(request, progressListener, reservation, reservationFinished, future));
        } catch (Exception error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    @Override
    public void cancel(String generationId) {
        if (generationId == null || generationId.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(cancelKey(generationId), "1", cancelTtl);
        ActiveRequest active = activeRequests.get(generationId);
        if (active != null) {
            active.future().completeExceptionally(new CancellationException("Research generation cancelled"));
        }
    }

    @PreDestroy
    void shutdown() {
        activeRequests.values().forEach(active ->
                active.future().completeExceptionally(new CancellationException("Research harness transport stopped")));
        eventExecutor.shutdownNow();
    }

    private void assertQueueCapacity() {
        Long currentDepth = streamOps().size(jobStreamKey);
        if (currentDepth != null && currentDepth > queueMaxDepth) {
            throw new IllegalStateException("Research harness queue is busy");
        }
    }

    private void enqueue(ProductTurnRequest request) throws Exception {
        String generationId = request.generationId();
        Map<String, Object> payload = payloadFactory.requestBody(request);
        long now = System.currentTimeMillis();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schema_version", "research-harness-job/v1");
        fields.put("generation_id", generationId);
        fields.put("created_at_ms", String.valueOf(now));
        fields.put("attempt", "1");
        fields.put("payload_json", objectMapper.writeValueAsString(payload));
        RecordId recordId = streamOps().add(jobStreamKey, fields);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("schema_version", "research-harness-status/v1");
        status.put("generation_id", generationId);
        status.put("status", "QUEUED");
        status.put("worker_id", "");
        status.put("job_stream_id", recordId == null ? "" : recordId.getValue());
        status.put("attempt", 1);
        status.put("created_at_ms", now);
        status.put("started_at_ms", "");
        status.put("updated_at_ms", now);
        status.put("terminal_at_ms", "");
        status.put("error_type", "");
        status.put("message", "");
        writeStatus(generationId, status);
    }

    private void readEvents(ProductTurnRequest request,
                            Consumer<Map<String, Object>> progressListener,
                            UsageQuotaService.TokenReservation reservation,
                            AtomicBoolean reservationFinished,
                            CompletableFuture<ProductTurnResult> future) {
        String generationId = request.generationId();
        String eventKey = eventKey(generationId);
        String lastId = "0-0";
        long deadline = System.nanoTime() + eventReadTimeout.toNanos();
        while (!future.isDone() && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, String, String>> records = streamOps().read(
                        StreamReadOptions.empty().block(eventBlockTimeout).count(20),
                        StreamOffset.create(eventKey, ReadOffset.from(lastId))
                );
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, String, String> record : records) {
                    lastId = record.getId().getValue();
                    if (handleEvent(request, record.getValue(), progressListener, reservation, reservationFinished, future)) {
                        return;
                    }
                }
            } catch (Exception error) {
                if (!future.isDone()) {
                    future.completeExceptionally(error);
                }
                return;
            }
        }
        if (!future.isDone()) {
            future.completeExceptionally(new IllegalStateException("Research harness timed out waiting for terminal event"));
        }
    }

    private boolean handleEvent(ProductTurnRequest request,
                                Map<String, String> fields,
                                Consumer<Map<String, Object>> progressListener,
                                UsageQuotaService.TokenReservation reservation,
                                AtomicBoolean reservationFinished,
                                CompletableFuture<ProductTurnResult> future) throws Exception {
        String type = stringValue(fields.get("type"));
        Map<String, Object> payload = jsonMap(fields.get("payload_json"));
        if ("result".equals(type)) {
            Map<String, Object> usage = resultMapper.objectMap(payload.get("usage"));
            usageQuotaService.settleReservation(reservation, intValue(usage.get("total_tokens"), 1));
            reservationFinished.set(true);
            future.complete(resultMapper.toProductResult(request, payload));
            return true;
        }
        if ("error".equals(type)) {
            future.completeExceptionally(new IllegalStateException(firstNonBlank(
                    payload.get("message"), payload.get("error_type"), "The Python research harness failed")));
            return true;
        }
        if ("cancelled".equals(type)) {
            future.completeExceptionally(new CancellationException(firstNonBlank(
                    payload.get("message"), "Research generation cancelled")));
            return true;
        }
        Map<String, Object> progress = new LinkedHashMap<>(payload);
        progress.putIfAbsent("type", type);
        publishProgress(progressListener, progress);
        return false;
    }

    private void publishProgress(Consumer<Map<String, Object>> listener, Map<String, Object> event) {
        try {
            listener.accept(event);
        } catch (RuntimeException error) {
            logger.warn("Research progress listener failed; continuing generation", error);
        }
    }

    private void writeStatus(String generationId, Map<String, Object> status) {
        try {
            redisTemplate.opsForValue().set(statusKey(generationId), objectMapper.writeValueAsString(status), statusTtl);
        } catch (Exception error) {
            logger.warn("Failed to write research harness Redis status for generationId={}", generationId, error);
        }
    }

    private Map<String, Object> jsonMap(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(raw, MAP_TYPE);
    }

    private StreamOperations<String, String, String> streamOps() {
        return redisTemplate.opsForStream();
    }

    private String eventKey(String generationId) {
        return eventKeyPrefix + generationId;
    }

    private String statusKey(String generationId) {
        return statusKeyPrefix + generationId;
    }

    private String cancelKey(String generationId) {
        return cancelKeyPrefix + generationId;
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record ActiveRequest(CompletableFuture<ProductTurnResult> future) {
    }
}
