package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ProductReadingReActHarness {

    private static final List<String> REQUIRED_TOOL_NAMES = List.of(
            "get_session_state",
            "list_papers",
            "search_paper_candidates",
            "find_papers_by_identity",
            "get_paper_outline",
            "list_paper_locations",
            "find_reading_locations",
            "read_locations",
            "trace_source_quotes"
    );
    private static final int MAX_CLICKED_SOURCE_QUOTE_REFS = 20;
    private static final int MAX_CLICKED_PAPER_HANDLES = 20;
    private static final int MAX_PRODUCT_STATE_ITEMS = 10;
    private static final int MAX_LOCATION_FALLBACK_ITEMS = 8;
    private static final int MAX_SOURCE_QUOTE_FALLBACK_ITEMS = 5;
    private static final String READING_PAPER_CHOICE_KIND = "READING_PAPER_CHOICE";
    private static final String LIST_PAPERS_TOOL_NAME = "list_papers";
    private static final String SEARCH_TOOL_NAME = "search_paper_candidates";
    private static final String IDENTITY_TOOL_NAME = "find_papers_by_identity";
    private static final String LIST_LOCATIONS_TOOL_NAME = "list_paper_locations";
    private static final String LOCATION_TOOL_NAME = "find_reading_locations";
    private static final String READ_LOCATIONS_TOOL_NAME = "read_locations";
    private static final String SEARCH_PAPERS_ACTION = "SEARCH_PAPERS";
    private static final String LIST_LOCATIONS_ACTION = "LIST_LOCATIONS";
    private static final String FIND_LOCATIONS_ACTION = "FIND_LOCATIONS";
    private static final Set<String> PAPER_CHOICE_SOURCE_TOOLS = Set.of(
            LIST_PAPERS_TOOL_NAME,
            SEARCH_TOOL_NAME,
            IDENTITY_TOOL_NAME
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
    private static final Pattern SOURCE_QUOTE_REF_PATTERN =
            Pattern.compile("^source_quote_[A-Za-z0-9_-]+$");
    private static final Pattern PAPER_HANDLE_PATTERN =
            Pattern.compile("^paper_handle_[A-Za-z0-9_-]+$");
    private static final String ANSWER_SCHEMA_INVALID_MESSAGE = "Answer envelope schema invalid.";
    private static final String SESSION_TOOL_NAME = "get_session_state";

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
        List<ToolDefinition> tools = toolRegistry.listTools();
        if (!validToolSurface(tools)) {
            ProductTurnResult result = failed(
                    "Product reading tool surface is invalid.",
                    progressEvents,
                    ProductStopReason.TOOL_FAILED
            );
            recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
            return result;
        }

        ReadingTurnState state = new ReadingTurnState(
                clickedSourceQuoteRefs(safeRequest.memory()),
                clickedPaperHandles(safeRequest.memory()),
                readingTurnAction(safeRequest.memory())
        );
        List<Map<String, Object>> messages = initialMessages(
                safeRequest,
                state.clickedSourceQuoteRefs,
                state.clickedPaperHandles,
                state.readingAction
        );
        boolean toolSucceeded = false;
        for (int round = 0; round < safeRequest.modelContext().maxReActRounds(); round++) {
            LlmProviderRouter.ReActTurn turn = llmProviderRouter.completeReActTurn(
                    requesterId(safeRequest.userId()),
                    messageSnapshot(messages),
                    tools,
                    safeRequest.modelContext().maxCompletionTokens()
            );
            llmCalls.add(llmCall(round + 1, turn));
            List<LlmProviderRouter.ToolCallDecision> decisions = turn == null || turn.toolCalls() == null
                    ? List.of()
                    : turn.toolCalls();
            if (decisions.isEmpty()) {
                if (!toolSucceeded) {
                    SyntheticToolExecution structuredActionExecution = executeStructuredActionToolIfAvailable(
                            safeRequest,
                            state,
                            progressEvents,
                            toolCalls,
                            messages,
                            round + 1
                    );
                    if (structuredActionExecution.terminalResult() != null) {
                        ProductTurnResult result = structuredActionExecution.terminalResult();
                        recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
                        return result;
                    }
                    if (structuredActionExecution.executed()) {
                        toolSucceeded = true;
                        messages.add(toolResultsPolicyMessage(List.of(structuredActionExecution.toolResult())));
                        continue;
                    }
                    if (!tools.isEmpty() && round + 1 < safeRequest.modelContext().maxReActRounds()) {
                        messages.add(assistantMessage(turn));
                        messages.add(firstReadingToolCallRequiredMessage(state));
                        continue;
                    }
                    ProductTurnResult result = failed(
                            "Product reading tool call is required before the final answer.",
                            progressEvents,
                            ProductStopReason.ANSWER_SCHEMA_INVALID
                    );
                    recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
                    return result;
                }
                if (needsReadAfterSemanticLocationSearchBeforeFinal(state)) {
                    SyntheticToolExecution readExecution = executeReadAfterSemanticLocationSearchIfAvailable(
                            safeRequest,
                            state,
                            progressEvents,
                            toolCalls,
                            messages,
                            round + 1
                    );
                    if (readExecution.terminalResult() != null) {
                        ProductTurnResult result = readExecution.terminalResult();
                        recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
                        return result;
                    }
                    if (readExecution.executed()) {
                        messages.add(toolResultsPolicyMessage(List.of(readExecution.toolResult())));
                        continue;
                    }
                }
                if (needsReadAfterSemanticLocationSearchBeforeFinal(state)
                        && round + 1 < safeRequest.modelContext().maxReActRounds()) {
                    messages.add(assistantMessage(turn));
                    messages.add(readLocationsRequiredAfterSemanticLocationsMessage());
                    continue;
                }
                if (needsReadAfterSemanticLocationSearchBeforeFinal(state)) {
                    ProductTurnResult result = maxReactRoundsReached(progressEvents, state);
                    recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
                    return result;
                }
                ProductTurnResult result = finalResultOrProductStateFallback(
                        turn == null ? "" : turn.content(),
                        progressEvents,
                        state
                );
                recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
                return result;
            }
            String requiredStructuredActionTool = structuredActionRequiredTool(state);
            if (requiredStructuredActionTool != null
                    && decisions.stream().noneMatch(decision -> requiredStructuredActionTool.equals(safeToolName(decision)))) {
                messages.add(assistantMessage(turn));
                for (LlmProviderRouter.ToolCallDecision toolCall : decisions) {
                    ProductToolResult rejected = structuredActionRejectedTool(toolCall, requiredStructuredActionTool);
                    toolCalls.add(toolCall(round + 1, toolCall, rejected, Instant.now(), Instant.now()));
                    messages.add(toolMessage(toolCall == null ? "" : toolCall.id(), rejected.contentJson(objectMapper)));
                }
                messages.add(structuredActionToolRequiredMessage(state, requiredStructuredActionTool));
                continue;
            }
            messages.add(assistantMessage(turn));
            List<ProductToolResult> successfulToolResults = new ArrayList<>();
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
                if (SEARCH_TOOL_NAME.equals(toolResult.toolName())) {
                    state.searchPapersActionSatisfied = true;
                }
                if (LIST_LOCATIONS_TOOL_NAME.equals(toolResult.toolName())) {
                    state.listLocationsActionSatisfied = true;
                }
                if (LOCATION_TOOL_NAME.equals(toolResult.toolName())) {
                    state.semanticLocationSearchUsed = true;
                    state.findLocationsActionSatisfied = true;
                }
                if (READ_LOCATIONS_TOOL_NAME.equals(toolResult.toolName())) {
                    state.readLocationsUsed = true;
                }
                toolSucceeded = true;
                successfulToolResults.add(toolResult);
            }
            if (!successfulToolResults.isEmpty()) {
                messages.add(toolResultsPolicyMessage(successfulToolResults));
            }
        }

        ProductTurnResult result = maxReactRoundsReached(progressEvents, state);
        recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
        return result;
    }

    private boolean validToolSurface(List<ToolDefinition> tools) {
        List<String> names = tools == null
                ? List.of()
                : tools.stream().map(ToolDefinition::name).toList();
        return REQUIRED_TOOL_NAMES.equals(names);
    }

    private ToolCallValidation validateToolCall(LlmProviderRouter.ToolCallDecision toolCall, ReadingTurnState state) {
        String toolName = safeToolName(toolCall);
        Map<String, Object> arguments = safeArguments(toolCall);
        if ("find_reading_locations".equals(toolName)) {
            List<String> paperHandles = stringList(arguments.get("paperHandles"));
            if (paperHandles.isEmpty() || !state.semanticPaperHandles.containsAll(paperHandles)) {
                return ToolCallValidation.rejected("hidden_paper_handle");
            }
        }
        if (LIST_LOCATIONS_TOOL_NAME.equals(toolName)) {
            List<String> paperHandles = stringList(arguments.get("paperHandles"));
            if (paperHandles.isEmpty() || !state.deterministicLocationPaperHandles.containsAll(paperHandles)) {
                return ToolCallValidation.rejected("hidden_paper_handle");
            }
        }
        if ("get_paper_outline".equals(toolName)) {
            List<String> paperHandles = stringList(arguments.get("paperHandles"));
            if (paperHandles.isEmpty() || !state.deterministicLocationPaperHandles.containsAll(paperHandles)) {
                return ToolCallValidation.rejected("hidden_paper_handle");
            }
        }
        if (READ_LOCATIONS_TOOL_NAME.equals(toolName)) {
            List<String> locationRefs = stringList(arguments.get("locationRefs"));
            if (locationRefs.isEmpty() || !state.disclosedLocationRefs.containsAll(locationRefs)) {
                return ToolCallValidation.rejected("hidden_location_ref");
            }
        }
        if ("trace_source_quotes".equals(toolName)) {
            List<String> sourceQuoteRefs = stringList(arguments.get("sourceQuoteRefs"));
            if (sourceQuoteRefs.isEmpty() || !state.clickedSourceQuoteRefs.containsAll(sourceQuoteRefs)) {
                return ToolCallValidation.rejected("hidden_source_quote_ref");
            }
        }
        return ToolCallValidation.allowed();
    }

    private void updateState(ProductToolResult toolResult, ReadingTurnState state) {
        if (toolResult == null || toolResult.data() == null) {
            return;
        }
        String toolName = toolResult.toolName();
        if (SESSION_TOOL_NAME.equals(toolName)) {
            state.sessionStatePayload.clear();
            state.sessionStatePayload.putAll(toolResult.data());
            return;
        }
        if (LIST_PAPERS_TOOL_NAME.equals(toolName)) {
            state.paperChoiceToolUsed = true;
            appendPaperChoiceItems(toolName, toolResult.data().get("items"), state, "", null);
            for (Map<String, Object> item : mapList(toolResult.data().get("items"))) {
                String paperHandle = stringValue(item.get("paperHandle"));
                if (!paperHandle.isBlank()) {
                    state.semanticPaperHandles.add(paperHandle);
                    state.deterministicLocationPaperHandles.add(paperHandle);
                }
            }
            return;
        }
        if (SEARCH_TOOL_NAME.equals(toolName)) {
            state.paperChoiceToolUsed = true;
            appendPaperChoiceItems(toolName, toolResult.data().get("items"), state, "", null);
            for (Map<String, Object> item : mapList(toolResult.data().get("items"))) {
                String paperHandle = stringValue(item.get("paperHandle"));
                if (!paperHandle.isBlank()) {
                    state.semanticPaperHandles.add(paperHandle);
                    state.deterministicLocationPaperHandles.add(paperHandle);
                }
            }
            return;
        }
        if (IDENTITY_TOOL_NAME.equals(toolName)) {
            state.paperChoiceToolUsed = true;
            appendIdentityPaperChoices(toolResult, state);
            if (!Boolean.FALSE.equals(toolResult.data().get("ambiguous"))) {
                return;
            }
            for (Map<String, Object> match : mapList(toolResult.data().get("matches"))) {
                String paperHandle = stringValue(match.get("paperHandle"));
                if (!paperHandle.isBlank()) {
                    state.semanticPaperHandles.add(paperHandle);
                    state.deterministicLocationPaperHandles.add(paperHandle);
                }
            }
            return;
        }
        if (LIST_LOCATIONS_TOOL_NAME.equals(toolName)) {
            state.deterministicNavigationToolUsed = true;
            for (Map<String, Object> location : mapList(toolResult.data().get("locations"))) {
                String locationRef = stringValue(location.get("locationRef"));
                if (!locationRef.isBlank()) {
                    state.disclosedLocationRefs.add(locationRef);
                    rememberLocationPayload(state, locationRef, location, toolName);
                }
            }
            return;
        }
        if ("get_paper_outline".equals(toolName)) {
            state.deterministicNavigationToolUsed = true;
            for (Map<String, Object> paper : mapList(toolResult.data().get("papers"))) {
                for (Map<String, Object> section : mapList(paper.get("sections"))) {
                    String sectionRef = stringValue(section.get("sectionRef"));
                    if (!sectionRef.isBlank()) {
                        state.disclosedLocationRefs.add(sectionRef);
                        Map<String, Object> location = new LinkedHashMap<>();
                        location.put("locationRef", sectionRef);
                        location.put("locationType", "SECTION");
                        copyIfPresent(location, paper, "paperHandle");
                        copyIfPresent(location, paper, "title");
                        copyIfPresent(location, paper, "originalFilename");
                        copyIfPresent(location, section, "heading");
                        copyIfPresent(location, section, "sectionRole");
                        copyIfPresent(location, section, "pageStart");
                        copyIfPresent(location, section, "pageEnd");
                        rememberLocationPayload(state, sectionRef, location, toolName);
                    }
                }
            }
            return;
        }
        if ("find_reading_locations".equals(toolName)) {
            for (Map<String, Object> candidate : mapList(toolResult.data().get("candidates"))) {
                String locationRef = stringValue(candidate.get("locationRef"));
                if (!locationRef.isBlank()) {
                    state.disclosedLocationRefs.add(locationRef);
                    rememberLocationPayload(state, locationRef, candidate, toolName);
                }
            }
            return;
        }
        if (READ_LOCATIONS_TOOL_NAME.equals(toolName)) {
            for (Map<String, Object> sourceQuote : mapList(toolResult.data().get("sourceQuotes"))) {
                String sourceQuoteRef = stringValue(sourceQuote.get("sourceQuoteRef"));
                if (!sourceQuoteRef.isBlank()) {
                    state.allowedSourceQuoteRefs.add(sourceQuoteRef);
                    state.sourceQuotePayloads.put(sourceQuoteRef, new LinkedHashMap<>(sourceQuote));
                }
            }
            return;
        }
        if ("trace_source_quotes".equals(toolName)) {
            for (Map<String, Object> sourceQuote : mapList(toolResult.data().get("sourceQuotes"))) {
                String paperHandle = stringValue(sourceQuote.get("paperHandle"));
                if (!paperHandle.isBlank()) {
                    state.deterministicLocationPaperHandles.add(paperHandle);
                }
                String sourceQuoteRef = stringValue(sourceQuote.get("sourceQuoteRef"));
                if (!sourceQuoteRef.isBlank()) {
                    state.allowedSourceQuoteRefs.add(sourceQuoteRef);
                    state.sourceQuotePayloads.put(sourceQuoteRef, new LinkedHashMap<>(sourceQuote));
                }
            }
        }
    }

    private void rememberLocationPayload(ReadingTurnState state,
                                         String locationRef,
                                         Map<String, Object> rawLocation,
                                         String sourceTool) {
        if (state == null || locationRef == null || locationRef.isBlank() || rawLocation == null) {
            return;
        }
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("locationRef", locationRef);
        if (!stringValue(sourceTool).isBlank()) {
            location.put("sourceTool", sourceTool);
            state.locationSourceTools.add(sourceTool);
        }
        copyIfPresent(location, rawLocation, "paperHandle");
        copyIfPresent(location, rawLocation, "title");
        copyIfPresent(location, rawLocation, "originalFilename");
        copyIfPresent(location, rawLocation, "locationType");
        copyIfPresent(location, rawLocation, "pageNumber");
        copyIfPresent(location, rawLocation, "pageEndNumber");
        copyIfPresent(location, rawLocation, "pageStart");
        copyIfPresent(location, rawLocation, "pageEnd");
        copyIfPresent(location, rawLocation, "sectionTitle");
        copyIfPresent(location, rawLocation, "heading");
        copyIfPresent(location, rawLocation, "sectionRole");
        copyIfPresent(location, rawLocation, "label");
        copyIfPresent(location, rawLocation, "preview");
        state.locationPayloads.putIfAbsent(locationRef, location);
    }

    private String structuredActionRequiredTool(ReadingTurnState state) {
        if (state == null) {
            return null;
        }
        if (SEARCH_PAPERS_ACTION.equals(state.readingAction) && !state.searchPapersActionSatisfied) {
            return SEARCH_TOOL_NAME;
        }
        if (LIST_LOCATIONS_ACTION.equals(state.readingAction) && !state.listLocationsActionSatisfied) {
            return LIST_LOCATIONS_TOOL_NAME;
        }
        if (FIND_LOCATIONS_ACTION.equals(state.readingAction) && !state.findLocationsActionSatisfied) {
            return LOCATION_TOOL_NAME;
        }
        return null;
    }

    private SyntheticToolExecution executeStructuredActionToolIfAvailable(ProductTurnRequest request,
                                                                          ReadingTurnState state,
                                                                          List<ToolProgressEvent> progressEvents,
                                                                          List<Map<String, Object>> toolCalls,
                                                                          List<Map<String, Object>> messages,
                                                                          int round) {
        String requiredToolName = structuredActionRequiredTool(state);
        if (requiredToolName == null) {
            return SyntheticToolExecution.notExecuted();
        }
        Map<String, Object> arguments = structuredActionArguments(requiredToolName, request, state);
        if (arguments.isEmpty()) {
            return SyntheticToolExecution.failed(failed(
                    "Product reading explicit action is missing required tool arguments.",
                    progressEvents,
                    ProductStopReason.TOOL_FAILED
            ));
        }
        return executeSyntheticToolCall(
                request,
                state,
                progressEvents,
                toolCalls,
                messages,
                round,
                requiredToolName,
                arguments
        );
    }

    private Map<String, Object> structuredActionArguments(String toolName,
                                                          ProductTurnRequest request,
                                                          ReadingTurnState state) {
        String userMessage = request == null ? "" : stringValue(request.userMessage());
        if (SEARCH_TOOL_NAME.equals(toolName)) {
            return userMessage.isBlank() ? Map.of() : Map.of("queryText", userMessage);
        }
        if (LOCATION_TOOL_NAME.equals(toolName)) {
            if (state == null || state.clickedPaperHandles.isEmpty() || userMessage.isBlank()) {
                return Map.of();
            }
            return Map.of(
                    "paperHandles", List.copyOf(state.clickedPaperHandles),
                    "queryText", userMessage
            );
        }
        if (LIST_LOCATIONS_TOOL_NAME.equals(toolName)) {
            if (state == null || state.clickedPaperHandles.isEmpty()) {
                return Map.of();
            }
            return Map.of("paperHandles", List.copyOf(state.clickedPaperHandles));
        }
        return Map.of();
    }

    private SyntheticToolExecution executeReadAfterSemanticLocationSearchIfAvailable(ProductTurnRequest request,
                                                                                     ReadingTurnState state,
                                                                                     List<ToolProgressEvent> progressEvents,
                                                                                     List<Map<String, Object>> toolCalls,
                                                                                     List<Map<String, Object>> messages,
                                                                                     int round) {
        if (state == null || state.disclosedLocationRefs.isEmpty()) {
            return SyntheticToolExecution.notExecuted();
        }
        List<String> locationRefs = state.disclosedLocationRefs.stream()
                .limit(MAX_LOCATION_FALLBACK_ITEMS)
                .toList();
        return executeSyntheticToolCall(
                request,
                state,
                progressEvents,
                toolCalls,
                messages,
                round,
                READ_LOCATIONS_TOOL_NAME,
                Map.of("locationRefs", locationRefs)
        );
    }

    private SyntheticToolExecution executeSyntheticToolCall(ProductTurnRequest request,
                                                            ReadingTurnState state,
                                                            List<ToolProgressEvent> progressEvents,
                                                            List<Map<String, Object>> toolCalls,
                                                            List<Map<String, Object>> messages,
                                                            int round,
                                                            String toolName,
                                                            Map<String, Object> arguments) {
        LlmProviderRouter.ToolCallDecision toolCall = new LlmProviderRouter.ToolCallDecision(
                syntheticToolCallId(toolName, round),
                toolName,
                arguments
        );
        ToolCallValidation validation = validateToolCall(toolCall, state);
        if (!validation.isAllowed()) {
            ProductToolResult rejected = new ProductToolResult(
                    safeToolName(toolCall),
                    false,
                    Map.of("error", validation.reason()),
                    ProductToolEffect.ERROR
            );
            toolCalls.add(toolCall(round, toolCall, rejected, Instant.now(), Instant.now()));
            return SyntheticToolExecution.failed(failed(
                    "Product reading tool rejected: " + validation.reason(),
                    progressEvents,
                    ProductStopReason.TOOL_FAILED
            ));
        }

        messages.add(syntheticAssistantToolCallMessage(toolCall));
        ToolProgressEvent progressEvent = new ToolProgressEvent("calling_tool", safeToolName(toolCall));
        progressEvents.add(progressEvent);
        request.progressListener().accept(progressEvent);
        Instant toolStartedAt = Instant.now();
        ProductToolResult toolResult = toolRegistry.execute(
                safeToolName(toolCall),
                safeArguments(toolCall),
                new ProductToolContext(
                        request.userId(),
                        request.conversationId(),
                        request.generationId(),
                        request.lockedScope()
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
        toolCalls.add(toolCall(round, toolCall, toolResult, toolStartedAt, Instant.now()));
        messages.add(toolMessage(toolCall.id(), toolResult.contentJson(objectMapper)));
        if (!toolResult.success()) {
            return SyntheticToolExecution.failed(failed(
                    "Product reading tool failed: " + toolResult.toolName(),
                    progressEvents,
                    ProductStopReason.TOOL_FAILED
            ));
        }
        updateState(toolResult, state);
        markToolSatisfied(toolResult, state);
        return SyntheticToolExecution.executed(toolResult);
    }

    private void markToolSatisfied(ProductToolResult toolResult, ReadingTurnState state) {
        if (toolResult == null || state == null) {
            return;
        }
        if (SEARCH_TOOL_NAME.equals(toolResult.toolName())) {
            state.searchPapersActionSatisfied = true;
        }
        if (LIST_LOCATIONS_TOOL_NAME.equals(toolResult.toolName())) {
            state.listLocationsActionSatisfied = true;
        }
        if (LOCATION_TOOL_NAME.equals(toolResult.toolName())) {
            state.semanticLocationSearchUsed = true;
            state.findLocationsActionSatisfied = true;
        }
        if (READ_LOCATIONS_TOOL_NAME.equals(toolResult.toolName())) {
            state.readLocationsUsed = true;
        }
    }

    private ProductToolResult structuredActionRejectedTool(LlmProviderRouter.ToolCallDecision toolCall,
                                                           String requiredToolName) {
        return new ProductToolResult(
                safeToolName(toolCall),
                false,
                Map.of(
                        "status", "REJECTED_BY_PRODUCT_READING_ACTION",
                        "error", "explicit_product_action_requires_" + requiredToolName,
                        "requiredToolName", requiredToolName
                ),
                ProductToolEffect.ERROR
        );
    }

    private List<Map<String, Object>> initialMessages(ProductTurnRequest request,
                                                      Set<String> clickedSourceQuoteRefs,
                                                      Set<String> clickedPaperHandles,
                                                      String readingAction) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", systemPrompt(request, clickedSourceQuoteRefs, clickedPaperHandles, readingAction)));
        messages.add(message("user", request.userMessage()));
        return messages;
    }

    private String systemPrompt(ProductTurnRequest request,
                                Set<String> clickedSourceQuoteRefs,
                                Set<String> clickedPaperHandles,
                                String readingAction) {
        return """
                You are PaperLoom Product Reading ReAct Source Quote MVP.
                Available tools are exactly get_session_state, list_papers, search_paper_candidates, find_papers_by_identity, get_paper_outline, list_paper_locations, find_reading_locations, read_locations, and trace_source_quotes.
                Use get_session_state for fixed search-scope label and readable paper count.
                Use list_papers for deterministic browse/filter inside the fixed scope.
                list_papers is not semantic search; use search_paper_candidates for topic discovery.
                Paper cards from list_papers are navigation only, not Source Quotes.
                Paper handles returned by list_papers may be used with get_paper_outline, list_paper_locations, or find_reading_locations.
                Use search_paper_candidates for paper candidate discovery.
                Use find_papers_by_identity for a specific paper by title, filename, DOI, arXiv id, author, or year.
                find_papers_by_identity is not semantic search; use search_paper_candidates for topic discovery.
                Identity paper cards are navigation only, not Source Quotes.
                Unambiguous paper handles returned by find_papers_by_identity may be used with get_paper_outline, list_paper_locations, or find_reading_locations.
                If find_papers_by_identity returns AMBIGUOUS, ask the user to clarify or choose a paper before reading content.
                Use get_paper_outline after choosing papers when structure, section choices, or parser quality is needed.
                get_paper_outline requires paperHandles disclosed by list_papers, search_paper_candidates, find_papers_by_identity, trace_source_quotes, or explicit clicked paper anchors in this turn.
                get_paper_outline returns sectionRef values for navigation only; they are not Source Quotes.
                Use find_reading_locations for semantic in-paper location search; it requires queryText and paperHandles disclosed by list_papers, search_paper_candidates, unambiguous find_papers_by_identity, or explicit clicked paper anchors in this turn.
                Use list_paper_locations for deterministic section/page/table/figure refs; it requires paperHandles disclosed by list_papers, search_paper_candidates, unambiguous find_papers_by_identity, trace_source_quotes, or explicit clicked paper anchors in this turn.
                Use read_locations only after explicit locationRef or sectionRef values were returned by get_paper_outline, find_reading_locations, or list_paper_locations in this turn.
                Use trace_source_quotes only for sourceQuoteRefs listed in this turn's explicit clicked Source Quote anchors.
                trace_source_quotes returned locationRef values are metadata, not read_locations input.
                To read broader context around a traced Source Quote, call list_paper_locations with the traced paperHandle and pageNumber or location type, then call read_locations with refs returned by list_paper_locations.
                Paper previews, paper outlines, parserQuality, and reading-location previews are navigation only, not Source Quotes.
                read_locations and trace_source_quotes are the only Source Quote tools in this slice.
                clicked Source Quote anchors are trace-tool inputs only; they are not citeable until trace_source_quotes returns them in this turn.
                Do not invent paperHandle, locationRef, or sourceQuoteRef values.
                Do not pass ordinals as tool input.
                Do not pass limit, topK, modelVersion, indexName, chunkRef, paperId, question, query, readingNeed, semanticNeed, sourceQuoteRef, splitPolicyVersion, or contentHash.
                trace_source_quotes accepts only sourceQuoteRefs from explicit clicked Source Quote anchors; never use display citations like [1] as tool input.
                Candidate-list and navigation answers use PRODUCT_STATE.
                Paper-content claims require EVIDENCE_ANSWER with sourceQuoteRefs returned by read_locations or trace_source_quotes in this turn.
                In EVIDENCE_ANSWER text, cite Source Quotes with markers exactly like {{sourceQuoteRef:source_quote_...}}.
                In evidenceBasedClaims, use sourceQuoteRefs, not evidenceRefs.
                If Source Quotes are unavailable or insufficient, answer INSUFFICIENT_EVIDENCE without unsupported paper facts.
                Final answer must be one JSON AnswerEnvelope.
                Re-output only one JSON object. Do not use markdown fences or plain text.
                Use exactly these top-level fields:
                answerType, answer, evidenceBasedClaims, stateClaims, limitations, nonEvidenceNotes, missingFields, reason.
                The answer field is the visible user answer; put any tables, bullets, recommendations, or other user-facing Markdown structure inside answer.
                Required final JSON shape:
                {
                  "answerType": "NON_EVIDENCE | PRODUCT_STATE | EVIDENCE_ANSWER | INSUFFICIENT_EVIDENCE | CLARIFICATION_NEEDED",
                  "answer": "direct user-facing answer",
                  "evidenceBasedClaims": [{"claim": "paper claim", "sourceQuoteRefs": ["source_quote_..."]}],
                  "stateClaims": [{"claim": "product-state claim", "sourceTool": "list_papers"}],
                  "limitations": ["limitation text"],
                  "nonEvidenceNotes": ["non-evidence note text"],
                  "missingFields": ["missing field name"],
                  "reason": ""
                }
                evidenceBasedClaims and stateClaims must be arrays of JSON objects, never strings.
                limitations, nonEvidenceNotes, and missingFields must be arrays of strings.
                paperRef, evidenceRef, and citationRef are legacy identifiers for the old harness. Do not use them as reading tool arguments or citation support.
                Explicit clicked paper anchors for this turn:
                %s
                Clicked paper anchors are navigation only, not Source Quotes.
                Use clicked paper handles only with get_paper_outline, list_paper_locations, or find_reading_locations.
                Explicit Product Reading UI action for this turn:
                %s
                If the explicit Product Reading UI action is SEARCH_PAPERS, call search_paper_candidates before any other tool. Use the current user request as queryText.
                If the explicit Product Reading UI action is LIST_LOCATIONS, call list_paper_locations before any other tool. Use the explicit clicked paperHandle anchors for paperHandles.
                If the explicit Product Reading UI action is FIND_LOCATIONS, call find_reading_locations before any other navigation or reading tool. Use the current user request as the source of queryText, but write queryText in the paper's language when that is needed for retrieval.
                Explicit clicked Source Quote anchors for this turn:
                %s
                Current user request:
                %s
                """.formatted(
                clickedPaperAnchorPrompt(clickedPaperHandles),
                readingActionPrompt(readingAction),
                clickedSourceQuoteAnchorPrompt(clickedSourceQuoteRefs),
                request.userMessage()
        );
    }

    private ProductTurnResult finalResult(String rawContent,
                                          List<ToolProgressEvent> progressEvents,
                                          ReadingTurnState state) {
        AnswerEnvelope envelope;
        try {
            envelope = parseEnvelope(rawContent);
        } catch (Exception exception) {
            return failed(ANSWER_SCHEMA_INVALID_MESSAGE, progressEvents, ProductStopReason.ANSWER_SCHEMA_INVALID);
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
                    state.productStateItems,
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
                state.productStateItems,
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
    }

    private ProductTurnResult finalResultOrProductStateFallback(String rawContent,
                                                                List<ToolProgressEvent> progressEvents,
                                                                ReadingTurnState state) {
        ProductTurnResult result = finalResult(rawContent, progressEvents, state);
        if (result.resultStatus() == ProductResultStatus.FAILED
                && result.stopReason() == ProductStopReason.ANSWER_SCHEMA_INVALID
                && ANSWER_SCHEMA_INVALID_MESSAGE.equals(result.finalAnswerMarkdown())) {
            if (canFallbackToSourceQuoteEvidence(state)) {
                return sourceQuoteEvidenceFallback(progressEvents, state, ProductStopReason.ANSWER_SCHEMA_INVALID);
            }
            if (canFallbackToLocationState(state)) {
                return locationNavigationFallback(progressEvents, state, ProductStopReason.ANSWER_SCHEMA_INVALID);
            }
            if (canFallbackToPaperChoiceState(state)) {
                return productStateNavigationFallback(progressEvents, state, ProductStopReason.ANSWER_SCHEMA_INVALID);
            }
            if (canFallbackToSessionState(state)) {
                return sessionStateFallback(progressEvents, state, ProductStopReason.ANSWER_SCHEMA_INVALID);
            }
        }
        if (result.resultStatus() == ProductResultStatus.FAILED
                && result.stopReason() == ProductStopReason.CITATION_VALIDATION_FAILED
                && "Source-quoted answer requires visible sourceQuoteRef markers.".equals(result.finalAnswerMarkdown())
                && canFallbackToSourceQuoteEvidence(state)) {
            return sourceQuoteEvidenceFallback(progressEvents, state, ProductStopReason.CITATION_VALIDATION_FAILED);
        }
        return result;
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private void appendIdentityPaperChoices(ProductToolResult toolResult, ReadingTurnState state) {
        if (toolResult == null || state == null || state.productStateItems.size() >= MAX_PRODUCT_STATE_ITEMS) {
            return;
        }
        String identityStatus = stringValue(toolResult.data().get("status"));
        Boolean ambiguous = Boolean.TRUE.equals(toolResult.data().get("ambiguous"));
        appendPaperChoiceItems(IDENTITY_TOOL_NAME, toolResult.data().get("matches"), state, identityStatus, ambiguous);
    }

    private void appendPaperChoiceItems(String sourceTool,
                                        Object rawRows,
                                        ReadingTurnState state,
                                        String identityStatus,
                                        Boolean ambiguous) {
        if (state == null
                || state.productStateItems.size() >= MAX_PRODUCT_STATE_ITEMS
                || !PAPER_CHOICE_SOURCE_TOOLS.contains(sourceTool)) {
            return;
        }
        boolean identitySource = IDENTITY_TOOL_NAME.equals(sourceTool);
        for (Map<String, Object> row : mapList(rawRows)) {
            if (state.productStateItems.size() >= MAX_PRODUCT_STATE_ITEMS) {
                return;
            }
            String paperHandle = stringValue(row.get("paperHandle"));
            if (!PAPER_HANDLE_PATTERN.matcher(paperHandle).matches()
                    || !state.productStatePaperHandles.add(paperHandle)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", READING_PAPER_CHOICE_KIND);
            item.put("sourceTool", sourceTool);
            item.put("paperHandle", paperHandle);
            copyStringIfPresent(item, row, "title");
            copyStringIfPresent(item, row, "originalFilename");
            copyStringListIfPresent(item, row, "authors");
            copyNumberIfPresent(item, row, "year");
            copyStringIfPresent(item, row, "venue");
            if (identitySource) {
                copyStringListIfPresent(item, row, "matchReasons");
                if (!stringValue(identityStatus).isBlank()) {
                    item.put("identityStatus", stringValue(identityStatus));
                }
                if (ambiguous != null) {
                    item.put("ambiguous", ambiguous);
                }
            }
            state.productStateItems.add(item);
        }
    }

    private void copyStringIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        String value = stringValue(source.get(key));
        if (!value.isBlank()) {
            target.put(key, value);
        }
    }

    private void copyStringListIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        List<String> values = stringList(source.get(key));
        if (!values.isEmpty()) {
            target.put(key, values);
        }
    }

    private void copyNumberIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            target.put(key, number);
        }
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
        JsonNode root = objectMapper.readTree(extractJson(rawContent));
        JsonNode node = answerEnvelopeNode(root);
        return new AnswerEnvelope(
                answerType(node),
                firstText(node, "answer", "summary", "message"),
                listOfMaps(node.path("evidenceBasedClaims")),
                listOfMaps(node.path("stateClaims")),
                listOfStrings(node.path("limitations")),
                listOfStrings(node.path("nonEvidenceNotes")),
                listOfStrings(node.path("missingFields")),
                node.path("reason").asText("")
        );
    }

    private JsonNode answerEnvelopeNode(JsonNode root) {
        if (root != null && root.path("answerEnvelope").isObject()) {
            return root.path("answerEnvelope");
        }
        return root;
    }

    private AnswerType answerType(JsonNode node) {
        String rawType = firstText(node, "answerType", "answerKind", "answerMode");
        if (rawType.isBlank() && node != null && node.path("productState").isObject()) {
            rawType = AnswerType.PRODUCT_STATE.name();
        }
        return AnswerType.valueOf(rawType.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT));
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return "";
        }
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
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

    private ProductTurnResult maxReactRoundsReached(List<ToolProgressEvent> progressEvents, ReadingTurnState state) {
        if (canFallbackToSourceQuoteEvidence(state)) {
            return sourceQuoteEvidenceFallback(progressEvents, state, ProductStopReason.MAX_REACT_ROUNDS);
        }
        if (canFallbackToLocationState(state)) {
            return locationNavigationFallback(progressEvents, state, ProductStopReason.MAX_REACT_ROUNDS);
        }
        if (canFallbackToPaperChoiceState(state)) {
            return productStateNavigationFallback(progressEvents, state, ProductStopReason.MAX_REACT_ROUNDS);
        }
        if (canFallbackToSessionState(state)) {
            return sessionStateFallback(progressEvents, state, ProductStopReason.MAX_REACT_ROUNDS);
        }
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

    private ProductTurnResult productStateNavigationFallback(List<ToolProgressEvent> progressEvents,
                                                             ReadingTurnState state,
                                                             ProductStopReason originalStopReason) {
        String answer = productStateAnswer(state.productStateItems);
        AnswerEnvelope envelope = new AnswerEnvelope(
                AnswerType.PRODUCT_STATE,
                answer,
                List.of(),
                productStateClaims(state.productStateItems),
                List.of(),
                List.of("Recovered a safe navigation answer after " + originalStopReason.name()),
                List.of(),
                "paper_choice_navigation_fallback"
        );
        return new ProductTurnResult(
                answer,
                envelope,
                List.of(),
                progressEvents,
                state.productStateItems,
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
    }

    private ProductTurnResult sessionStateFallback(List<ToolProgressEvent> progressEvents,
                                                   ReadingTurnState state,
                                                   ProductStopReason originalStopReason) {
        String answer = sessionStateAnswer(state);
        AnswerEnvelope envelope = new AnswerEnvelope(
                AnswerType.PRODUCT_STATE,
                answer,
                List.of(),
                List.of(Map.of(
                        "claim", "Reading session scope and count were returned by " + SESSION_TOOL_NAME + ".",
                        "sourceTool", SESSION_TOOL_NAME
                )),
                List.of(),
                List.of("Recovered a safe session-state answer after " + originalStopReason.name()),
                List.of(),
                "session_state_fallback"
        );
        return new ProductTurnResult(
                answer,
                envelope,
                List.of(),
                progressEvents,
                state.productStateItems,
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
    }

    private boolean canFallbackToPaperChoiceState(ReadingTurnState state) {
        return state != null
                && !state.productStateItems.isEmpty()
                && state.disclosedLocationRefs.isEmpty()
                && state.sourceQuotePayloads.isEmpty();
    }

    private boolean canFallbackToSessionState(ReadingTurnState state) {
        return state != null && !state.sessionStatePayload.isEmpty();
    }

    private boolean canFallbackToLocationState(ReadingTurnState state) {
        return state != null
                && !state.locationPayloads.isEmpty()
                && state.sourceQuotePayloads.isEmpty();
    }

    private boolean canFallbackToSourceQuoteEvidence(ReadingTurnState state) {
        return state != null && !fallbackSourceQuotes(state).isEmpty();
    }

    private boolean needsReadAfterSemanticLocationSearchBeforeFinal(ReadingTurnState state) {
        return state != null
                && state.semanticLocationSearchUsed
                && !FIND_LOCATIONS_ACTION.equals(state.readingAction)
                && !state.paperChoiceToolUsed
                && !state.deterministicNavigationToolUsed
                && !state.readLocationsUsed
                && !state.disclosedLocationRefs.isEmpty()
                && state.sourceQuotePayloads.isEmpty();
    }

    private ProductTurnResult locationNavigationFallback(List<ToolProgressEvent> progressEvents,
                                                         ReadingTurnState state,
                                                         ProductStopReason originalStopReason) {
        String answer = locationStateAnswer(state);
        AnswerEnvelope envelope = new AnswerEnvelope(
                AnswerType.PRODUCT_STATE,
                answer,
                List.of(),
                locationStateClaims(state),
                List.of("Location results are navigation only, not Source Quotes; call read_locations for paper-content claims."),
                List.of("Recovered a safe navigation answer after " + originalStopReason.name()),
                List.of(),
                "location_navigation_fallback"
        );
        return new ProductTurnResult(
                answer,
                envelope,
                List.of(),
                progressEvents,
                state.productStateItems,
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
    }

    private ProductTurnResult sourceQuoteEvidenceFallback(List<ToolProgressEvent> progressEvents,
                                                          ReadingTurnState state,
                                                          ProductStopReason originalStopReason) {
        List<Map<String, Object>> quotes = fallbackSourceQuotes(state);
        String answer = sourceQuoteFallbackAnswer(quotes);
        List<Map<String, Object>> claims = sourceQuoteFallbackClaims(quotes);
        AnswerEnvelope envelope = new AnswerEnvelope(
                AnswerType.EVIDENCE_ANSWER,
                answer,
                claims,
                List.of(),
                List.of("Recovered a conservative Source Quote answer after " + originalStopReason.name()),
                List.of(),
                List.of(),
                "source_quote_evidence_fallback"
        );
        CitationRender render = renderSourceQuoteCitations(answer, state);
        return new ProductTurnResult(
                render.markdown(),
                envelope,
                render.references(),
                progressEvents,
                state.productStateItems,
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
    }

    private String productStateAnswer(List<Map<String, Object>> items) {
        List<Map<String, Object>> safeItems = items == null ? List.of() : items;
        StringBuilder builder = new StringBuilder("Found readable paper choices:");
        int index = 1;
        for (Map<String, Object> item : safeItems) {
            String title = stringValue(item.get("title"));
            String filename = stringValue(item.get("originalFilename"));
            if (title.isBlank() && filename.isBlank()) {
                continue;
            }
            builder.append("\n").append(index++).append(". ");
            builder.append(title.isBlank() ? filename : title);
            if (!filename.isBlank() && !filename.equals(title)) {
                builder.append(" (").append(filename).append(")");
            }
        }
        return builder.toString();
    }

    private String sessionStateAnswer(ReadingTurnState state) {
        Map<String, Object> searchScope = objectMap(state.sessionStatePayload.get("searchScope"));
        String label = stringValue(searchScope.get("label"));
        String mode = stringValue(searchScope.get("scopeMode"));
        boolean countKnown = Boolean.TRUE.equals(searchScope.get("readablePaperCountKnown"));
        String count = stringValue(searchScope.get("readablePaperCount"));
        StringBuilder builder = new StringBuilder("Current reading scope");
        if (!label.isBlank()) {
            builder.append(": ").append(label);
        }
        if (!mode.isBlank()) {
            builder.append(" (").append(mode).append(")");
        }
        builder.append(".");
        if (countKnown && !count.isBlank()) {
            builder.append(" Readable paper count: ").append(count).append(".");
        } else {
            builder.append(" Readable paper count is not available from this tool result.");
        }
        return builder.toString();
    }

    private List<Map<String, Object>> productStateClaims(List<Map<String, Object>> items) {
        LinkedHashSet<String> sourceTools = new LinkedHashSet<>();
        for (Map<String, Object> item : items == null ? List.<Map<String, Object>>of() : items) {
            String sourceTool = stringValue(item.get("sourceTool"));
            if (!sourceTool.isBlank()) {
                sourceTools.add(sourceTool);
            }
        }
        List<Map<String, Object>> claims = new ArrayList<>();
        for (String sourceTool : sourceTools) {
            claims.add(Map.of(
                    "claim", "Paper choices were returned by " + sourceTool + ".",
                    "sourceTool", sourceTool
            ));
        }
        return List.copyOf(claims);
    }

    private String locationStateAnswer(ReadingTurnState state) {
        StringBuilder builder = new StringBuilder("Found readable locations:");
        int index = 1;
        for (Map<String, Object> location : state.locationPayloads.values()) {
            if (index > MAX_LOCATION_FALLBACK_ITEMS) {
                break;
            }
            String locationRef = stringValue(location.get("locationRef"));
            if (locationRef.isBlank()) {
                continue;
            }
            builder.append("\n").append(index++).append(". ");
            builder.append(locationLabel(location));
            builder.append(" (").append(locationRef).append(")");
            String preview = snippet(stringValue(location.get("preview")), 180);
            if (!preview.isBlank()) {
                builder.append(" - ").append(preview);
            }
        }
        return builder.toString();
    }

    private String locationLabel(Map<String, Object> location) {
        String label = firstNonBlank(
                stringValue(location.get("label")),
                stringValue(location.get("sectionTitle")),
                stringValue(location.get("heading"))
        );
        String pageLabel = pageLabel(location);
        if (label.isBlank()) {
            return pageLabel.isBlank() ? "Location" : pageLabel;
        }
        if (pageLabel.isBlank()) {
            return label;
        }
        return label + ", " + pageLabel;
    }

    private String pageLabel(Map<String, Object> location) {
        String page = firstNonBlank(stringValue(location.get("pageNumber")), stringValue(location.get("pageStart")));
        String pageEnd = firstNonBlank(stringValue(location.get("pageEndNumber")), stringValue(location.get("pageEnd")));
        if (page.isBlank()) {
            return "";
        }
        if (!pageEnd.isBlank() && !pageEnd.equals(page)) {
            return "pages " + page + "-" + pageEnd;
        }
        return "page " + page;
    }

    private List<Map<String, Object>> locationStateClaims(ReadingTurnState state) {
        List<Map<String, Object>> claims = new ArrayList<>();
        for (String sourceTool : state.locationSourceTools) {
            if (!sourceTool.isBlank()) {
                claims.add(Map.of(
                        "claim", "Reading locations were returned by " + sourceTool + ".",
                        "sourceTool", sourceTool
                ));
            }
        }
        return List.copyOf(claims);
    }

    private List<Map<String, Object>> fallbackSourceQuotes(ReadingTurnState state) {
        if (state == null || state.sourceQuotePayloads.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> withContent = new ArrayList<>();
        List<Map<String, Object>> anyQuotes = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : state.sourceQuotePayloads.entrySet()) {
            Map<String, Object> quote = new LinkedHashMap<>(entry.getValue());
            quote.putIfAbsent("sourceQuoteRef", entry.getKey());
            if (SOURCE_QUOTE_REF_PATTERN.matcher(stringValue(quote.get("sourceQuoteRef"))).matches()) {
                anyQuotes.add(quote);
                if (!stringValue(quote.get("content")).isBlank()) {
                    withContent.add(quote);
                }
            }
        }
        List<Map<String, Object>> preferred = withContent.isEmpty() ? anyQuotes : withContent;
        return preferred.stream()
                .limit(MAX_SOURCE_QUOTE_FALLBACK_ITEMS)
                .<Map<String, Object>>map(item -> new LinkedHashMap<String, Object>(item))
                .toList();
    }

    private String sourceQuoteFallbackAnswer(List<Map<String, Object>> quotes) {
        StringBuilder builder = new StringBuilder("Found source-quoted evidence:");
        int index = 1;
        for (Map<String, Object> quote : quotes) {
            String sourceQuoteRef = stringValue(quote.get("sourceQuoteRef"));
            if (!SOURCE_QUOTE_REF_PATTERN.matcher(sourceQuoteRef).matches()) {
                continue;
            }
            builder.append("\n").append(index++).append(". ");
            String label = sourceQuoteLabel(quote);
            if (!label.isBlank()) {
                builder.append(label).append(": ");
            }
            String content = snippet(stringValue(quote.get("content")), 260);
            builder.append(content.isBlank() ? "Source Quote returned for this location." : content);
            builder.append(" {{sourceQuoteRef:").append(sourceQuoteRef).append("}}");
        }
        return builder.toString();
    }

    private List<Map<String, Object>> sourceQuoteFallbackClaims(List<Map<String, Object>> quotes) {
        List<Map<String, Object>> claims = new ArrayList<>();
        for (Map<String, Object> quote : quotes) {
            String sourceQuoteRef = stringValue(quote.get("sourceQuoteRef"));
            if (!SOURCE_QUOTE_REF_PATTERN.matcher(sourceQuoteRef).matches()) {
                continue;
            }
            String content = snippet(stringValue(quote.get("content")), 220);
            String claim = content.isBlank()
                    ? "A Source Quote was returned for the requested reading location."
                    : "Source Quote reports: " + content;
            claims.add(Map.of(
                    "claim", claim,
                    "sourceQuoteRefs", List.of(sourceQuoteRef)
            ));
        }
        return List.copyOf(claims);
    }

    private String sourceQuoteLabel(Map<String, Object> quote) {
        String paperTitle = stringValue(quote.get("paperTitle"));
        String sectionTitle = stringValue(quote.get("sectionTitle"));
        String page = pageLabel(quote);
        List<String> parts = new ArrayList<>();
        if (!paperTitle.isBlank()) {
            parts.add(paperTitle);
        }
        if (!sectionTitle.isBlank()) {
            parts.add(sectionTitle);
        }
        if (!page.isBlank()) {
            parts.add(page);
        }
        return String.join(", ", parts);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = stringValue(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String snippet(String value, int maxChars) {
        String normalized = stringValue(value).replaceAll("\\s+", " ");
        if (maxChars <= 0 || normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 3)).trim() + "...";
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

    private List<Map<String, Object>> messageSnapshot(List<Map<String, Object>> messages) {
        if (messages == null) {
            return List.of();
        }
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            snapshot.add(message == null ? Map.of() : new LinkedHashMap<>(message));
        }
        return List.copyOf(snapshot);
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

    private Map<String, Object> syntheticAssistantToolCallMessage(LlmProviderRouter.ToolCallDecision toolCall) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", safeToolName(toolCall));
        function.put("arguments", toolArgumentsJson(safeArguments(toolCall)));

        Map<String, Object> serializedCall = new LinkedHashMap<>();
        serializedCall.put("id", toolCall == null ? "" : toolCall.id());
        serializedCall.put("type", "function");
        serializedCall.put("function", function);

        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", List.of(serializedCall));
        return assistant;
    }

    private String toolArgumentsJson(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private String syntheticToolCallId(String toolName, int round) {
        String normalizedToolName = stringValue(toolName).replaceAll("[^A-Za-z0-9_-]", "_");
        if (normalizedToolName.isBlank()) {
            normalizedToolName = "reading_tool";
        }
        return "synthetic_" + round + "_" + normalizedToolName;
    }

    private Map<String, Object> firstReadingToolCallRequiredMessage(ReadingTurnState state) {
        String requiredToolName = structuredActionRequiredTool(state);
        if (requiredToolName != null) {
            return structuredActionToolRequiredMessage(state, requiredToolName);
        }
        return message("user", """
                The previous response did not call a PaperLoom reading tool and is rejected.
                Your next response must contain tool_calls only. Do not output plain text, markdown, or an AnswerEnvelope yet.
                Select and call one available reading tool for the current user request only, not for older history or memory.
                Use get_session_state for fixed search-scope label, readable paper count, or library/session state questions.
                Use list_papers for deterministic browse/filter and search_paper_candidates for semantic topic discovery.
                Use find_papers_by_identity only when the user names a specific paper by title, filename, DOI, arXiv id, author, or year.
                """);
    }

    private Map<String, Object> structuredActionToolRequiredMessage(ReadingTurnState state, String requiredToolName) {
        String action = state == null || state.readingAction.isBlank() ? "UNKNOWN" : state.readingAction;
        String anchorInstruction;
        if (LIST_LOCATIONS_TOOL_NAME.equals(requiredToolName)) {
            anchorInstruction = "Use the explicit clicked paperHandle anchors as paperHandles.";
        } else if (LOCATION_TOOL_NAME.equals(requiredToolName)) {
            anchorInstruction = "Use the explicit clicked paperHandle anchors and a caller-authored queryText for this turn's request.";
        } else {
            anchorInstruction = "Use a caller-authored queryText for this turn's request.";
        }
        return message("user", """
                The explicit Product Reading UI action for this turn is %s.
                The next response must contain tool_calls only and must call %s before any other tool.
                %s
                """.formatted(action, requiredToolName, anchorInstruction));
    }

    private Map<String, Object> readLocationsRequiredAfterSemanticLocationsMessage() {
        return message("user", """
                The previous response stopped at semantic reading-location navigation.
                This turn is not an explicit FIND_LOCATIONS UI action, so navigation refs are not a final answer.
                Your next response must contain tool_calls only and must call read_locations with locationRefs disclosed by the successful find_reading_locations result.
                """);
    }

    private Map<String, Object> toolResultsPolicyMessage(List<ProductToolResult> toolResults) {
        if (hasSourceQuoteToolResult(toolResults)) {
            return message("user", """
                    Source Quotes from read_locations or trace_source_quotes may support EVIDENCE_ANSWER.
                    Cite them with {{sourceQuoteRef:source_quote_...}} markers and use sourceQuoteRefs in evidenceBasedClaims.
                    Do not use evidenceRefs, citationRef, locationRef, or paperHandle as citation support.
                    """);
        }
        return message(
                "user",
                "Treat this result as navigation only. If paper-content claims are needed, call read_locations on disclosed locationRefs."
        );
    }

    private boolean hasSourceQuoteToolResult(List<ProductToolResult> toolResults) {
        if (toolResults == null) {
            return false;
        }
        for (ProductToolResult toolResult : toolResults) {
            if (toolResult != null
                    && (READ_LOCATIONS_TOOL_NAME.equals(toolResult.toolName())
                    || "trace_source_quotes".equals(toolResult.toolName()))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> safeArguments(LlmProviderRouter.ToolCallDecision toolCall) {
        return toolCall == null || toolCall.arguments() == null ? Map.of() : toolCall.arguments();
    }

    private Set<String> clickedSourceQuoteRefs(Map<String, Object> memory) {
        if (memory == null || !(memory.get("readingTurnAnchors") instanceof Map<?, ?> anchors)) {
            return Set.of();
        }
        Object rawRefs = anchors.get("clickedSourceQuoteRefs");
        if (!(rawRefs instanceof List<?> list)) {
            return Set.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (Object rawRef : list) {
            String sourceQuoteRef = stringValue(rawRef);
            if (SOURCE_QUOTE_REF_PATTERN.matcher(sourceQuoteRef).matches()) {
                refs.add(sourceQuoteRef);
            }
            if (refs.size() >= MAX_CLICKED_SOURCE_QUOTE_REFS) {
                break;
            }
        }
        return refs;
    }

    private Set<String> clickedPaperHandles(Map<String, Object> memory) {
        if (memory == null || !(memory.get("readingTurnAnchors") instanceof Map<?, ?> anchors)) {
            return Set.of();
        }
        Object rawHandles = anchors.get("clickedPaperHandles");
        if (!(rawHandles instanceof List<?> list)) {
            return Set.of();
        }
        LinkedHashSet<String> handles = new LinkedHashSet<>();
        for (Object rawHandle : list) {
            String paperHandle = stringValue(rawHandle);
            if (PAPER_HANDLE_PATTERN.matcher(paperHandle).matches()) {
                handles.add(paperHandle);
            }
            if (handles.size() >= MAX_CLICKED_PAPER_HANDLES) {
                break;
            }
        }
        return handles;
    }

    private String readingTurnAction(Map<String, Object> memory) {
        if (memory == null) {
            return "";
        }
        String action = stringValue(memory.get("readingTurnAction")).toUpperCase(Locale.ROOT);
        if (SEARCH_PAPERS_ACTION.equals(action)
                || LIST_LOCATIONS_ACTION.equals(action)
                || FIND_LOCATIONS_ACTION.equals(action)) {
            return action;
        }
        return "";
    }

    private String clickedSourceQuoteAnchorPrompt(Set<String> clickedSourceQuoteRefs) {
        if (clickedSourceQuoteRefs == null || clickedSourceQuoteRefs.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(clickedSourceQuoteRefs);
        } catch (Exception exception) {
            return clickedSourceQuoteRefs.toString();
        }
    }

    private String clickedPaperAnchorPrompt(Set<String> clickedPaperHandles) {
        if (clickedPaperHandles == null || clickedPaperHandles.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(clickedPaperHandles);
        } catch (Exception exception) {
            return clickedPaperHandles.toString();
        }
    }

    private String readingActionPrompt(String readingAction) {
        String action = stringValue(readingAction);
        return action.isBlank() ? "NONE" : action;
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
        private final Set<String> clickedSourceQuoteRefs;
        private final Set<String> clickedPaperHandles;
        private final String readingAction;
        private final Set<String> semanticPaperHandles = new LinkedHashSet<>();
        private final Set<String> deterministicLocationPaperHandles = new LinkedHashSet<>();
        private final Set<String> disclosedLocationRefs = new LinkedHashSet<>();
        private final Set<String> allowedSourceQuoteRefs = new LinkedHashSet<>();
        private final Map<String, Map<String, Object>> sourceQuotePayloads = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> locationPayloads = new LinkedHashMap<>();
        private final Map<String, Object> sessionStatePayload = new LinkedHashMap<>();
        private final Set<String> locationSourceTools = new LinkedHashSet<>();
        private final List<Map<String, Object>> productStateItems = new ArrayList<>();
        private final Set<String> productStatePaperHandles = new LinkedHashSet<>();
        private boolean searchPapersActionSatisfied;
        private boolean listLocationsActionSatisfied;
        private boolean findLocationsActionSatisfied;
        private boolean semanticLocationSearchUsed;
        private boolean paperChoiceToolUsed;
        private boolean deterministicNavigationToolUsed;
        private boolean readLocationsUsed;

        private ReadingTurnState(Set<String> clickedSourceQuoteRefs,
                                 Set<String> clickedPaperHandles,
                                 String readingAction) {
            this.clickedSourceQuoteRefs = clickedSourceQuoteRefs == null
                    ? Set.of()
                    : new LinkedHashSet<>(clickedSourceQuoteRefs);
            this.clickedPaperHandles = clickedPaperHandles == null
                    ? Set.of()
                    : new LinkedHashSet<>(clickedPaperHandles);
            this.readingAction = readingAction == null ? "" : readingAction.trim().toUpperCase(Locale.ROOT);
            this.semanticPaperHandles.addAll(this.clickedPaperHandles);
            this.deterministicLocationPaperHandles.addAll(this.clickedPaperHandles);
        }
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

    private record SyntheticToolExecution(boolean executed,
                                          ProductToolResult toolResult,
                                          ProductTurnResult terminalResult) {
        static SyntheticToolExecution notExecuted() {
            return new SyntheticToolExecution(false, null, null);
        }

        static SyntheticToolExecution executed(ProductToolResult toolResult) {
            return new SyntheticToolExecution(true, toolResult, null);
        }

        static SyntheticToolExecution failed(ProductTurnResult terminalResult) {
            return new SyntheticToolExecution(false, null, terminalResult);
        }
    }
}
