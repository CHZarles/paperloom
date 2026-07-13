package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
public class PythonResearchHarnessClient {

    private static final Logger logger = LoggerFactory.getLogger(PythonResearchHarnessClient.class);

    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final UsageQuotaService usageQuotaService;
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
            RateLimitService rateLimitService,
            UsageQuotaService usageQuotaService,
            @Value("${research-harness.base-url:http://127.0.0.1:8091}") String baseUrl,
            @Value("${research-harness.internal-token:}") String internalToken) {
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
        this.usageQuotaService = usageQuotaService;
        this.streamUri = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/research/stream");
        this.internalToken = internalToken == null ? "" : internalToken.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public ProductTurnResult run(ProductTurnRequest request) {
        return submit(request, event -> {}).join();
    }

    public CompletableFuture<ProductTurnResult> submit(ProductTurnRequest request,
                                                       Consumer<Map<String, Object>> progressListener) {
        if (request.lockedScope().paperIds().isEmpty()) {
            throw new IllegalArgumentException("The Python harness requires an authorized paper scope");
        }
        Map<String, Object> body = requestBody(request);
        int maxCompletionTokens = request.modelContext().maxCompletionTokens() > 0
                ? request.modelContext().maxCompletionTokens()
                : 3000;
        UsageQuotaService.TokenReservationBundle reservation = rateLimitService.reserveLlmUsage(
                String.valueOf(request.userId()),
                estimatedPromptTokens(request),
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
                Map<String, Object> usage = objectMap(response.get("usage"));
                usageQuotaService.settleReservation(reservation, intValue(usage.get("total_tokens"), 1));
                reservationFinished.set(true);
                future.complete(toProductResult(request, response));
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

    private Map<String, Object> requestBody(ProductTurnRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", request.generationId());
        body.put("conversation_id", request.conversationId());
        body.put("user_message", request.userMessage());
        body.put("history", request.history());
        body.put("scope", Map.of(
                "mode", request.lockedScope().mode().name(),
                "paper_ids", request.lockedScope().paperIds(),
                "reference_focus", request.memory()
        ));
        body.put("research_memory", researchMemory(request.memory()));
        body.put("options", Map.of(
                "include_trace", true,
                "max_completion_tokens", request.modelContext().maxCompletionTokens()
        ));
        return body;
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
                    result = objectMap(item.get("payload"));
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

    private int estimatedPromptTokens(ProductTurnRequest request) {
        int characters = request.userMessage().length();
        for (Map<String, String> message : request.history()) {
            characters += message.getOrDefault("role", "").length();
            characters += message.getOrDefault("content", "").length();
        }
        return Math.max(1, (characters + 3) / 4);
    }

    private Map<String, Object> researchMemory(Map<String, Object> memory) {
        if (memory == null || memory.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        copy(memory, result, "selected_paper_ids");
        copy(memory, result, "selected_evidence_ids");
        copy(memory, result, "previous_evidence");
        return result;
    }

    private ProductTurnResult toProductResult(ProductTurnRequest request, Map<String, Object> response) {
        Map<String, Object> answer = objectMap(response.get("answer"));
        String markdown = stringValue(answer.get("markdown"));
        String status = stringValue(response.get("status"));
        List<Map<String, Object>> references = references(response.get("citations"));
        ProductResultStatus resultStatus = switch (status) {
            case "FAILED_TECHNICAL" -> ProductResultStatus.FAILED;
            case "INCOMPLETE_PRECISE" -> ProductResultStatus.INCOMPLETE_PRECISE;
            default -> ProductResultStatus.COMPLETED;
        };
        AnswerType answerType = switch (status) {
            case "NEEDS_CLARIFICATION" -> AnswerType.CLARIFICATION_NEEDED;
            case "INCOMPLETE_PRECISE" -> AnswerType.INSUFFICIENT_EVIDENCE;
            default -> references.isEmpty() ? AnswerType.NON_EVIDENCE : AnswerType.EVIDENCE_ANSWER;
        };
        Map<String, Object> trace = objectMap(response.get("trace"));
        AnswerEnvelope envelope = new AnswerEnvelope(
                answerType,
                markdown,
                List.of(),
                List.of(),
                resultStatus == ProductResultStatus.INCOMPLETE_PRECISE
                        ? List.of("The available paper evidence did not fully support the request.")
                        : List.of(),
                List.of(),
                List.of(),
                stringValue(trace.get("finish_reason"))
        );
        return new ProductTurnResult(
                markdown,
                envelope,
                references,
                List.of(),
                paperChoices(trace.get("paper_candidates")),
                readingArtifacts(request.userMessage(), trace, references, resultStatus),
                readingStatePatch(references),
                ReadingResearchTrace.empty(),
                resultStatus == ProductResultStatus.FAILED ? ProductStopReason.TOOL_FAILED : ProductStopReason.COMPLETED,
                resultStatus
        );
    }

    private List<Map<String, Object>> references(Object rawCitations) {
        List<Map<String, Object>> result = new ArrayList<>();
        int fallbackNumber = 1;
        for (Map<String, Object> citation : mapList(rawCitations)) {
            int referenceNumber = intValue(citation.get("reference_number"), fallbackNumber++);
            String evidenceId = stringValue(citation.get("evidence_id"));
            String quote = stringValue(citation.get("span_text"));
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("referenceNumber", referenceNumber);
            reference.put("evidenceRef", evidenceId);
            reference.put("paperId", citation.get("paper_id"));
            reference.put("paperTitle", citation.get("title"));
            reference.put("originalFilename", citation.get("original_filename"));
            reference.put("pageNumber", citation.get("page"));
            reference.put("sectionTitle", citation.get("section"));
            reference.put("locationRef", firstNonBlank(citation.get("location_ref"), citation.get("location")));
            reference.put("elementType", citation.get("element_type"));
            reference.put("sourceKind", citation.get("source_kind"));
            reference.put("bboxJson", firstNonBlank(citation.get("bbox_json"), citation.get("bbox_or_cell_ref")));
            reference.put("parserName", citation.get("parser_name"));
            reference.put("parserVersion", citation.get("parser_version"));
            reference.put("tableId", citation.get("table_id"));
            reference.put("figureId", citation.get("figure_id"));
            reference.put("formulaId", citation.get("formula_id"));
            reference.put("content", quote);
            reference.put("anchorText", quote);
            reference.put("matchedText", quote);
            reference.put("evidenceSnippet", quote);
            reference.put("retrievalMode", "PYTHON_RESEARCH_HARNESS");
            reference.put("retrievalLabel", "Python research harness evidence");
            reference.put("retrievalRoute", "PYTHON_RESEARCH_HARNESS");
            reference.put("citationRef", "[" + referenceNumber + "]");
            reference.put("score", citation.get("relevance_score"));
            reference.put("evidenceAssetLevel", "TEXT");
            reference.put("pdfEvidenceAvailable", booleanValue(citation.get("pdf_evidence_available")));
            reference.put("pageScreenshotAvailable", booleanValue(citation.get("page_screenshot_available")));
            reference.put("tableScreenshotAvailable", booleanValue(citation.get("table_screenshot_available")));
            reference.put("figureScreenshotAvailable", booleanValue(citation.get("figure_screenshot_available")));
            result.add(reference);
        }
        return List.copyOf(result);
    }

    private ReadingTurnArtifacts readingArtifacts(String question,
                                                  Map<String, Object> trace,
                                                  List<Map<String, Object>> references,
                                                  ProductResultStatus resultStatus) {
        List<ReadingTurnArtifacts.TraceStep> steps = mapList(trace.get("tool_calls")).stream()
                .map(call -> new ReadingTurnArtifacts.TraceStep(
                        stringValue(call.get("tool_name")),
                        stringValue(call.get("tool_name")),
                        "",
                        "completed"
                ))
                .toList();
        List<ReadingTurnArtifacts.ClaimEvidenceRow> rows = references.stream()
                .map(reference -> new ReadingTurnArtifacts.ClaimEvidenceRow(
                        "",
                        stringValue(reference.get("content")),
                        stringValue(reference.get("citationRef")),
                        "",
                        stringValue(reference.get("paperId")),
                        "",
                        stringValue(reference.get("paperTitle")),
                        stringValue(reference.get("locationRef")),
                        stringValue(reference.get("sectionTitle")),
                        stringValue(reference.get("elementType")),
                        List.of(),
                        List.of()
                ))
                .toList();
        ReadingTurnArtifacts.ResearchTraceSummary summary = new ReadingTurnArtifacts.ResearchTraceSummary(
                steps,
                new ReadingTurnArtifacts.EvidenceSummary(references.size(), 0, 0, List.of()),
                new ReadingTurnArtifacts.ClaimSummary(rows.size(), rows.size(), 0, 0),
                new ReadingTurnArtifacts.VerificationSummary(
                        resultStatus != ProductResultStatus.FAILED,
                        resultStatus.name(),
                        stringValue(trace.get("finish_reason")),
                        references.isEmpty() ? "NOT_REQUIRED_OR_UNAVAILABLE" : "VERIFIED",
                        0,
                        0
                )
        );
        return new ReadingTurnArtifacts(
                "reading-turn-artifacts/v1",
                new ReadingTurnArtifacts.GoalCard(question, "Authorized paper scope", null, true, List.of()),
                ReadingIntentFrame.empty(question),
                ReadingTurnArtifacts.PaperShortlist.empty(),
                ReadingTurnArtifacts.ReadingPlan.empty(),
                new ReadingTurnArtifacts.ClaimEvidencePanel(rows),
                ReadingTurnArtifacts.MissingEvidence.empty(),
                List.of(),
                List.of(),
                summary
        );
    }

    private ReadingStatePatch readingStatePatch(List<Map<String, Object>> references) {
        if (references.isEmpty()) {
            return ReadingStatePatch.empty();
        }
        Map<String, Object> first = references.get(0);
        return new ReadingStatePatch(
                new ReadingStatePatch.SelectedPaper(
                        stringValue(first.get("paperId")),
                        "",
                        stringValue(first.get("paperTitle")),
                        ""
                ),
                new ReadingStatePatch.SelectedLocation(
                        stringValue(first.get("paperId")),
                        "",
                        stringValue(first.get("locationRef")),
                        stringValue(first.get("sectionTitle"))
                ),
                null,
                List.of()
        );
    }

    private List<Map<String, Object>> paperChoices(Object rawCandidates) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> candidate : mapList(rawCandidates)) {
            String paperId = stringValue(candidate.get("paper_id"));
            if (paperId.isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "READING_PAPER_CHOICE");
            item.put("sourceTool", "search_paper_candidates");
            item.put("paperHandle", "paper_handle_" + paperId);
            item.put("title", candidate.get("title"));
            item.put("authors", candidate.get("authors"));
            item.put("year", candidate.get("year"));
            item.put("venue", candidate.get("venue"));
            result.add(item);
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> mapped = objectMap(item);
            if (!mapped.isEmpty()) {
                result.add(mapped);
            }
        }
        return result;
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return Map.of();
        }
        return objectMapper.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
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

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(stringValue(value)) || "1".equals(stringValue(value));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record ActiveRequest(FutureTask<Void> task, CompletableFuture<ProductTurnResult> future) {
    }
}
