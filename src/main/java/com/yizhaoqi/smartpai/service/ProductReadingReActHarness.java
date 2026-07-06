package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ProductReadingReActHarness {

    private static final List<String> REQUIRED_TOOL_NAMES = List.of(
            "search_paper_candidates",
            "find_reading_locations",
            "read_locations"
    );
    private static final List<String> FORBIDDEN_OUTPUT_TOKENS = List.of(
            internalToken("paper", "Id"),
            internalToken("model", "Version"),
            internalToken("chunk", "Ref"),
            internalToken("reading", "ElementId"),
            internalToken("matched", "Fields"),
            internalToken("matched", "Field"),
            internalToken("routing", "Diagnostics"),
            internalToken("split", "PolicyVersion"),
            internalToken("content", "Hash")
    );
    private static final Pattern NUMBERED_CITATION_PATTERN = Pattern.compile("\\[\\d+]");
    private static final Pattern SOURCE_QUOTE_MARKER_PATTERN =
            Pattern.compile("\\{\\{\\s*sourceQuoteRef\\s*:\\s*(source_quote_[A-Za-z0-9_-]+)\\s*}}");

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

        ReadingTurnState state = new ReadingTurnState();
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
                        ? finalResult(turn == null ? "" : turn.content(), progressEvents, state)
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
                ToolCallValidation validation = validateToolCall(toolCall, state);
                if (!validation.isAllowed()) {
                    ProductToolResult rejected = new ProductToolResult(
                            safeToolName(toolCall),
                            false,
                            Map.of("error", validation.reason()),
                            ProductToolEffect.ERROR
                    );
                    toolCalls.add(toolCall(round + 1, toolCall, rejected, Instant.now(), Instant.now()));
                    ProductTurnResult result = failed(
                            "Product reading tool rejected: " + validation.reason(),
                            progressEvents,
                            ProductStopReason.TOOL_FAILED
                    );
                    recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
                    return result;
                }
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
                updateState(toolResult, state);
                toolSucceeded = true;
                messages.add(toolResultPolicyMessage(toolResult));
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

    private ToolCallValidation validateToolCall(LlmProviderRouter.ToolCallDecision toolCall, ReadingTurnState state) {
        String toolName = safeToolName(toolCall);
        Map<String, Object> arguments = safeArguments(toolCall);
        if ("find_reading_locations".equals(toolName)) {
            List<String> paperHandles = stringList(arguments.get("paperHandles"));
            if (paperHandles.isEmpty() || !state.disclosedPaperHandles.containsAll(paperHandles)) {
                return ToolCallValidation.rejected("hidden_paper_handle");
            }
        }
        if ("read_locations".equals(toolName)) {
            List<String> locationRefs = stringList(arguments.get("locationRefs"));
            if (locationRefs.isEmpty() || !state.disclosedLocationRefs.containsAll(locationRefs)) {
                return ToolCallValidation.rejected("hidden_location_ref");
            }
        }
        return ToolCallValidation.allowed();
    }

    private void updateState(ProductToolResult toolResult, ReadingTurnState state) {
        if (toolResult == null || toolResult.data() == null) {
            return;
        }
        String toolName = toolResult.toolName();
        if ("search_paper_candidates".equals(toolName)) {
            for (Map<String, Object> item : mapList(toolResult.data().get("items"))) {
                String paperHandle = stringValue(item.get("paperHandle"));
                if (!paperHandle.isBlank()) {
                    state.disclosedPaperHandles.add(paperHandle);
                }
            }
            return;
        }
        if ("find_reading_locations".equals(toolName)) {
            for (Map<String, Object> candidate : mapList(toolResult.data().get("candidates"))) {
                String locationRef = stringValue(candidate.get("locationRef"));
                if (!locationRef.isBlank()) {
                    state.disclosedLocationRefs.add(locationRef);
                }
            }
            return;
        }
        if ("read_locations".equals(toolName)) {
            for (Map<String, Object> sourceQuote : mapList(toolResult.data().get("sourceQuotes"))) {
                String sourceQuoteRef = stringValue(sourceQuote.get("sourceQuoteRef"));
                if (!sourceQuoteRef.isBlank()) {
                    state.allowedSourceQuoteRefs.add(sourceQuoteRef);
                    state.sourceQuotePayloads.put(sourceQuoteRef, new LinkedHashMap<>(sourceQuote));
                }
            }
        }
    }

    private List<Map<String, Object>> initialMessages(ProductTurnRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", systemPrompt(request)));
        messages.add(message("user", request.userMessage()));
        return messages;
    }

    private String systemPrompt(ProductTurnRequest request) {
        return """
                You are PaperLoom Product Reading ReAct Source Quote MVP.
                Available tools are exactly search_paper_candidates, find_reading_locations, and read_locations.
                Use search_paper_candidates for paper candidate discovery.
                Use find_reading_locations only after explicit paperHandle values were returned by search_paper_candidates in this turn.
                Use read_locations only after explicit locationRef values were returned by find_reading_locations in this turn.
                Paper previews and reading-location previews are navigation only, not Source Quotes.
                read_locations is the only Source Quote tool in this slice.
                Do not invent paperHandle, locationRef, or sourceQuoteRef values.
                Do not pass ordinals as tool input.
                Do not pass limit, topK, modelVersion, indexName, chunkRef, paperId, question, query, readingNeed, semanticNeed, sourceQuoteRef, splitPolicyVersion, or contentHash.
                Candidate-list and navigation answers use PRODUCT_STATE.
                Paper-content claims require EVIDENCE_ANSWER with sourceQuoteRefs returned by read_locations in this turn.
                In EVIDENCE_ANSWER text, cite Source Quotes with markers exactly like {{sourceQuoteRef:source_quote_...}}.
                In evidenceBasedClaims, use sourceQuoteRefs, not evidenceRefs.
                If Source Quotes are unavailable or insufficient, answer INSUFFICIENT_EVIDENCE without unsupported paper facts.
                Final answer must be one JSON AnswerEnvelope.
                paperRef, evidenceRef, and citationRef are legacy identifiers for the old harness. Do not use them as reading tool arguments or citation support.
                Current user request:
                %s
                """.formatted(request.userMessage());
    }

    private ProductTurnResult finalResult(String rawContent,
                                          List<ToolProgressEvent> progressEvents,
                                          ReadingTurnState state) {
        AnswerEnvelope envelope;
        try {
            envelope = parseEnvelope(rawContent);
        } catch (Exception exception) {
            return failed("Answer envelope schema invalid.", progressEvents, ProductStopReason.ANSWER_SCHEMA_INVALID);
        }
        if (containsForbiddenOutputToken(envelopeText(envelope))
                || NUMBERED_CITATION_PATTERN.matcher(envelopeText(envelope)).find()) {
            return failed("Answer envelope contains forbidden reading identifiers or citations.",
                    progressEvents, ProductStopReason.ANSWER_SCHEMA_INVALID);
        }
        if (envelope.answerType() == AnswerType.EVIDENCE_ANSWER) {
            CitationValidation validation = validateSourceQuoteAnswer(envelope, state);
            if (!validation.isValid()) {
                return failed(validation.reason(), progressEvents, ProductStopReason.CITATION_VALIDATION_FAILED);
            }
            CitationRender render = renderSourceQuoteCitations(envelope.answer(), state);
            return new ProductTurnResult(
                    render.markdown(),
                    envelope,
                    render.references(),
                    progressEvents,
                    ProductStopReason.COMPLETED,
                    ProductResultStatus.COMPLETED
            );
        }
        if (containsLegacyCitation(envelopeText(envelope))
                || !envelope.evidenceBasedClaims().isEmpty()
                || !sourceQuoteMarkers(envelope.answer()).isEmpty()) {
            return failed("Non-evidence reading answers cannot include Source Quote support.",
                    progressEvents, ProductStopReason.CITATION_VALIDATION_FAILED);
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

    private CitationValidation validateSourceQuoteAnswer(AnswerEnvelope envelope, ReadingTurnState state) {
        Set<String> visibleRefs = sourceQuoteMarkers(envelope.answer());
        Set<String> claimRefs = claimSourceQuoteRefs(envelope);
        if (visibleRefs.isEmpty()) {
            return CitationValidation.invalid("Source-quoted answer requires visible sourceQuoteRef markers.");
        }
        if (claimRefs.isEmpty()) {
            return CitationValidation.invalid("Source-quoted answer requires claim-level sourceQuoteRefs.");
        }
        if (!visibleRefs.equals(claimRefs)) {
            return CitationValidation.invalid("Visible sourceQuoteRef markers and claim-level sourceQuoteRefs must match.");
        }
        if (!state.allowedSourceQuoteRefs.containsAll(visibleRefs)) {
            return CitationValidation.invalid("Source-quoted answer contains unreturned sourceQuoteRef values.");
        }
        return CitationValidation.valid();
    }

    private Set<String> claimSourceQuoteRefs(AnswerEnvelope envelope) {
        Set<String> refs = new LinkedHashSet<>();
        for (Map<String, Object> claim : envelope.evidenceBasedClaims()) {
            if (containsForbiddenClaimSupportKey(claim)) {
                return Set.of();
            }
            refs.addAll(stringList(claim.get("sourceQuoteRefs")));
        }
        return refs;
    }

    private boolean containsForbiddenClaimSupportKey(Map<String, Object> claim) {
        return claim.containsKey("evidenceRefs")
                || claim.containsKey("evidenceRef")
                || claim.containsKey("citationRef")
                || claim.containsKey("locationRefs")
                || claim.containsKey("locationRef")
                || claim.containsKey("paperHandles")
                || claim.containsKey("paperHandle");
    }

    private Set<String> sourceQuoteMarkers(String text) {
        Set<String> refs = new LinkedHashSet<>();
        Matcher matcher = SOURCE_QUOTE_MARKER_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            refs.add(matcher.group(1));
        }
        return refs;
    }

    private CitationRender renderSourceQuoteCitations(String answer, ReadingTurnState state) {
        Matcher matcher = SOURCE_QUOTE_MARKER_PATTERN.matcher(answer == null ? "" : answer);
        StringBuffer markdown = new StringBuffer();
        Map<String, Integer> numbersByRef = new LinkedHashMap<>();
        List<Map<String, Object>> references = new ArrayList<>();
        while (matcher.find()) {
            String sourceQuoteRef = matcher.group(1);
            Integer number = numbersByRef.get(sourceQuoteRef);
            if (number == null) {
                number = numbersByRef.size() + 1;
                numbersByRef.put(sourceQuoteRef, number);
                references.add(reference(number, sourceQuoteRef, state.sourceQuotePayloads.get(sourceQuoteRef)));
            }
            matcher.appendReplacement(markdown, Matcher.quoteReplacement("[" + number + "]"));
        }
        matcher.appendTail(markdown);
        return new CitationRender(markdown.toString(), references);
    }

    private Map<String, Object> reference(Integer referenceNumber,
                                          String sourceQuoteRef,
                                          Map<String, Object> sourceQuotePayload) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("referenceNumber", referenceNumber);
        reference.put("sourceQuoteRef", sourceQuoteRef);
        if (sourceQuotePayload != null) {
            copyIfPresent(reference, sourceQuotePayload, "paperHandle");
            copyIfPresent(reference, sourceQuotePayload, "paperTitle");
            copyIfPresent(reference, sourceQuotePayload, "locationRef");
            copyIfPresent(reference, sourceQuotePayload, "locationType");
            copyIfPresent(reference, sourceQuotePayload, "pageNumber");
            copyIfPresent(reference, sourceQuotePayload, "pageEndNumber");
            copyIfPresent(reference, sourceQuotePayload, "sectionTitle");
            copyIfPresent(reference, sourceQuotePayload, "contentKind");
            copyIfPresent(reference, sourceQuotePayload, "content");
        }
        return reference;
    }

    private void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private boolean containsLegacyCitation(String text) {
        String value = text == null ? "" : text;
        return value.contains("{{evidenceRef:") || value.contains("citationRef") || value.contains("evidenceRef");
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

    private String envelopeText(AnswerEnvelope envelope) {
        return envelope.answer()
                + " " + envelope.evidenceBasedClaims()
                + " " + envelope.stateClaims()
                + " " + envelope.limitations()
                + " " + envelope.nonEvidenceNotes()
                + " " + envelope.missingFields()
                + " " + envelope.reason();
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> rawItems)) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object rawItem : rawItems) {
            if (rawItem instanceof Map<?, ?> map) {
                items.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return items;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> rawValues)) {
            return List.of();
        }
        return rawValues.stream()
                .map(this::stringValue)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
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

    private Map<String, Object> toolResultPolicyMessage(ProductToolResult toolResult) {
        if (toolResult != null && "read_locations".equals(toolResult.toolName())) {
            return message("user", """
                    Source Quotes from read_locations may support EVIDENCE_ANSWER.
                    Cite them with {{sourceQuoteRef:source_quote_...}} markers and use sourceQuoteRefs in evidenceBasedClaims.
                    Do not use evidenceRefs, citationRef, locationRef, or paperHandle as citation support.
                    """);
        }
        return message(
                "user",
                "Treat this result as navigation only. If paper-content claims are needed, call read_locations on disclosed locationRefs."
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

    private static String internalToken(String prefix, String suffix) {
        return prefix + suffix;
    }

    private static class ReadingTurnState {
        private final Set<String> disclosedPaperHandles = new LinkedHashSet<>();
        private final Set<String> disclosedLocationRefs = new LinkedHashSet<>();
        private final Set<String> allowedSourceQuoteRefs = new LinkedHashSet<>();
        private final Map<String, Map<String, Object>> sourceQuotePayloads = new LinkedHashMap<>();
    }

    private record ToolCallValidation(boolean isAllowed, String reason) {
        static ToolCallValidation allowed() {
            return new ToolCallValidation(true, "");
        }

        static ToolCallValidation rejected(String reason) {
            return new ToolCallValidation(false, reason);
        }
    }

    private record CitationValidation(boolean isValid, String reason) {
        static CitationValidation valid() {
            return new CitationValidation(true, "");
        }

        static CitationValidation invalid(String reason) {
            return new CitationValidation(false, reason);
        }
    }

    private record CitationRender(String markdown, List<Map<String, Object>> references) {
    }
}
