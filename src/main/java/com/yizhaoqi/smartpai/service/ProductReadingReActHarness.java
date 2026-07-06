package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ProductReadingReActHarness {

    private static final List<String> REQUIRED_TOOL_NAMES = List.of(
            "search_paper_candidates",
            "find_reading_locations"
    );
    private static final List<String> FORBIDDEN_OUTPUT_TOKENS = List.of(
            "paperId",
            "modelVersion",
            "chunkRef",
            "readingElementId",
            "matchedFields",
            "matchedField",
            "routingDiagnostics",
            "rank",
            "score"
    );
    private static final Pattern NUMBERED_CITATION_PATTERN = Pattern.compile("\\[\\d+]");

    private final LlmProviderRouter llmProviderRouter;
    private final ProductReadingToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ProductReadingTraceRecorder traceRecorder;

    public ProductReadingReActHarness(LlmProviderRouter llmProviderRouter,
                                      ProductReadingToolRegistry toolRegistry,
                                      ObjectMapper objectMapper,
                                      ProductReadingTraceRecorder traceRecorder) {
        this.llmProviderRouter = llmProviderRouter;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.traceRecorder = traceRecorder;
    }

    public ProductTurnResult run(ProductTurnRequest request) {
        Instant startedAt = Instant.now();
        ProductTurnRequest safeRequest = request == null
                ? new ProductTurnRequest(null, "", "", "", SourceScope.auto(), List.of(), Map.of(), ProductModelContext.defaults())
                : request;
        List<Map<String, Object>> llmCalls = new ArrayList<>();
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        List<ToolProgressEvent> progressEvents = new ArrayList<>();
        List<AgentToolRegistry.AgentTool> tools = toolRegistry.listTools();
        if (!validToolSurface(tools)) {
            ProductTurnResult result = failed(
                    "Product reading tool surface is invalid.",
                    progressEvents,
                    ProductStopReason.TOOL_FAILED
            );
            recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
            return result;
        }

        List<Map<String, Object>> messages = initialMessages(safeRequest);
        boolean toolSucceeded = false;
        for (int round = 0; round < safeRequest.modelContext().maxReActRounds(); round++) {
            LlmProviderRouter.ReActTurn turn = llmProviderRouter.completeReActTurn(
                    requesterId(safeRequest.userId()),
                    messages,
                    tools,
                    safeRequest.modelContext().maxCompletionTokens()
            );
            llmCalls.add(llmCall(round + 1, turn));
            List<LlmProviderRouter.ToolCallDecision> decisions = turn == null || turn.toolCalls() == null
                    ? List.of()
                    : turn.toolCalls();
            if (decisions.isEmpty()) {
                ProductTurnResult result = toolSucceeded
                        ? finalResult(turn == null ? "" : turn.content(), progressEvents)
                        : failed(
                        "Product reading tool call is required before the final answer.",
                        progressEvents,
                        ProductStopReason.ANSWER_SCHEMA_INVALID
                );
                recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
                return result;
            }
            messages.add(assistantMessage(turn));
            for (LlmProviderRouter.ToolCallDecision toolCall : decisions) {
                ToolProgressEvent progressEvent = new ToolProgressEvent("calling_tool", safeToolName(toolCall));
                progressEvents.add(progressEvent);
                safeRequest.progressListener().accept(progressEvent);
                Instant toolStartedAt = Instant.now();
                ProductToolResult toolResult = toolRegistry.execute(
                        safeToolName(toolCall),
                        safeArguments(toolCall),
                        new ProductToolContext(
                                safeRequest.userId(),
                                safeRequest.conversationId(),
                                safeRequest.generationId(),
                                safeRequest.lockedScope()
                        )
                );
                if (toolResult == null) {
                    toolResult = new ProductToolResult(
                            safeToolName(toolCall),
                            false,
                            Map.of("error", "reading_tool_returned_null"),
                            ProductToolEffect.ERROR
                    );
                }
                toolCalls.add(toolCall(round + 1, toolCall, toolResult, toolStartedAt, Instant.now()));
                messages.add(toolMessage(toolCall == null ? "" : toolCall.id(), toolResult.contentJson(objectMapper)));
                if (!toolResult.success()) {
                    ProductTurnResult result = failed(
                            "Product reading tool failed: " + toolResult.toolName(),
                            progressEvents,
                            ProductStopReason.TOOL_FAILED
                    );
                    recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
                    return result;
                }
                toolSucceeded = true;
                messages.add(navigationPolicyMessage());
            }
        }

        ProductTurnResult result = maxReactRoundsReached(progressEvents);
        recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
        return result;
    }

    private boolean validToolSurface(List<AgentToolRegistry.AgentTool> tools) {
        List<String> names = tools == null
                ? List.of()
                : tools.stream().map(AgentToolRegistry.AgentTool::name).toList();
        return REQUIRED_TOOL_NAMES.equals(names);
    }

    private List<Map<String, Object>> initialMessages(ProductTurnRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", systemPrompt(request)));
        messages.add(message("user", request.userMessage()));
        return messages;
    }

    private String systemPrompt(ProductTurnRequest request) {
        return """
                You are PaperLoom Product Reading ReAct Phase 1.
                Available tools are only search_paper_candidates and find_reading_locations.
                Use search_paper_candidates for paper candidate discovery.
                Use find_reading_locations only after explicit paperHandle values are available.
                Paper previews are navigation only, not Source Quotes.
                Reading-location previews are navigation only, not Source Quotes.
                locationRef values are navigation refs, not Source Quotes.
                Do not invent paperHandle, locationRef, or sourceQuoteRef values.
                Do not pass ordinals as tool input.
                Do not pass limit, topK, modelVersion, indexName, chunkRef, paperId, question, query, readingNeed, or semanticNeed.
                Phase 1 can answer candidate-list, navigation, clarification, and not-enough-source-quotes answers.
                Phase 1 cannot answer paper-content methods, results, limitations, comparisons, recommendation reasons, citations, or source-quoted answers.
                If the user asks for paper-content reasons, answer INSUFFICIENT_EVIDENCE and say read_locations / Source Quotes are required.
                Final answer must be one JSON AnswerEnvelope.
                PRODUCT_STATE is allowed for candidate-list and navigation answers.
                INSUFFICIENT_EVIDENCE is required for paper-content claims that need Source Quotes.
                EVIDENCE_ANSWER is forbidden in Phase 1.
                paperRef, evidenceRef, and citationRef are legacy identifiers for the old harness. Do not use them as reading tool arguments.
                Current user request:
                %s
                """.formatted(request.userMessage());
    }

    private ProductTurnResult finalResult(String rawContent, List<ToolProgressEvent> progressEvents) {
        AnswerEnvelope envelope;
        try {
            envelope = parseEnvelope(rawContent);
        } catch (Exception exception) {
            return failed("Answer envelope schema invalid.", progressEvents, ProductStopReason.ANSWER_SCHEMA_INVALID);
        }
        if (!validPhaseOneEnvelope(envelope)) {
            return failed(
                    "Phase 1 reading tools have no Source Quotes and cannot support evidence answers or paper-content claims.",
                    progressEvents,
                    ProductStopReason.ANSWER_SCHEMA_INVALID
            );
        }
        return new ProductTurnResult(
                envelope.answer(),
                envelope,
                List.of(),
                progressEvents,
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
    }

    private boolean validPhaseOneEnvelope(AnswerEnvelope envelope) {
        if (envelope == null) {
            return false;
        }
        if (envelope.answerType() == AnswerType.EVIDENCE_ANSWER) {
            return false;
        }
        if (!envelope.evidenceBasedClaims().isEmpty()) {
            return false;
        }
        String envelopeText = envelope.answer()
                + " " + envelope.stateClaims()
                + " " + envelope.limitations()
                + " " + envelope.nonEvidenceNotes()
                + " " + envelope.missingFields()
                + " " + envelope.reason();
        return !containsCitation(envelopeText) && !containsForbiddenOutputToken(envelopeText);
    }

    private boolean containsCitation(String text) {
        String value = text == null ? "" : text;
        return value.contains("{{evidenceRef:")
                || value.contains("sourceQuoteRef")
                || value.contains("citationRef")
                || NUMBERED_CITATION_PATTERN.matcher(value).find();
    }

    private boolean containsForbiddenOutputToken(String text) {
        String value = text == null ? "" : text;
        for (String token : FORBIDDEN_OUTPUT_TOKENS) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private AnswerEnvelope parseEnvelope(String rawContent) throws Exception {
        JsonNode node = objectMapper.readTree(extractJson(rawContent));
        return new AnswerEnvelope(
                AnswerType.valueOf(node.path("answerType").asText("")),
                node.path("answer").asText(""),
                listOfMaps(node.path("evidenceBasedClaims")),
                listOfMaps(node.path("stateClaims")),
                listOfStrings(node.path("limitations")),
                listOfStrings(node.path("nonEvidenceNotes")),
                listOfStrings(node.path("missingFields")),
                node.path("reason").asText("")
        );
    }

    private String extractJson(String rawContent) {
        String text = rawContent == null ? "" : rawContent.trim();
        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode item : node) {
            items.add(new LinkedHashMap<>(objectMapper.convertValue(item, Map.class)));
        }
        return items;
    }

    private List<String> listOfStrings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : node) {
            items.add(item.asText(""));
        }
        return items;
    }

    private ProductTurnResult failed(String message,
                                     List<ToolProgressEvent> progressEvents,
                                     ProductStopReason stopReason) {
        return new ProductTurnResult(
                message,
                new AnswerEnvelope(
                        AnswerType.CLARIFICATION_NEEDED,
                        message,
                        List.of(),
                        List.of(),
                        List.of(message),
                        List.of(),
                        List.of(),
                        stopReason.name()
                ),
                List.of(),
                progressEvents,
                stopReason,
                ProductResultStatus.FAILED
        );
    }

    private ProductTurnResult maxReactRoundsReached(List<ToolProgressEvent> progressEvents) {
        String message = "Reading ReAct round budget reached before final answer.";
        return new ProductTurnResult(
                message,
                new AnswerEnvelope(
                        AnswerType.CLARIFICATION_NEEDED,
                        message,
                        List.of(),
                        List.of(),
                        List.of(message),
                        List.of(),
                        List.of("react_round_budget"),
                        ProductStopReason.MAX_REACT_ROUNDS.name()
                ),
                List.of(),
                progressEvents,
                ProductStopReason.MAX_REACT_ROUNDS,
                ProductResultStatus.DEGRADED
        );
    }

    private void recordTrace(ProductTurnRequest request,
                             ProductTurnResult result,
                             List<Map<String, Object>> llmCalls,
                             List<Map<String, Object>> toolCalls,
                             Instant startedAt) {
        if (traceRecorder != null) {
            traceRecorder.recordReadingTurn(request, result, llmCalls, toolCalls, startedAt, Instant.now());
        }
    }

    private Map<String, Object> llmCall(int round, LlmProviderRouter.ReActTurn turn) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("round", round);
        call.put("content", turn == null ? "" : stringValue(turn.content()));
        call.put("toolCallCount", turn == null || turn.toolCalls() == null ? 0 : turn.toolCalls().size());
        call.put("finishReason", turn == null ? "" : stringValue(turn.finishReason()));
        call.put("promptTokens", turn == null ? 0 : turn.promptTokens());
        call.put("completionTokens", turn == null ? 0 : turn.completionTokens());
        return call;
    }

    private Map<String, Object> toolCall(int round,
                                         LlmProviderRouter.ToolCallDecision toolCall,
                                         ProductToolResult toolResult,
                                         Instant startedAt,
                                         Instant finishedAt) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("round", round);
        call.put("toolName", safeToolName(toolCall));
        call.put("argumentsJson", safeArguments(toolCall));
        call.put("success", toolResult.success());
        call.put("resultJson", toolResult.data());
        call.put("startedAt", startedAt == null ? null : startedAt.toString());
        call.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        return call;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role == null ? "" : role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private Map<String, Object> assistantMessage(LlmProviderRouter.ReActTurn turn) {
        if (turn != null && turn.assistantMessage() != null) {
            return turn.assistantMessage();
        }
        return message("assistant", turn == null ? "" : turn.content());
    }

    private Map<String, Object> toolMessage(String toolCallId, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId == null ? "" : toolCallId);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private Map<String, Object> navigationPolicyMessage() {
        return message(
                "user",
                "Treat this result as navigation only. Do not make paper-content claims. If source-quoted reasons are needed, answer INSUFFICIENT_EVIDENCE."
        );
    }

    private Map<String, Object> safeArguments(LlmProviderRouter.ToolCallDecision toolCall) {
        return toolCall == null || toolCall.arguments() == null ? Map.of() : toolCall.arguments();
    }

    private String safeToolName(LlmProviderRouter.ToolCallDecision toolCall) {
        return toolCall == null ? "" : stringValue(toolCall.name());
    }

    private String requesterId(Long userId) {
        return userId == null ? "anonymous" : String.valueOf(userId);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
