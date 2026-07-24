package io.github.chzarles.paperloom.service;

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
    private static final int MAX_PRODUCT_STATE_ITEMS = 10;
    private static final String READING_PAPER_CHOICE_KIND = "READING_PAPER_CHOICE";
    private static final String LIST_PAPERS_TOOL_NAME = "list_papers";
    private static final String SEARCH_TOOL_NAME = "search_paper_candidates";
    private static final String IDENTITY_TOOL_NAME = "find_papers_by_identity";
    private static final String OUTLINE_TOOL_NAME = "get_paper_outline";
    private static final String LIST_LOCATIONS_TOOL_NAME = "list_paper_locations";
    private static final String LOCATION_TOOL_NAME = "find_reading_locations";
    private static final String READ_LOCATIONS_TOOL_NAME = "read_locations";
    private static final String TRACE_SOURCE_QUOTES_TOOL_NAME = "trace_source_quotes";
    private static final String SEARCH_PAPERS_ACTION = "SEARCH_PAPERS";
    private static final String LIST_LOCATIONS_ACTION = "LIST_LOCATIONS";
    private static final String FIND_LOCATIONS_ACTION = "FIND_LOCATIONS";
    private static final String READ_LOCATION_ACTION = "READ_LOCATION";
    private static final String TRACE_SOURCE_QUOTE_ACTION = "TRACE_SOURCE_QUOTE";
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
    private static final Pattern PAPER_HANDLE_PATTERN =
            Pattern.compile("^paper_handle_[A-Za-z0-9_-]+$");
    private static final String ANSWER_SCHEMA_INVALID_MESSAGE = "Answer envelope schema invalid.";
    private static final String VISIBLE_INTERNAL_LEAK_MESSAGE =
            "Answer envelope contains user-visible internal reading identifiers.";
    private static final String SESSION_TOOL_NAME = "get_session_state";

    private final LlmProviderRouter llmProviderRouter;
    private final ProductReadingToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ProductReadingTraceRecorder traceRecorder;
    private final ReadingAnswerPresenter answerPresenter = new ReadingAnswerPresenter();
    private final ReadingTurnArtifactProjector artifactProjector = new ReadingTurnArtifactProjector();
    private final ReadingResearchTraceProjector researchTraceProjector = new ReadingResearchTraceProjector();
    private final ReadingResearchTraceSummaryProjector researchTraceSummaryProjector =
            new ReadingResearchTraceSummaryProjector();
    private final ReadingArtifactContractValidator artifactContractValidator = new ReadingArtifactContractValidator();
    private final ReadingResearchTraceContractValidator researchTraceContractValidator =
            new ReadingResearchTraceContractValidator();
    private final ReadingToolCallGate toolCallGate = new ReadingToolCallGate();

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
            ReadingTurnState state = new ReadingTurnState(ReadingTurnFrame.from(safeRequest));
            ProductTurnResult result = preciseIncompleteResult(
                    safeRequest,
                    progressEvents,
                    state,
                    ProductStopReason.TOOL_FAILED,
                    "The reading flow is not ready to produce a precise paper-reading answer."
            );
            recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
            return result;
        }

        ReadingTurnState state = new ReadingTurnState(ReadingTurnFrame.from(safeRequest));
        List<Map<String, Object>> messages = initialMessages(safeRequest, state);
        boolean toolSucceeded = false;
        boolean firstToolCallRequiredIssued = false;
        reactLoop:
        for (int round = 0; round < safeRequest.modelContext().maxReActRounds(); round++) {
            LlmProviderRouter.ReActTurn turn;
            try {
                turn = llmProviderRouter.completeReActTurn(
                        requesterId(safeRequest.userId()),
                        messageSnapshot(messages),
                        tools,
                        safeRequest.modelContext().maxCompletionTokens()
                );
            } catch (Exception exception) {
                ProductTurnResult result = modelUnavailableResult(
                        safeRequest,
                        progressEvents,
                        state,
                        toolCalls,
                        messages,
                        round + 1
                );
                recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
                return result;
            }
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
                        firstToolCallRequiredIssued = true;
                        continue;
                    }
                    ProductTurnResult result = preciseIncompleteResult(
                            safeRequest,
                            progressEvents,
                            state,
                            ProductStopReason.ANSWER_SCHEMA_INVALID,
                            "A validated reading observation is required before answering."
                    );
                    recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
                    return result;
                }
                if (needsReadAfterSemanticLocationSearchBeforeFinal(state)
                        && round + 1 < safeRequest.modelContext().maxReActRounds()) {
                    messages.add(assistantMessage(turn));
                    messages.add(readLocationsRequiredAfterSemanticLocationsMessage());
                    continue;
                }
                if (needsReadAfterSemanticLocationSearchBeforeFinal(state)) {
                    ProductTurnResult result = maxReactRoundsReached(safeRequest, progressEvents, state);
                    recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
                    return result;
                }
                ProductTurnResult result = finalResultOrPreciseIncomplete(
                        safeRequest,
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
                ReadingToolCallValidation validation = validateToolCall(toolCall, state);
                if (!validation.isAllowed()) {
                    if ("typed_location_query_plan_missing".equals(validation.reason())) {
                        ProductTurnResult result = preciseIncompleteResult(
                                safeRequest,
                                progressEvents,
                                state,
                                ProductStopReason.ANSWER_SCHEMA_INVALID,
                                "The location search needs a validated typed query plan before reading."
                        );
                        recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
                        return result;
                    }
                    ProductToolResult rejected = new ProductToolResult(
                            safeToolName(toolCall),
                            false,
                            Map.of("error", validation.reason()),
                            ProductToolEffect.ERROR
                    );
                    toolCalls.add(toolCall(round + 1, toolCall, rejected, Instant.now(), Instant.now()));
                    if (firstToolCallRequiredIssued
                            && isRecoverableToolValidation(validation.reason())
                            && round + 1 < safeRequest.modelContext().maxReActRounds()) {
                        messages.add(toolMessage(toolCall == null ? "" : toolCall.id(), rejected.contentJson(objectMapper)));
                        messages.add(toolValidationCorrectionMessage(validation.reason()));
                        continue reactLoop;
                    }
                    ProductTurnResult result = preciseIncompleteResult(
                            safeRequest,
                            progressEvents,
                            state,
                            ProductStopReason.TOOL_FAILED,
                            toolValidationMessage(validation.reason())
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
                    ProductTurnResult result = preciseIncompleteResult(
                            safeRequest,
                            progressEvents,
                            state,
                            ProductStopReason.TOOL_FAILED,
                            "A reading operation failed before returning validated observations."
                    );
                    recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
                    return result;
                }
                rememberToolArguments(toolResult.toolName(), safeArguments(toolCall), state);
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
                if (TRACE_SOURCE_QUOTES_TOOL_NAME.equals(toolResult.toolName())) {
                    state.traceSourceQuotesUsed = true;
                }
                toolSucceeded = true;
                successfulToolResults.add(toolResult);
            }
            if (!successfulToolResults.isEmpty()) {
                messages.add(toolResultsPolicyMessage(successfulToolResults));
            }
        }

        ProductTurnResult result = maxReactRoundsReached(safeRequest, progressEvents, state);
        recordTrace(safeRequest, result, llmCalls, toolCalls, startedAt);
        return result;
    }

    private boolean validToolSurface(List<ToolDefinition> tools) {
        List<String> names = tools == null
                ? List.of()
                : tools.stream().map(ToolDefinition::name).toList();
        return REQUIRED_TOOL_NAMES.equals(names);
    }

    private ProductTurnResult modelUnavailableResult(ProductTurnRequest request,
                                                     List<ToolProgressEvent> progressEvents,
                                                     ReadingTurnState state,
                                                     List<Map<String, Object>> toolCalls,
                                                     List<Map<String, Object>> messages,
                                                     int round) {
        if (state != null && !state.sourceQuotePayloads.isEmpty()) {
            return deterministicSourceQuoteResult(request, progressEvents, state);
        }
        SyntheticToolExecution structuredActionExecution = executeStructuredActionToolIfAvailable(
                request,
                state,
                progressEvents,
                toolCalls,
                messages,
                round
        );
        if (structuredActionExecution.terminalResult() != null) {
            return structuredActionExecution.terminalResult();
        }
        if (structuredActionExecution.executed()) {
            if (state != null && !state.sourceQuotePayloads.isEmpty()) {
                return deterministicSourceQuoteResult(request, progressEvents, state);
            }
            return preciseIncompleteResult(
                    request,
                    progressEvents,
                    state,
                    ProductStopReason.ANSWER_SCHEMA_INVALID,
                    "The selected reading action returned observations, but no quote-backed claim was validated."
            );
        }
        return preciseIncompleteResult(
                request,
                progressEvents,
                state,
                ProductStopReason.ANSWER_SCHEMA_INVALID,
                hasPreciseObservationState(state)
                        ? "The cards below are checkable observations, but no quote-backed claim has been validated yet."
                        : "The model call failed before producing a validated reading answer."
        );
    }

    private ReadingToolCallValidation validateToolCall(LlmProviderRouter.ToolCallDecision toolCall, ReadingTurnState state) {
        return toolCallGate.validate(safeToolName(toolCall), safeArguments(toolCall), state);
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
        if (OUTLINE_TOOL_NAME.equals(toolName)) {
            state.deterministicNavigationToolUsed = true;
            for (Map<String, Object> paper : mapList(toolResult.data().get("papers"))) {
                for (Map<String, Object> section : mapList(paper.get("sections"))) {
                    String sectionRef = stringValue(section.get("sectionRef"));
                    if (!sectionRef.isBlank()) {
                        state.disclosedLocationRefs.add(sectionRef);
                        Map<String, Object> location = new LinkedHashMap<>();
                        location.put("locationRef", sectionRef);
                        location.put("locationType", "SECTION");
                        copyIfPresent(location, paper, "paperId");
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
            rememberSemanticLocationStatus(toolResult, state);
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
        if (TRACE_SOURCE_QUOTES_TOOL_NAME.equals(toolName)) {
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

    private void rememberSemanticLocationStatus(ProductToolResult toolResult, ReadingTurnState state) {
        if (toolResult == null || state == null || toolResult.data() == null) {
            return;
        }
        String status = stringValue(toolResult.data().get("status"));
        if ("NO_MATCH".equals(status)) {
            state.semanticLocationEvidenceMissing = true;
            state.retrievalStatusPayload.put("semanticLocationEvidence", Map.of(
                    "status", status,
                    "missingEvidence", "semantic_location_evidence"
            ));
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
        copyIfPresent(location, rawLocation, "paperId");
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
        if (READ_LOCATION_ACTION.equals(state.readingAction) && !state.readLocationsUsed) {
            return READ_LOCATIONS_TOOL_NAME;
        }
        if (TRACE_SOURCE_QUOTE_ACTION.equals(state.readingAction) && !state.traceSourceQuotesUsed) {
            return TRACE_SOURCE_QUOTES_TOOL_NAME;
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
        if (LOCATION_TOOL_NAME.equals(requiredToolName)) {
            return SyntheticToolExecution.failed(preciseIncompleteResult(
                    request,
                    progressEvents,
                    state,
                    ProductStopReason.ANSWER_SCHEMA_INVALID,
                    "The location search needs a validated typed query plan before reading."
            ));
        }
        Map<String, Object> arguments = structuredActionArguments(requiredToolName, request, state);
        if (arguments.isEmpty()) {
            return SyntheticToolExecution.failed(preciseIncompleteResult(
                    request,
                    progressEvents,
                    state,
                    ProductStopReason.TOOL_FAILED,
                    "The requested reading action is missing a validated target."
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
        if (LIST_LOCATIONS_TOOL_NAME.equals(toolName)) {
            if (state == null || state.deterministicLocationPaperHandles.isEmpty()) {
                return Map.of();
            }
            return Map.of("paperHandles", List.copyOf(state.deterministicLocationPaperHandles));
        }
        if (READ_LOCATIONS_TOOL_NAME.equals(toolName)) {
            if (state == null || state.disclosedLocationRefs.isEmpty()) {
                return Map.of();
            }
            return Map.of("locationRefs", List.copyOf(state.disclosedLocationRefs));
        }
        if (TRACE_SOURCE_QUOTES_TOOL_NAME.equals(toolName)) {
            if (state == null || state.traceableSourceQuoteRefs.isEmpty()) {
                return Map.of();
            }
            return Map.of("sourceQuoteRefs", List.copyOf(state.traceableSourceQuoteRefs));
        }
        return Map.of();
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
        ReadingToolCallValidation validation = validateToolCall(toolCall, state);
        if (!validation.isAllowed()) {
            ProductToolResult rejected = new ProductToolResult(
                    safeToolName(toolCall),
                    false,
                    Map.of("error", validation.reason()),
                    ProductToolEffect.ERROR
            );
            toolCalls.add(toolCall(round, toolCall, rejected, Instant.now(), Instant.now()));
            return SyntheticToolExecution.failed(preciseIncompleteResult(
                    request,
                    progressEvents,
                    state,
                    ProductStopReason.TOOL_FAILED,
                    toolValidationMessage(validation.reason())
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
            return SyntheticToolExecution.failed(preciseIncompleteResult(
                    request,
                    progressEvents,
                    state,
                    ProductStopReason.TOOL_FAILED,
                    "A reading operation failed before returning validated observations."
            ));
        }
        rememberToolArguments(toolResult.toolName(), arguments, state);
        updateState(toolResult, state);
        markToolSatisfied(toolResult, state);
        return SyntheticToolExecution.executed(toolResult);
    }

    private void rememberToolArguments(String toolName, Map<String, Object> arguments, ReadingTurnState state) {
        if (state == null || arguments == null || arguments.isEmpty()) {
            return;
        }
        if (SEARCH_TOOL_NAME.equals(toolName)) {
            String queryText = stringValue(arguments.get("queryText"));
            if (!queryText.isBlank()) {
                state.paperQueryTexts.add(queryText);
            }
        }
        if (LOCATION_TOOL_NAME.equals(toolName)) {
            Map<String, Object> queryPlan = objectMap(arguments.get("queryPlan"));
            String queryText = stringValue(queryPlan.get("queryText"));
            if (!queryText.isBlank()) {
                state.locationQueryTexts.add(queryText);
            }
            String intent = stringValue(queryPlan.get("intent")).toUpperCase(Locale.ROOT);
            if (!intent.isBlank()) {
                state.locationIntents.add(intent);
            }
            String sourceLanguage = stringValue(queryPlan.get("sourceLanguage"));
            if (!sourceLanguage.isBlank()) {
                state.sourceLanguages.add(sourceLanguage);
            }
            String retrievalLanguage = stringValue(queryPlan.get("retrievalLanguage"));
            if (!retrievalLanguage.isBlank()) {
                state.retrievalLanguages.add(retrievalLanguage);
            }
            state.sectionRoles.addAll(upperStringList(queryPlan.get("sectionRoles")));
            List<String> queryPlanLocationTypes = upperStringList(queryPlan.get("locationTypes"));
            if (queryPlanLocationTypes.isEmpty()) {
                state.locationTypes.addAll(upperStringList(arguments.get("locationTypes")));
            } else {
                state.locationTypes.addAll(queryPlanLocationTypes);
            }
            state.locationQueryPlans.add(new ReadingIntentFrame.LocationQueryPlan(
                    queryText,
                    intent,
                    sourceLanguage,
                    retrievalLanguage,
                    upperStringList(queryPlan.get("sectionRoles")),
                    queryPlanLocationTypes.isEmpty()
                            ? upperStringList(arguments.get("locationTypes"))
                            : queryPlanLocationTypes
            ));
        }
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
        if (TRACE_SOURCE_QUOTES_TOOL_NAME.equals(toolResult.toolName())) {
            state.traceSourceQuotesUsed = true;
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
                                                      ReadingTurnState state) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", systemPrompt(request, state)));
        messages.add(message("user", request.userMessage()));
        return messages;
    }

    private String systemPrompt(ProductTurnRequest request,
                                ReadingTurnState state) {
        Set<String> clickedPaperHandles = state == null ? Set.of() : state.clickedPaperHandles;
        String readingAction = state == null ? "" : state.readingAction;
        Set<String> clickedSourceQuoteRefs = state == null ? Set.of() : state.clickedSourceQuoteRefs;
        Set<String> clickedLocationRefs = state == null ? Set.of() : state.clickedLocationRefs;
        return """
                You are PaperLoom Product Reading ReAct Source Quote MVP.
                Available tools are exactly get_session_state, list_papers, search_paper_candidates, find_papers_by_identity, get_paper_outline, list_paper_locations, find_reading_locations, read_locations, and trace_source_quotes.
                Use get_session_state for fixed search-scope label and readable paper count.
                Use list_papers for deterministic browse/filter inside the fixed scope.
                list_papers is not semantic search; use search_paper_candidates for topic discovery.
                Paper cards from list_papers are navigation only, not Source Quotes.
                Paper handles returned by list_papers may be used with get_paper_outline, list_paper_locations, or find_reading_locations.
                Use search_paper_candidates for paper candidate discovery.
                Fresh topic discovery is a paper-shortlist step: after search_paper_candidates returns candidates for a turn with no explicit location/read/citation UI action, complete with PRODUCT_STATE paper cards instead of immediately searching inside those newly discovered papers.
                Use find_papers_by_identity for a specific paper by title, filename, DOI, arXiv id, author, or year.
                find_papers_by_identity is not semantic search; use search_paper_candidates for topic discovery.
                Identity paper cards are navigation only, not Source Quotes.
                Unambiguous paper handles returned by find_papers_by_identity may be used with get_paper_outline, list_paper_locations, or find_reading_locations.
                If find_papers_by_identity returns AMBIGUOUS, ask the user to clarify or choose a paper before reading content.
                Use get_paper_outline after choosing papers when structure, section choices, or parser quality is needed.
                get_paper_outline requires paperHandles disclosed by list_papers, search_paper_candidates, find_papers_by_identity, trace_source_quotes, or explicit clicked paper anchors in this turn.
                get_paper_outline returns sectionRef values for navigation only; they are not Source Quotes.
                Use find_reading_locations for semantic in-paper location search; it requires paperHandles disclosed by list_papers, search_paper_candidates, unambiguous find_papers_by_identity, or explicit clicked paper anchors in this turn, plus queryPlan with paper-language queryText, intent, languages, sectionRoles, and optional locationTypes.
                Use list_paper_locations for deterministic section/page/table/figure refs; it requires paperHandles disclosed by list_papers, search_paper_candidates, unambiguous find_papers_by_identity, trace_source_quotes, or explicit clicked paper anchors in this turn.
                Use read_locations only after explicit locationRef or sectionRef values were returned by get_paper_outline, find_reading_locations, or list_paper_locations in this turn, or provided as explicit clicked location anchors in this turn.
                Use trace_source_quotes only for sourceQuoteRefs listed in this turn's explicit clicked Source Quote anchors or in the persisted selected Source Quote target.
                trace_source_quotes returned locationRef values are metadata, not read_locations input.
                To read broader context around a traced Source Quote, call list_paper_locations with the traced paperHandle and pageNumber or location type, then call read_locations with refs returned by list_paper_locations.
                Paper previews, paper outlines, parserQuality, and reading-location previews are navigation only, not Source Quotes.
                read_locations and trace_source_quotes are the only Source Quote tools in this slice.
                clicked Source Quote anchors are trace-tool inputs only; they are not citeable until trace_source_quotes returns them in this turn.
                Do not invent paperHandle, locationRef, or sourceQuoteRef values.
                Do not pass ordinals as tool input.
                Do not pass limit, topK, modelVersion, indexName, chunkRef, paperId, question, query, readingNeed, semanticNeed, sourceQuoteRef, splitPolicyVersion, or contentHash.
                trace_source_quotes accepts only sourceQuoteRefs from explicit clicked Source Quote anchors or the persisted selected Source Quote target; never use display citations like [1] as tool input.
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
                Do not put beginnerRole, paperRole, or role decisions in stateClaims. Beginner paper roles must come from product metadata returned by tools or from quote-backed evidence, not model inference over titles.
                If a quoted passage directly supports a beginner paper role, put beginnerRole on that evidenceBasedClaims item only when it has exactly one sourceQuoteRefs value.
                paperRef, evidenceRef, and citationRef are legacy identifiers for the old harness. Do not use them as reading tool arguments or citation support.
                Explicit clicked paper anchors for this turn:
                %s
                Clicked paper anchors are navigation only, not Source Quotes.
                Use clicked paper handles only with get_paper_outline, list_paper_locations, or find_reading_locations.
                Explicit Product Reading UI action for this turn:
                %s
                If the explicit Product Reading UI action is SEARCH_PAPERS, call search_paper_candidates before any other tool. Use the current user request as queryText.
                If the explicit Product Reading UI action is LIST_LOCATIONS, call list_paper_locations before any other tool. Use the explicit clicked paperHandle anchors for paperHandles.
                If the explicit Product Reading UI action is FIND_LOCATIONS, call find_reading_locations before any other navigation or reading tool. You must provide queryPlan with queryText, intent, sourceLanguage, retrievalLanguage, sectionRoles, and optional locationTypes. Do not rely on Java keyword routing; declare the plan yourself.
                If the explicit Product Reading UI action is READ_LOCATION, call read_locations before any other tool. Use the explicit clicked locationRef anchors for locationRefs.
                If the explicit Product Reading UI action is TRACE_SOURCE_QUOTE, call trace_source_quotes before any other tool. Use the explicit clicked Source Quote anchors for sourceQuoteRefs.
                Explicit clicked location anchors for this turn:
                %s
                Clicked location anchors are read_locations inputs only; they are not citeable until read_locations returns Source Quotes in this turn.
                Explicit clicked Source Quote anchors for this turn:
                %s
                Persisted current reading target for this conversation:
                %s
                Persisted current paper and location targets are navigation inputs only. Persisted selected Source Quote targets are trace inputs only. They are not paper-content evidence until get_paper_outline, list_paper_locations, find_reading_locations, read_locations, or trace_source_quotes returns current-turn observations.
                Current user request:
                %s
                """.formatted(
                clickedPaperAnchorPrompt(clickedPaperHandles),
                readingActionPrompt(readingAction),
                clickedLocationAnchorPrompt(clickedLocationRefs),
                clickedSourceQuoteAnchorPrompt(clickedSourceQuoteRefs),
                readingStatePatchPrompt(state == null ? Map.of() : state.persistedReadingStatePatch),
                request.userMessage()
        );
    }

    private ProductTurnResult finalResult(ProductTurnRequest request,
                                          String rawContent,
                                          List<ToolProgressEvent> progressEvents,
                                          ReadingTurnState state) {
        AnswerEnvelope envelope;
        try {
            envelope = parseEnvelope(rawContent);
        } catch (Exception exception) {
            return failed(ANSWER_SCHEMA_INVALID_MESSAGE, progressEvents, ProductStopReason.ANSWER_SCHEMA_INVALID);
        }
        if (containsVisibleInternalLeak(envelope.answer(), envelope.answerType(), state)) {
            return failed(VISIBLE_INTERNAL_LEAK_MESSAGE,
                    progressEvents, ProductStopReason.ANSWER_SCHEMA_INVALID);
        }
        if (NUMBERED_CITATION_PATTERN.matcher(envelope.answer()).find()) {
            return failed("Answer envelope contains forbidden numbered citations.",
                    progressEvents, ProductStopReason.ANSWER_SCHEMA_INVALID);
        }
        if (envelope.answerType() == AnswerType.EVIDENCE_ANSWER) {
            CitationValidation validation = validateSourceQuoteAnswer(envelope, state);
            if (!validation.isValid()) {
                return failed(validation.reason(), progressEvents, ProductStopReason.CITATION_VALIDATION_FAILED);
            }
            if (claimTextContainsVisibleInternalLeak(envelope, state)) {
                return failed(VISIBLE_INTERNAL_LEAK_MESSAGE,
                        progressEvents, ProductStopReason.ANSWER_SCHEMA_INVALID);
            }
            CitationRender render = renderSourceQuoteCitations(envelope, state);
            ReadingTurnProjection projection = artifactProjection(state, envelope, render);
            ReadingArtifactContractValidation artifactValidation = artifactContractValidator.validate(
                    envelope,
                    projection,
                    render.references()
            );
            if (!artifactValidation.valid()) {
                return preciseIncompleteResult(
                        request,
                        progressEvents,
                        state,
                        ProductStopReason.CITATION_VALIDATION_FAILED,
                        artifactValidation.reason()
                );
            }
            ReadingTurnArtifacts artifacts = projection.artifacts();
            String answer = answerPresenter.render(envelope, artifacts, render.markdown());
            if (containsVisibleInternalLeak(answer, envelope.answerType(), state)) {
                return failed(VISIBLE_INTERNAL_LEAK_MESSAGE,
                        progressEvents, ProductStopReason.ANSWER_SCHEMA_INVALID);
            }
            AnswerEnvelope presentedEnvelope = envelopeWithAnswer(envelope, answer);
            ReadingResearchTrace researchTrace = researchTrace(
                    request,
                    state,
                    presentedEnvelope,
                    artifacts,
                    render.references(),
                    artifactValidation,
                    ProductStopReason.COMPLETED,
                    ProductResultStatus.COMPLETED
            );
            ReadingResearchTraceContractValidation traceValidation =
                    researchTraceContractValidator.validateForCompletion(
                            researchTrace,
                            presentedEnvelope,
                            ProductResultStatus.COMPLETED
                    );
            if (!traceValidation.valid()) {
                return preciseIncompleteResult(
                        request,
                        progressEvents,
                        state,
                        ProductStopReason.CITATION_VALIDATION_FAILED,
                        traceValidation.reason()
                );
            }
            artifacts = withTraceSummary(artifacts, researchTrace);
            return new ProductTurnResult(
                    answer,
                    presentedEnvelope,
                    render.references(),
                    progressEvents,
                    state.productStateItems,
                    artifacts,
                    projection.statePatch(),
                    researchTrace,
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
        ReadingTurnProjection projection = artifactProjection(state, envelope, null);
        ReadingArtifactContractValidation artifactValidation = artifactContractValidator.validate(
                envelope,
                projection,
                List.of()
        );
        if (!artifactValidation.valid()) {
            return preciseIncompleteResult(
                    request,
                    progressEvents,
                    state,
                    ProductStopReason.ANSWER_SCHEMA_INVALID,
                    artifactValidation.reason()
            );
        }
        ReadingTurnArtifacts artifacts = projection.artifacts();
        String answer = answerPresenter.render(envelope, artifacts, envelope.answer());
        if (containsVisibleInternalLeak(answer, envelope.answerType(), state)) {
            return failed(VISIBLE_INTERNAL_LEAK_MESSAGE,
                    progressEvents, ProductStopReason.ANSWER_SCHEMA_INVALID);
        }
        AnswerEnvelope presentedEnvelope = envelopeWithAnswer(envelope, answer);
        ReadingResearchTrace researchTrace = researchTrace(
                request,
                state,
                presentedEnvelope,
                artifacts,
                List.of(),
                artifactValidation,
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
        ReadingResearchTraceContractValidation traceValidation =
                researchTraceContractValidator.validateForCompletion(
                        researchTrace,
                        presentedEnvelope,
                        ProductResultStatus.COMPLETED
                );
        if (!traceValidation.valid()) {
            return preciseIncompleteResult(
                    request,
                    progressEvents,
                    state,
                    ProductStopReason.ANSWER_SCHEMA_INVALID,
                    traceValidation.reason()
            );
        }
        artifacts = withTraceSummary(artifacts, researchTrace);
        return new ProductTurnResult(
                answer,
                presentedEnvelope,
                List.of(),
                progressEvents,
                state.productStateItems,
                artifacts,
                projection.statePatch(),
                researchTrace,
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
    }

    private ProductTurnResult finalResultOrPreciseIncomplete(ProductTurnRequest request,
                                                             String rawContent,
                                                             List<ToolProgressEvent> progressEvents,
                                                             ReadingTurnState state) {
        ProductTurnResult result = finalResult(request, rawContent, progressEvents, state);
        if (result.resultStatus() == ProductResultStatus.FAILED) {
            return preciseIncompleteResult(request, progressEvents, state, result.stopReason(), result.finalAnswerMarkdown());
        }
        return result;
    }

    private ProductTurnResult deterministicSourceQuoteResult(ProductTurnRequest request,
                                                             List<ToolProgressEvent> progressEvents,
                                                             ReadingTurnState state) {
        List<String> sourceQuoteRefs = state == null
                ? List.of()
                : state.sourceQuotePayloads.keySet().stream()
                .filter(ref -> state.allowedSourceQuoteRefs.contains(ref))
                .limit(5)
                .toList();
        if (sourceQuoteRefs.isEmpty()) {
            return preciseIncompleteResult(
                    request,
                    progressEvents,
                    state,
                    ProductStopReason.ANSWER_SCHEMA_INVALID,
                    "I found no validated quoted passage for this reading action."
            );
        }

        AnswerEnvelope envelope = deterministicSourceQuoteEnvelope(sourceQuoteRefs, state);
        CitationValidation validation = validateSourceQuoteAnswer(envelope, state);
        if (!validation.isValid()) {
            return preciseIncompleteResult(
                    request,
                    progressEvents,
                    state,
                    ProductStopReason.CITATION_VALIDATION_FAILED,
                    validation.reason()
            );
        }
        CitationRender render = renderSourceQuoteCitations(envelope, state);
        ReadingTurnProjection projection = artifactProjection(state, envelope, render);
        ReadingArtifactContractValidation artifactValidation = artifactContractValidator.validate(
                envelope,
                projection,
                render.references()
        );
        if (!artifactValidation.valid()) {
            return preciseIncompleteResult(
                    request,
                    progressEvents,
                    state,
                    ProductStopReason.CITATION_VALIDATION_FAILED,
                    artifactValidation.reason()
            );
        }
        ReadingTurnArtifacts artifacts = projection.artifacts();
        String answer = answerPresenter.render(envelope, artifacts, render.markdown());
        if (containsVisibleInternalLeak(answer, envelope.answerType(), state)) {
            return preciseIncompleteResult(
                    request,
                    progressEvents,
                    state,
                    ProductStopReason.ANSWER_SCHEMA_INVALID,
                    "A quoted passage was retrieved, but the visible answer still contained non-user-facing identifiers."
            );
        }
        AnswerEnvelope presentedEnvelope = envelopeWithAnswer(envelope, answer);
        ReadingResearchTrace researchTrace = researchTrace(
                request,
                state,
                presentedEnvelope,
                artifacts,
                render.references(),
                artifactValidation,
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
        ReadingResearchTraceContractValidation traceValidation =
                researchTraceContractValidator.validateForCompletion(
                        researchTrace,
                        presentedEnvelope,
                        ProductResultStatus.COMPLETED
                );
        if (!traceValidation.valid()) {
            return preciseIncompleteResult(
                    request,
                    progressEvents,
                    state,
                    ProductStopReason.CITATION_VALIDATION_FAILED,
                    traceValidation.reason()
            );
        }
        artifacts = withTraceSummary(artifacts, researchTrace);
        return new ProductTurnResult(
                answer,
                presentedEnvelope,
                render.references(),
                progressEvents,
                state.productStateItems,
                artifacts,
                projection.statePatch(),
                researchTrace,
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
    }

    private AnswerEnvelope deterministicSourceQuoteEnvelope(List<String> sourceQuoteRefs,
                                                            ReadingTurnState state) {
        List<Map<String, Object>> claims = new ArrayList<>();
        List<String> answerParts = new ArrayList<>();
        int index = 1;
        for (String sourceQuoteRef : sourceQuoteRefs) {
            Map<String, Object> payload = state.sourceQuotePayloads.getOrDefault(sourceQuoteRef, Map.of());
            String quote = snippet(stringValue(payload.get("content")), 220);
            String claim = quote.isBlank()
                    ? "The selected reading target contains a quoted passage."
                    : "The selected reading target contains this quoted passage: \"" + quote + "\"";
            claims.add(Map.of(
                    "claim", claim,
                    "sourceQuoteRefs", List.of(sourceQuoteRef)
            ));
            answerParts.add("Quoted passage " + index + " is available for the selected reading target "
                    + "{{sourceQuoteRef:" + sourceQuoteRef + "}}.");
            index += 1;
        }
        return new AnswerEnvelope(
                AnswerType.EVIDENCE_ANSWER,
                String.join(" ", answerParts),
                claims,
                List.of(),
                List.of("This is a direct quote-level reading result; it does not prove broader claims outside the cited passage."),
                List.of(),
                List.of(),
                "deterministic_source_quote_result"
        );
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

    private ReadingTurnProjection artifactProjection(ReadingTurnState state,
                                                     AnswerEnvelope envelope,
                                                     CitationRender citationRender) {
        return artifactProjector.project(
                observationLedger(state),
                envelope,
                citationRender == null ? List.of() : citationRender.references(),
                citationRender == null ? Map.of() : citationRender.numbersByRef()
        );
    }

    private ReadingResearchTrace researchTrace(ProductTurnRequest request,
                                               ReadingTurnState state,
                                               AnswerEnvelope envelope,
                                               ReadingTurnArtifacts artifacts,
                                               List<Map<String, Object>> references,
                                               ReadingArtifactContractValidation validation,
                                               ProductStopReason stopReason,
                                               ProductResultStatus resultStatus) {
        return researchTraceProjector.project(
                questionId(request),
                observationLedger(state),
                envelope,
                artifacts,
                references,
                validation,
                stopReason,
                resultStatus
        );
    }

    private ReadingTurnArtifacts withTraceSummary(ReadingTurnArtifacts artifacts,
                                                  ReadingResearchTrace researchTrace) {
        ReadingTurnArtifacts safeArtifacts = artifacts == null ? ReadingTurnArtifacts.empty("") : artifacts;
        return new ReadingTurnArtifacts(
                safeArtifacts.artifactVersion(),
                safeArtifacts.goalCard(),
                safeArtifacts.intentFrame(),
                safeArtifacts.paperShortlist(),
                safeArtifacts.readingPlan(),
                safeArtifacts.claimEvidencePanel(),
                safeArtifacts.missingEvidence(),
                safeArtifacts.uiActions(),
                safeArtifacts.uncertaintyNotes(),
                researchTraceSummaryProjector.summarize(researchTrace)
        );
    }

    private String questionId(ProductTurnRequest request) {
        if (request == null) {
            return "";
        }
        String generationId = stringValue(request.generationId());
        if (!generationId.isBlank()) {
            return generationId;
        }
        return stringValue(request.conversationId());
    }

    private ReadingTurnObservationLedger observationLedger(ReadingTurnState state) {
        if (state == null) {
            return ReadingTurnObservationLedger.empty("");
        }
        return new ReadingTurnObservationLedger(
                visibleGoal(state),
                intentFrame(state),
                state.sessionStatePayload,
                state.productStateItems,
                state.paperPayloadsByHandle,
                state.locationPayloads,
                state.sourceQuotePayloads,
                state.retrievalStatusPayload
        );
    }

    private String visibleGoal(ReadingTurnState state) {
        if (state == null) {
            return "";
        }
        return switch (stringValue(state.readingAction)) {
            case LIST_LOCATIONS_ACTION -> "find readable locations in " + selectedPaperLabel(state);
            case READ_LOCATION_ACTION -> readLocationGoal(state);
            case TRACE_SOURCE_QUOTE_ACTION -> traceSourceQuoteGoal(state);
            default -> state.userGoal;
        };
    }

    private String readLocationGoal(ReadingTurnState state) {
        String location = selectedLocationLabel(state);
        String paper = selectedPaperLabel(state);
        if (!location.isBlank() && !paper.isBlank() && !"the selected paper".equals(paper)) {
            return "read " + location + " in " + paper;
        }
        if (!location.isBlank()) {
            return "read " + location;
        }
        return "read the selected passage";
    }

    private String traceSourceQuoteGoal(ReadingTurnState state) {
        Map<String, Object> selectedSourceQuote = objectMap(state.persistedReadingStatePatch.get("selectedSourceQuote"));
        String marker = stringValue(selectedSourceQuote.get("citationMarker"));
        return marker.isBlank() ? "explain the selected citation" : "explain citation " + marker;
    }

    private String selectedPaperLabel(ReadingTurnState state) {
        if (state == null) {
            return "the selected paper";
        }
        for (Map<String, Object> sourceQuote : state.sourceQuotePayloads.values()) {
            String label = stringValue(sourceQuote.get("paperTitle"));
            if (!label.isBlank()) {
                return label;
            }
        }
        for (Map<String, Object> location : state.locationPayloads.values()) {
            String label = firstNonBlank(
                    stringValue(location.get("title")),
                    stringValue(location.get("originalFilename"))
            );
            if (!label.isBlank()) {
                return label;
            }
        }
        Map<String, Object> selectedPaper = objectMap(state.persistedReadingStatePatch.get("selectedPaper"));
        String label = firstNonBlank(
                stringValue(selectedPaper.get("title")),
                stringValue(selectedPaper.get("originalFilename"))
        );
        return label.isBlank() ? "the selected paper" : label;
    }

    private String selectedLocationLabel(ReadingTurnState state) {
        if (state == null) {
            return "";
        }
        for (Map<String, Object> sourceQuote : state.sourceQuotePayloads.values()) {
            String label = sourceQuoteLabel(sourceQuote);
            if (!label.isBlank()) {
                return label;
            }
        }
        for (Map<String, Object> location : state.locationPayloads.values()) {
            String label = locationLabel(location);
            if (!label.isBlank()) {
                return label;
            }
        }
        Map<String, Object> selectedLocation = objectMap(state.persistedReadingStatePatch.get("selectedLocation"));
        return firstNonBlank(stringValue(selectedLocation.get("label")), "the selected passage");
    }

    private ReadingIntentFrame intentFrame(ReadingTurnState state) {
        if (state == null) {
            return ReadingIntentFrame.empty("");
        }
        return ReadingIntentFrame.observed(
                state.userGoal,
                state.readingAction,
                state.paperQueryTexts,
                state.locationQueryTexts,
                state.locationQueryPlans,
                List.copyOf(state.locationTypes),
                List.copyOf(state.locationIntents),
                List.copyOf(state.sourceLanguages),
                List.copyOf(state.retrievalLanguages),
                List.copyOf(state.sectionRoles)
        );
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
            String paperId = stringValue(row.get("paperId"));
            if (!PAPER_HANDLE_PATTERN.matcher(paperHandle).matches()
                    || paperId.isBlank()
                    || !state.productStatePaperHandles.add(paperHandle)) {
                continue;
            }
            rememberPaperPayload(state, paperHandle, row);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", READING_PAPER_CHOICE_KIND);
            item.put("sourceTool", sourceTool);
            item.put("paperHandle", paperHandle);
            copyStringIfPresent(item, row, "title");
            copyStringIfPresent(item, row, "originalFilename");
            copyStringListIfPresent(item, row, "authors");
            copyNumberIfPresent(item, row, "year");
            copyStringIfPresent(item, row, "venue");
            copyStringListIfPresent(item, row, "matchReasons");
            if (identitySource) {
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

    private void rememberPaperPayload(ReadingTurnState state,
                                      String paperHandle,
                                      Map<String, Object> rawPaper) {
        if (state == null || paperHandle == null || paperHandle.isBlank() || rawPaper == null) {
            return;
        }
        Map<String, Object> paper = new LinkedHashMap<>();
        paper.put("paperHandle", paperHandle);
        copyIfPresent(paper, rawPaper, "paperId");
        copyIfPresent(paper, rawPaper, "title");
        copyIfPresent(paper, rawPaper, "originalFilename");
        copyIfPresent(paper, rawPaper, "authors");
        copyIfPresent(paper, rawPaper, "year");
        copyIfPresent(paper, rawPaper, "venue");
        copyIfPresent(paper, rawPaper, "matchReasons");
        copyIfPresent(paper, rawPaper, "paperRoles");
        copyIfPresent(paper, rawPaper, "beginnerRoles");
        copyIfPresent(paper, rawPaper, "readingRoles");
        copyIfPresent(paper, rawPaper, "paperTypes");
        copyIfPresent(paper, rawPaper, "catalogTopics");
        state.paperPayloadsByHandle.putIfAbsent(paperHandle, paper);
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
        if (claimRefs.isEmpty()) {
            return CitationValidation.invalid("Source-quoted answer requires claim-level sourceQuoteRefs.");
        }
        if (!visibleRefs.isEmpty() && !visibleRefs.equals(claimRefs)) {
            return CitationValidation.invalid("Visible sourceQuoteRef markers and claim-level sourceQuoteRefs must match.");
        }
        if (!state.allowedSourceQuoteRefs.containsAll(claimRefs)) {
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

    private boolean claimTextContainsVisibleInternalLeak(AnswerEnvelope envelope, ReadingTurnState state) {
        if (envelope == null) {
            return false;
        }
        for (Map<String, Object> claim : envelope.evidenceBasedClaims()) {
            if (containsVisibleInternalLeak(stringValue(claim.get("claim")), AnswerType.NON_EVIDENCE, state)) {
                return true;
            }
        }
        return false;
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

    private CitationRender renderSourceQuoteCitations(AnswerEnvelope envelope, ReadingTurnState state) {
        String answer = envelope == null ? "" : envelope.answer();
        Set<String> visibleRefs = sourceQuoteMarkers(answer);
        if (visibleRefs.isEmpty()) {
            return renderClaimSourceQuoteCitations(answer, claimSourceQuoteRefs(envelope), state);
        }
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
        return new CitationRender(markdown.toString(), references, Map.copyOf(numbersByRef));
    }

    private CitationRender renderClaimSourceQuoteCitations(String answer,
                                                           Set<String> sourceQuoteRefs,
                                                           ReadingTurnState state) {
        Map<String, Integer> numbersByRef = new LinkedHashMap<>();
        List<Map<String, Object>> references = new ArrayList<>();
        List<String> markers = new ArrayList<>();
        for (String sourceQuoteRef : sourceQuoteRefs == null ? Set.<String>of() : sourceQuoteRefs) {
            if (sourceQuoteRef == null || sourceQuoteRef.isBlank()) {
                continue;
            }
            int number = numbersByRef.size() + 1;
            numbersByRef.put(sourceQuoteRef, number);
            references.add(reference(number, sourceQuoteRef, state.sourceQuotePayloads.get(sourceQuoteRef)));
            markers.add("[" + number + "]");
        }
        String markdown = stringValue(answer);
        if (!markers.isEmpty()) {
            markdown = markdown.isBlank()
                    ? String.join(" ", markers)
                    : markdown + " " + String.join(" ", markers);
        }
        return new CitationRender(markdown, references, Map.copyOf(numbersByRef));
    }

    private Map<String, Object> reference(Integer referenceNumber,
                                          String sourceQuoteRef,
                                          Map<String, Object> sourceQuotePayload) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("referenceNumber", referenceNumber);
        reference.put("sourceQuoteRef", sourceQuoteRef);
        if (sourceQuotePayload != null) {
            copyIfPresent(reference, sourceQuotePayload, "paperId");
            copyIfPresent(reference, sourceQuotePayload, "paperVersion");
            copyIfPresent(reference, sourceQuotePayload, "paperHandle");
            copyIfPresent(reference, sourceQuotePayload, "paperTitle");
            copyIfPresent(reference, sourceQuotePayload, "locationRef");
            copyIfPresent(reference, sourceQuotePayload, "locationType");
            copyIfPresent(reference, sourceQuotePayload, "pageNumber");
            copyIfPresent(reference, sourceQuotePayload, "pageEndNumber");
            copyIfPresent(reference, sourceQuotePayload, "sectionTitle");
            copyIfPresent(reference, sourceQuotePayload, "contentKind");
            copyIfPresent(reference, sourceQuotePayload, "content");
            copyIfPresent(reference, sourceQuotePayload, "sourceSpanJson");
            copyIfPresent(reference, sourceQuotePayload, "visualRegions");
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

    private boolean containsVisibleInternalLeak(String text, AnswerType answerType) {
        String value = text == null ? "" : text;
        if (answerType == AnswerType.EVIDENCE_ANSWER) {
            value = SOURCE_QUOTE_MARKER_PATTERN.matcher(value).replaceAll("");
        }
        if (containsForbiddenOutputToken(value)) {
            return true;
        }
        List<String> visibleInternalTokens = List.of(
                "paper_handle_",
                "page_ref_",
                "section_ref_",
                "location_ref_",
                "source_quote_",
                "paperHandle",
                "locationRef",
                "sourceQuoteRef",
                "parserQuality",
                "parserName",
                "parserVersion",
                "AUTO_SOURCE",
                "AUTO_LIBRARY",
                "SOURCE_SET_SNAPSHOT",
                "immutable=true",
                "Source Quote",
                SESSION_TOOL_NAME,
                LIST_PAPERS_TOOL_NAME,
                SEARCH_TOOL_NAME,
                IDENTITY_TOOL_NAME,
                "get_paper_outline",
                LIST_LOCATIONS_TOOL_NAME,
                LOCATION_TOOL_NAME,
                READ_LOCATIONS_TOOL_NAME,
                "trace_source_quotes"
        );
        for (String token : visibleInternalTokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsVisibleInternalLeak(String text, AnswerType answerType, ReadingTurnState state) {
        return containsVisibleInternalLeak(text, answerType)
                || containsKnownInternalIdentity(text, answerType, state);
    }

    private boolean containsKnownInternalIdentity(String text, AnswerType answerType, ReadingTurnState state) {
        String value = text == null ? "" : text;
        if (answerType == AnswerType.EVIDENCE_ANSWER) {
            value = SOURCE_QUOTE_MARKER_PATTERN.matcher(value).replaceAll("");
        }
        if (value.isBlank() || state == null) {
            return false;
        }
        for (Map<String, Object> paper : state.paperPayloadsByHandle.values()) {
            if (containsSensitiveValue(value, paper, "paperId", "paperVersion", "modelVersion")) {
                return true;
            }
        }
        for (Map<String, Object> location : state.locationPayloads.values()) {
            if (containsSensitiveValue(value, location, "paperId", "paperVersion", "modelVersion")) {
                return true;
            }
        }
        for (Map<String, Object> sourceQuote : state.sourceQuotePayloads.values()) {
            if (containsSensitiveValue(value, sourceQuote, "paperId", "paperVersion", "modelVersion")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSensitiveValue(String visibleText, Map<String, Object> payload, String... keys) {
        if (visibleText == null || visibleText.isBlank() || payload == null || payload.isEmpty() || keys == null) {
            return false;
        }
        for (String key : keys) {
            String token = stringValue(payload.get(key));
            if (isSensitiveDynamicToken(token) && visibleText.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSensitiveDynamicToken(String token) {
        String value = stringValue(token);
        return value.length() >= 6;
    }

    private AnswerEnvelope envelopeWithAnswer(AnswerEnvelope envelope, String answer) {
        return new AnswerEnvelope(
                envelope == null ? AnswerType.NON_EVIDENCE : envelope.answerType(),
                answer,
                envelope == null ? List.of() : envelope.evidenceBasedClaims(),
                envelope == null ? List.of() : envelope.stateClaims(),
                envelope == null ? List.of() : envelope.limitations(),
                envelope == null ? List.of() : envelope.nonEvidenceNotes(),
                envelope == null ? List.of() : envelope.missingFields(),
                envelope == null ? "" : envelope.reason()
        );
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

    private List<String> upperStringList(Object value) {
        if (!(value instanceof List<?> rawValues)) {
            return List.of();
        }
        return rawValues.stream()
                .map(this::stringValue)
                .filter(item -> !item.isBlank())
                .map(item -> item.toUpperCase(Locale.ROOT))
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

    private ProductTurnResult maxReactRoundsReached(ProductTurnRequest request,
                                                    List<ToolProgressEvent> progressEvents,
                                                    ReadingTurnState state) {
        String reason = hasPreciseObservationState(state)
                ? "Reading ReAct round budget reached before a validated final answer."
                : "Reading ReAct round budget reached before any reading observation was validated.";
        return preciseIncompleteResult(
                request,
                progressEvents,
                state,
                ProductStopReason.MAX_REACT_ROUNDS,
                reason
        );
    }

    private boolean hasPreciseObservationState(ReadingTurnState state) {
        return state != null && observationLedger(state).hasObservations();
    }

    private ProductTurnResult preciseIncompleteResult(List<ToolProgressEvent> progressEvents,
                                                      ReadingTurnState state,
                                                      ProductStopReason stopReason,
                                                      String reason) {
        return preciseIncompleteResult(null, progressEvents, state, stopReason, reason);
    }

    private ProductTurnResult preciseIncompleteResult(ProductTurnRequest request,
                                                      List<ToolProgressEvent> progressEvents,
                                                      ReadingTurnState state,
                                                      ProductStopReason stopReason,
                                                      String reason) {
        AnswerEnvelope draftEnvelope = new AnswerEnvelope(
                AnswerType.INSUFFICIENT_EVIDENCE,
                safeVisibleIncompleteAnswer(stopReason, state, reason),
                List.of(),
                List.of(),
                preciseIncompleteLimitations(state, stopReason, reason),
                List.of("The structured cards below are observations, not paper-content claims."),
                List.of("validated_final_answer"),
                stopReason == null ? "precise_incomplete" : stopReason.name()
        );
        ReadingTurnProjection projection = artifactProjection(state, draftEnvelope, null);
        ReadingTurnArtifacts artifacts = projection.artifacts();
        ReadingArtifactContractValidation artifactValidation = artifactContractValidator.validate(
                draftEnvelope,
                projection,
                List.of()
        );
        String answer = answerPresenter.render(draftEnvelope, artifacts, draftEnvelope.answer());
        AnswerEnvelope envelope = envelopeWithAnswer(draftEnvelope, answer);
        ProductStopReason safeStopReason = stopReason == null ? ProductStopReason.ANSWER_SCHEMA_INVALID : stopReason;
        ReadingResearchTrace researchTrace = researchTrace(
                request,
                state,
                envelope,
                artifacts,
                List.of(),
                artifactValidation,
                safeStopReason,
                ProductResultStatus.INCOMPLETE_PRECISE
        );
        artifacts = withTraceSummary(artifacts, researchTrace);
        return new ProductTurnResult(
                answer,
                envelope,
                List.of(),
                progressEvents,
                state == null ? List.of() : state.productStateItems,
                artifacts,
                projection.statePatch(),
                researchTrace,
                safeStopReason,
                ProductResultStatus.INCOMPLETE_PRECISE
        );
    }

    private String toolValidationMessage(String reason) {
        return switch (stringValue(reason)) {
            case "hidden_paper_handle" -> "The paper target was not validated in the current reading context.";
            case "hidden_location_ref" -> "The reading location was not validated in the current reading context.";
            case "hidden_source_quote_ref" -> "The citation target was not validated in the current reading context.";
            case "typed_location_query_plan_missing" -> "The location search needs a validated typed query plan before reading.";
            case "semantic_location_evidence_missing" -> "The typed in-paper search found no matching passage, so this turn cannot switch to outline navigation as a substitute.";
            default -> "The reading action did not satisfy the validated tool contract.";
        };
    }

    private boolean isRecoverableToolValidation(String reason) {
        return switch (stringValue(reason)) {
            case "hidden_paper_handle", "hidden_location_ref", "hidden_source_quote_ref" -> true;
            default -> false;
        };
    }

    private Map<String, Object> toolValidationCorrectionMessage(String reason) {
        String instruction = switch (stringValue(reason)) {
            case "hidden_paper_handle" -> """
                    The previous tool call used a paperHandle that was not disclosed by a current-turn tool result or explicit clicked paper anchor.
                    Your next response must contain tool_calls only.
                    For the current user request, call get_session_state, list_papers, search_paper_candidates, or find_papers_by_identity before using any paperHandle.
                    Do not use persisted paper targets or older conversation handles as tool arguments.
                    """;
            case "hidden_location_ref" -> """
                    The previous tool call used a locationRef that was not disclosed by a current-turn navigation tool result or explicit clicked location anchor.
                    Your next response must contain tool_calls only.
                    First call get_paper_outline, list_paper_locations, or find_reading_locations with authorized paperHandles, then read only the returned locationRefs.
                    """;
            case "hidden_source_quote_ref" -> """
                    The previous tool call used a sourceQuoteRef that was not an explicit clicked Source Quote anchor or persisted selected Source Quote target for this turn.
                    Your next response must contain tool_calls only.
                    Do not use display citations or older sourceQuoteRefs as trace_source_quotes input.
                    """;
            default -> """
                    The previous tool call did not satisfy the validated reading tool contract.
                    Your next response must contain tool_calls only and use only arguments disclosed in this turn.
                    """;
        };
        return message("user", instruction);
    }

    private String safeVisibleIncompleteAnswer(ProductStopReason stopReason, ReadingTurnState state, String reason) {
        String value = stringValue(reason);
        ProductStopReason safeStopReason = stopReason == null ? ProductStopReason.ANSWER_SCHEMA_INVALID : stopReason;
        if (safeStopReason == ProductStopReason.ANSWER_SCHEMA_INVALID) {
            if (!value.isBlank()
                    && !isInternalValidationFailure(value)
                    && !containsVisibleInternalLeak(value, AnswerType.NON_EVIDENCE)) {
                return value;
            }
            if (hasPreciseObservationState(state)) {
                return "The cards below are checkable observations, but no quote-backed claim has been validated yet.";
            }
            return "I could not produce a validated final reading answer from the current observations.";
        }
        if (safeStopReason == ProductStopReason.CITATION_VALIDATION_FAILED) {
            if (!value.isBlank() && !containsVisibleInternalLeak(value, AnswerType.NON_EVIDENCE)) {
                return value;
            }
            if (state != null && !state.sourceQuotePayloads.isEmpty()) {
                return "A quote was retrieved, but it did not support a validated cited claim in this turn.";
            }
            return "I could not verify the citation evidence for this answer.";
        }
        if (safeStopReason == ProductStopReason.MAX_REACT_ROUNDS) {
            return "I could not finish validating the answer within this turn.";
        }
        if (safeStopReason == ProductStopReason.TOOL_FAILED) {
            return value.isBlank() ? "The reading action did not satisfy the validated tool contract." : value;
        }
        if (!value.isBlank() && !containsVisibleInternalLeak(value, AnswerType.NON_EVIDENCE)) {
            return value;
        }
        return "I could not produce a validated final reading answer from the current observations.";
    }

    private boolean isInternalValidationFailure(String value) {
        String text = stringValue(value);
        return ANSWER_SCHEMA_INVALID_MESSAGE.equals(text)
                || VISIBLE_INTERNAL_LEAK_MESSAGE.equals(text)
                || "Answer envelope contains forbidden numbered citations.".equals(text);
    }

    private List<String> preciseIncompleteLimitations(ReadingTurnState state,
                                                      ProductStopReason stopReason,
                                                      String reason) {
        List<String> limitations = new ArrayList<>();
        limitations.add(safeVisibleIncompleteAnswer(stopReason, state, reason));
        if (state != null && !state.productStateItems.isEmpty() && state.sourceQuotePayloads.isEmpty()) {
            limitations.add("Only paper metadata was observed; no quoted paper passage was validated.");
        }
        if (state != null && !state.locationPayloads.isEmpty() && state.sourceQuotePayloads.isEmpty()) {
            limitations.add("Only reading locations were observed; no location was validated as quote-backed evidence.");
        }
        if (state != null && !state.sourceQuotePayloads.isEmpty()) {
            limitations.add("A quoted passage was retrieved, but no cited claim passed verification.");
        }
        if (limitations.isEmpty()) {
            limitations.add("A validated reading answer is missing.");
        }
        return List.copyOf(limitations);
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
        if (normalizedLabel(label).equals(normalizedLabel(pageLabel))) {
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

    private String sourceQuoteLabel(Map<String, Object> quote) {
        String paperTitle = stringValue(quote.get("paperTitle"));
        String sectionTitle = stringValue(quote.get("sectionTitle"));
        String page = pageLabel(quote);
        List<String> parts = new ArrayList<>();
        if (!sectionTitle.isBlank() && !normalizedLabel(sectionTitle).equals(normalizedLabel(paperTitle))) {
            parts.add(sectionTitle);
        }
        if (!page.isBlank()) {
            parts.add(page);
        }
        return parts.isEmpty() ? "the cited passage" : String.join(", ", parts);
    }

    private String normalizedLabel(String value) {
        return stringValue(value)
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);
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
            anchorInstruction = "Use the explicit clicked paperHandle anchors and a typed queryPlan for this turn's request. The queryPlan must include queryText, intent, sourceLanguage, retrievalLanguage, and sectionRoles.";
        } else if (READ_LOCATIONS_TOOL_NAME.equals(requiredToolName)) {
            anchorInstruction = "Use the explicit clicked locationRef anchors as locationRefs.";
        } else if (TRACE_SOURCE_QUOTES_TOOL_NAME.equals(requiredToolName)) {
            anchorInstruction = "Use the explicit clicked Source Quote anchors as sourceQuoteRefs.";
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
                    || TRACE_SOURCE_QUOTES_TOOL_NAME.equals(toolResult.toolName()))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> safeArguments(LlmProviderRouter.ToolCallDecision toolCall) {
        return toolCall == null || toolCall.arguments() == null ? Map.of() : toolCall.arguments();
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

    private String clickedLocationAnchorPrompt(Set<String> clickedLocationRefs) {
        if (clickedLocationRefs == null || clickedLocationRefs.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(clickedLocationRefs);
        } catch (Exception exception) {
            return clickedLocationRefs.toString();
        }
    }

    private String readingStatePatchPrompt(Map<String, Object> readingStatePatch) {
        if (readingStatePatch == null || readingStatePatch.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(readingStatePatch);
        } catch (Exception exception) {
            return readingStatePatch.toString();
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

    private record CitationValidation(boolean isValid, String reason) {
        static CitationValidation valid() {
            return new CitationValidation(true, "");
        }

        static CitationValidation invalid(String reason) {
            return new CitationValidation(false, reason);
        }
    }

    private record CitationRender(String markdown,
                                  List<Map<String, Object>> references,
                                  Map<String, Integer> numbersByRef) {
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
