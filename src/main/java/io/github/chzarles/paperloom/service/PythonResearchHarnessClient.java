package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
@ConditionalOnProperty(name = "research-harness.transport", havingValue = "http", matchIfMissing = true)
public class PythonResearchHarnessClient implements ResearchHarnessTransport {

    private static final Logger logger = LoggerFactory.getLogger(PythonResearchHarnessClient.class);

    private final ObjectMapper objectMapper;
    private final UsageQuotaService usageQuotaService;
    private final ResearchHarnessPayloadFactory payloadFactory;
    private final ResearchHarnessResultMapper resultMapper;
    private final HttpClient httpClient;
    private final URI streamUri;
    private final String internalToken;
    private final ExecutorService requestExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "research-harness-http");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, ActiveRequest> activeRequests = new ConcurrentHashMap<>();

    public PythonResearchHarnessClient(
            ObjectMapper objectMapper,
            UsageQuotaService usageQuotaService,
            ResearchHarnessPayloadFactory payloadFactory,
            ResearchHarnessResultMapper resultMapper,
            @Value("${research-harness.base-url:http://127.0.0.1:8091}") String baseUrl,
            @Value("${research-harness.internal-token:}") String internalToken) {
        this.objectMapper = objectMapper;
        this.usageQuotaService = usageQuotaService;
        this.payloadFactory = payloadFactory;
        this.resultMapper = resultMapper;
        this.streamUri = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/research/stream");
        this.internalToken = internalToken == null ? "" : internalToken.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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
        Map<String, Object> body = payloadFactory.requestBody(request);
        int maxCompletionTokens = request.modelContext().maxCompletionTokens() > 0
                ? request.modelContext().maxCompletionTokens()
                : 3000;
        UsageQuotaService.TokenReservation reservation = usageQuotaService.reserveLlmTokens(
                String.valueOf(request.userId()),
                payloadFactory.estimatedPromptTokens(request),
                maxCompletionTokens
        );
        CompletableFuture<ProductTurnResult> future = new CompletableFuture<>();
        String generationId = request.generationId();
        AtomicBoolean reservationFinished = new AtomicBoolean(false);
        AtomicBoolean failureEventPublished = new AtomicBoolean(false);
        Consumer<Map<String, Object>> trackedProgressListener = event -> {
            if ("job_failed".equals(stringValue(event.get("type")))) {
                failureEventPublished.set(true);
            }
            progressListener.accept(event);
        };
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                Map<String, Object> response = executeStream(body, trackedProgressListener);
                if (future.isDone()) {
                    return null;
                }
                Map<String, Object> usage = resultMapper.objectMap(response.get("usage"));
                usageQuotaService.settleReservation(reservation, intValue(usage.get("total_tokens"), 1));
                reservationFinished.set(true);
                future.complete(resultMapper.toProductResult(request, response));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(new CancellationException("Research generation cancelled"));
            } catch (CancellationException error) {
                future.completeExceptionally(error);
            } catch (Exception error) {
                if (failureEventPublished.compareAndSet(false, true)) {
                    publishProgress(progressListener, Map.of(
                            "type", "job_failed",
                            "status", "failed",
                            "errorType", error.getClass().getSimpleName(),
                            "message", firstNonBlank(error.getMessage(), "The Python research harness failed")
                    ));
                }
                future.completeExceptionally(error);
            }
            return null;
        });
        ActiveRequest active = new ActiveRequest(task, future);
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
            requestExecutor.execute(task);
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
        ActiveRequest active = activeRequests.get(generationId);
        if (active == null) {
            return;
        }
        active.future().completeExceptionally(new CancellationException("Research generation cancelled"));
        active.task().cancel(true);
    }

    @PreDestroy
    void shutdown() {
        activeRequests.values().forEach(active -> {
            active.future().completeExceptionally(new CancellationException("Research harness client stopped"));
            active.task().cancel(true);
        });
        requestExecutor.shutdownNow();
    }

    private Map<String, Object> executeStream(Map<String, Object> body,
                                              Consumer<Map<String, Object>> progressListener)
            throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(streamUri)
                .header("Content-Type", "application/json")
                .header("Accept", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (!internalToken.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + internalToken);
        }

        HttpResponse<InputStream> response = httpClient.send(
                requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Python research harness returned HTTP " + response.statusCode()
                    + ": " + new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        }

        Map<String, Object> result = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Research generation cancelled");
                }
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> item = objectMapper.readValue(
                        line, new TypeReference<LinkedHashMap<String, Object>>() {});
                String type = stringValue(item.get("type"));
                if ("result".equals(type)) {
                    result = resultMapper.objectMap(item.get("payload"));
                } else if ("error".equals(type)) {
                    throw new IllegalStateException(firstNonBlank(
                            item.get("message"), item.get("errorType"), "The Python research harness failed"));
                } else {
                    publishProgress(progressListener, item);
                }
            }
        }
        if (result == null || result.isEmpty()) {
            throw new IllegalStateException("The Python research harness stream ended without a result");
        }
        return result;
    }

    private void publishProgress(Consumer<Map<String, Object>> listener, Map<String, Object> event) {
        try {
            listener.accept(event);
        } catch (RuntimeException error) {
            logger.warn("Research progress listener failed; continuing generation", error);
        }
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

    private record ActiveRequest(FutureTask<Void> task, CompletableFuture<ProductTurnResult> future) {
    }
}
