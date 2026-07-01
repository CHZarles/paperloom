package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.PaperConversationReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ProductReActHarness {

    private static final Logger log = LoggerFactory.getLogger(ProductReActHarness.class);
    private static final Pattern EVIDENCE_MARKER_PATTERN =
            Pattern.compile("\\{\\{\\s*evidenceRef\\s*:\\s*([^}\\s]+)\\s*}}");
    private static final Pattern MODEL_NUMBERED_CITATION_PATTERN =
            Pattern.compile("\\[(\\d+)]");
    private static final Pattern OPAQUE_PAPER_REF_PATTERN =
            Pattern.compile("^paper_[A-Za-z0-9_-]+$");

    private final LlmProviderRouter llmProviderRouter;
    private final ProductToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ProductTraceRecorder traceRecorder;
    private final ConversationReferenceRegistry referenceRegistry;

    public ProductReActHarness(LlmProviderRouter llmProviderRouter,
                               ProductToolRegistry toolRegistry,
                               ObjectMapper objectMapper) {
        this(llmProviderRouter, toolRegistry, objectMapper, null, null);
    }

    public ProductReActHarness(LlmProviderRouter llmProviderRouter,
                               ProductToolRegistry toolRegistry,
                               ObjectMapper objectMapper,
                               ProductTraceRecorder traceRecorder) {
        this(llmProviderRouter, toolRegistry, objectMapper, traceRecorder, null);
    }

    @Autowired
    public ProductReActHarness(LlmProviderRouter llmProviderRouter,
                               ProductToolRegistry toolRegistry,
                               ObjectMapper objectMapper,
                               ProductTraceRecorder traceRecorder,
                               ConversationReferenceRegistry referenceRegistry) {
        this.llmProviderRouter = llmProviderRouter;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.traceRecorder = traceRecorder;
        this.referenceRegistry = referenceRegistry;
    }

    public ProductTurnResult run(ProductTurnRequest request) {
        Instant startedAt = Instant.now();
        ProductTurnRequest safeRequest = request == null
                ? new ProductTurnRequest(null, "", "", "", SourceScope.auto(), List.of(), Map.of(), ProductModelContext.defaults())
                : request;
        List<AgentToolRegistry.AgentTool> tools = toolRegistry.listTools();
        List<Map<String, Object>> messages = initialMessages(safeRequest);
        List<ToolProgressEvent> progressEvents = new ArrayList<>();
        List<Map<String, Object>> llmCalls = new ArrayList<>();
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        Set<String> allowedEvidenceRefs = new LinkedHashSet<>();
        Map<String, Map<String, Object>> evidencePayloads = new LinkedHashMap<>();
        Set<ProductToolEffect> successfulToolEffects = new LinkedHashSet<>();

        int maxRounds = safeRequest.modelContext().maxReActRounds();
        for (int round = 0; round < maxRounds; round++) {
            if (round == maxRounds - 1 && !successfulToolEffects.isEmpty()) {
                messages.add(finalRoundRequiredMessage());
            }
            List<Map<String, Object>> promptMessages = copyMessages(messages);
            LlmProviderRouter.ReActTurn turn = llmProviderRouter.completeReActTurn(
                    requesterId(safeRequest.userId()),
                    messages,
                    tools,
                    safeRequest.modelContext().maxCompletionTokens()
            );
            llmCalls.add(llmCall(round + 1, promptMessages, tools, turn));
            if (turn.toolCalls().isEmpty()) {
                if (successfulToolEffects.isEmpty()) {
                    if (!tools.isEmpty() && round + 1 < safeRequest.modelContext().maxReActRounds()) {
                        messages.add(turn.assistantMessage());
                        messages.add(firstToolCallRequiredMessage());
                        continue;
                    }
                    return withTraceStatus(
                            failed(
                                    "Product tool call is required before the final answer.",
                                    progressEvents,
                                    ProductStopReason.ANSWER_SCHEMA_INVALID
                            ),
                            safeRequest,
                            llmCalls,
                            toolCalls,
                            startedAt
                    );
                }
                ProductTurnResult candidate = finalResult(
                        turn.content(),
                        progressEvents,
                        allowedEvidenceRefs,
                        evidencePayloads,
                        successfulToolEffects,
                        safeRequest
                );
                if (shouldRetryFinalFailure(candidate)
                        && round + 1 < safeRequest.modelContext().maxReActRounds()) {
                    messages.add(turn.assistantMessage());
                    messages.add(answerEnvelopeCorrectionMessage(
                            candidate.stopReason(),
                            successfulToolEffects,
                            allowedEvidenceRefs
                    ));
                    continue;
                }
                return withTraceStatus(
                        candidate,
                        safeRequest,
                        llmCalls,
                        toolCalls,
                        startedAt
                );
            }
            messages.add(turn.assistantMessage());
            if (round == maxRounds - 1) {
                return withTraceStatus(
                        maxReactRoundsReached(progressEvents),
                        safeRequest,
                        llmCalls,
                        toolCalls,
                        startedAt
                );
            }
            for (LlmProviderRouter.ToolCallDecision toolCall : turn.toolCalls()) {
                ToolCallValidation validation = validateToolCall(toolCall, successfulToolEffects, allowedEvidenceRefs);
                if (!validation.allowed()) {
                    Instant rejectedAt = Instant.now();
                    ProductToolResult rejectedResult = rejectedToolResult(toolCall, validation);
                    toolCalls.add(rejectedToolCall(round + 1, toolCall, rejectedResult, rejectedAt));
                    messages.add(toolMessage(toolCall.id(), rejectedResult.contentJson(objectMapper)));
                    messages.add(toolCallRejectedPolicyMessage(validation));
                    continue;
                }
                publishProgressEvent(safeRequest, progressEvents, new ToolProgressEvent("calling_tool", toolCall.name()));
                Instant toolStartedAt = Instant.now();
                ProductToolResult toolResult = toolRegistry.execute(
                        toolCall.name(),
                        toolCall.arguments(),
                        new ProductToolContext(
                                safeRequest.userId(),
                                safeRequest.conversationId(),
                                safeRequest.generationId(),
                                safeRequest.lockedScope()
                        )
                );
                if (toolResult == null) {
                    toolResult = new ProductToolResult(
                            toolCall.name(),
                            false,
                            Map.of("error", "product_tool_returned_null"),
                            ProductToolEffect.ERROR
                    );
                }
                toolCalls.add(toolCall(round + 1, toolCall, toolResult, toolStartedAt, Instant.now()));
                if (!toolResult.success()) {
                    return withTraceStatus(
                            failed(
                                    "Product tool failed: " + toolResult.toolName(),
                                    progressEvents,
                                    ProductStopReason.TOOL_FAILED
                            ),
                            safeRequest,
                            llmCalls,
                            toolCalls,
                            startedAt
                    );
                }
                boolean referenceResolutionFailure = isReferenceResolutionFailure(toolResult);
                if (!referenceResolutionFailure) {
                    successfulToolEffects.add(toolResult.effect());
                    allowedEvidenceRefs.addAll(evidenceRefs(toolResult));
                    evidencePayloads.putAll(evidencePayloads(toolResult));
                }
                messages.add(toolMessage(toolCall.id(), toolResult.contentJson(objectMapper)));
                messages.add(toolResultPolicyMessage(toolResult));
            }
        }
        return withTraceStatus(
                maxReactRoundsReached(progressEvents),
                safeRequest,
                llmCalls,
                toolCalls,
                startedAt
        );
    }

    private ProductTurnResult finalResult(String rawContent,
                                          List<ToolProgressEvent> progressEvents,
                                          Set<String> allowedEvidenceRefs,
                                          Map<String, Map<String, Object>> evidencePayloads,
                                          Set<ProductToolEffect> successfulToolEffects,
                                          ProductTurnRequest request) {
        AnswerEnvelope envelope;
        try {
            envelope = parseEnvelope(rawContent);
        } catch (Exception exception) {
            return failed("Answer envelope schema invalid.", progressEvents, ProductStopReason.ANSWER_SCHEMA_INVALID);
        }
        if (!validEvidenceRefs(envelope, allowedEvidenceRefs)) {
            return failed("Evidence answer contains no valid evidence refs.", progressEvents,
                    ProductStopReason.CITATION_VALIDATION_FAILED);
        }
        if (!validToolGrounding(envelope, successfulToolEffects, allowedEvidenceRefs)) {
            return failed("Answer envelope is not grounded in required product tools.", progressEvents,
                    ProductStopReason.CITATION_VALIDATION_FAILED);
        }
        CitationRender citationRender;
        try {
            citationRender = renderCitations(envelope, evidencePayloads);
        } catch (Exception exception) {
            return failed("Citation validation failed.", progressEvents,
                    ProductStopReason.CITATION_VALIDATION_FAILED);
        }
        List<Map<String, Object>> references;
        try {
            references = persistReferences(citationRender.references(), request);
        } catch (Exception exception) {
            return failed("Reference registry persistence failed.", progressEvents,
                    ProductStopReason.REFERENCE_PERSISTENCE_FAILED);
        }
        return new ProductTurnResult(
                renderMarkdown(envelope, citationRender.markdown()),
                envelope,
                references,
                progressEvents,
                ProductStopReason.COMPLETED,
                ProductResultStatus.COMPLETED
        );
    }

    private ProductTurnResult failed(String message,
                                     List<ToolProgressEvent> progressEvents,
                                     ProductStopReason stopReason) {
        AnswerEnvelope envelope = new AnswerEnvelope(
                AnswerType.CLARIFICATION_NEEDED,
                message,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                stopReason.name()
        );
        return new ProductTurnResult(
                message,
                envelope,
                List.of(),
                progressEvents,
                stopReason,
                ProductResultStatus.FAILED
        );
    }

    private ProductTurnResult maxReactRoundsReached(List<ToolProgressEvent> progressEvents) {
        String message = "需要缩小问题或继续新一轮；当前已达到 Product ReAct 工具调用上限，没有生成可校验的最终答案。";
        AnswerEnvelope envelope = new AnswerEnvelope(
                AnswerType.CLARIFICATION_NEEDED,
                message,
                List.of(),
                List.of(),
                List.of(message),
                List.of(),
                List.of("react_round_budget"),
                ProductStopReason.MAX_REACT_ROUNDS.name()
        );
        return new ProductTurnResult(
                message,
                envelope,
                List.of(),
                progressEvents,
                ProductStopReason.MAX_REACT_ROUNDS,
                ProductResultStatus.DEGRADED
        );
    }

    private ProductTurnResult withTraceStatus(ProductTurnResult result,
                                              ProductTurnRequest request,
                                              List<Map<String, Object>> llmCalls,
                                              List<Map<String, Object>> toolCalls,
                                              Instant startedAt) {
        if (traceRecorder == null) {
            return result;
        }
        try {
            traceRecorder.record(request, result, llmCalls, toolCalls, startedAt, Instant.now());
        } catch (Exception exception) {
            log.warn("trace_submit_failed conversationId={} generationId={} artifactType={}",
                    request == null ? "" : request.conversationId(),
                    request == null ? "" : request.generationId(),
                    "PRODUCT_REACT_TURN",
                    exception);
        }
        return result;
    }

    private List<Map<String, Object>> initialMessages(ProductTurnRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", systemPrompt(request)));
        for (Map<String, String> item : request.history()) {
            String role = item == null ? "" : item.get("role");
            String content = item == null ? "" : item.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            if ("user".equals(role) || "assistant".equals(role)) {
                messages.add(message(role, content));
            }
        }
        messages.add(message("user", request.userMessage()));
        return messages;
    }

    private String systemPrompt(ProductTurnRequest request) {
        return """
                You are PaperLoom Product ReAct Harness.

                Use the product function catalog for product state, paper identity, references, and paper evidence.
                Session memory is non-authoritative and cannot replace tools.
                The locked session scope is injected into tools by the backend; do not request raw paper ids.
                Every turn must call at least one product function before any final AnswerEnvelope, even when the current request repeats an earlier request and history or memory appears to contain the answer.
                History and memory cannot ground product state, paper identity, references, pages, citations, or paper evidence.
                Product-state answers require product-state tools and no citations.
                Paper-content answers require evidence tools and valid evidence refs.
                Current user request for this turn:
                <current_user_request>
                %s
                </current_user_request>
                Answer only the current user request. Earlier history and memory are context only; they must not change the intent of the current request.
                Do not answer old open questions from memory unless the current user request asks for them.
                Effect-to-answer-type contract:
                - NO_PRODUCT_STATE from answer_without_product_state requires answerType NON_EVIDENCE, empty evidenceBasedClaims, and empty stateClaims.
                - PRODUCT_STATE, SESSION_SCOPE, PAPER_LIST, PAPER_DISCOVERY, PAPER_RESOLUTION, and PAPER_METADATA require answerType PRODUCT_STATE unless the user asked for paper content and a later evidence tool is needed.
                - EVIDENCE, REFERENCE, and PAGE require answerType EVIDENCE_ANSWER with valid returned evidence refs, or INSUFFICIENT_EVIDENCE when the returned evidence cannot support the requested paper claim.
                Do not add paper-content summaries, contribution summaries, product-state answers, or reference inspection unless the current user request asks for paper content, page, citation, method, experiment, result, limitation, comparison, evidence, product state, session scope, paper count, paper list, or bibliographic metadata.
                For product state, session scope, paper count, paper list, and bibliographic metadata requests, use PRODUCT_STATE and stop after product-state/list/metadata tools provide enough information.
                For paper recommendation, paper discovery, or topic-based paper selection, call find_papers. Do not use list_papers titleQuery as semantic topic search.
                find_papers returns paper candidates and non-citeable selection rationale. Use retrieve_evidence before making paper-content claims about methods, experiments, results, or limitations.
                Only pass paperRef values that were returned by PaperLoom tools or the persistent reference registry. Opaque paperRef values must look like paper_...
                Never pass filenames, arXiv-like ids, DOI values, titles, ordinals, or raw paper ids as paperRef.
                If the user refers to a paper by title, filename, arXiv-like id, ordinal, or previous wording, call resolve_papers first, then use the returned opaque paperRef in later tools.
                If the current user request does not depend on product state, paper identity, references, pages, citations, or paper evidence, call answer_without_product_state and then answer with NON_EVIDENCE.
                For answer_without_product_state results, use NON_EVIDENCE and do not mention paper counts, paper titles, filenames, processing status, pages, citations, or paper evidence.
                After any tool result, if the current user request is answerable from the tool results already available, output the final AnswerEnvelope immediately. Unnecessary tool calls are invalid.
                In EVIDENCE_ANSWER text, cite evidence with markers exactly like {{evidenceRef:ev_...}}.
                Do not write final numbered citations such as [1]; the harness generates final citation numbers.
                Final answer must be exactly one JSON object following the fixed AnswerEnvelope schema. Do not output plain text.
                The answer field is the visible user answer. Put all user-facing Markdown structure inside answer, not before or after the JSON object.
                For lists, recommendations, comparisons, and multi-item summaries, use Markdown tables or bullets inside the answer field.
                Required final JSON shape:
                {
                  "answerType": "NON_EVIDENCE | PRODUCT_STATE | EVIDENCE_ANSWER | INSUFFICIENT_EVIDENCE | CLARIFICATION_NEEDED",
                  "answer": "direct user-facing answer",
                  "evidenceBasedClaims": [{"claim": "paper claim", "evidenceRefs": ["E1"]}],
                  "stateClaims": [{"claim": "product-state claim", "sourceTool": "list_papers"}],
                  "limitations": ["limitation text"],
                  "nonEvidenceNotes": ["non-evidence note text"],
                  "missingFields": ["missing field name"],
                  "reason": ""
                }
                evidenceBasedClaims and stateClaims must be arrays of JSON objects, never strings.
                limitations, nonEvidenceNotes, and missingFields must be arrays of strings.
                For PRODUCT_STATE, stateClaims must cite the product-state source tool, for example get_system_state, get_session_scope, list_papers, find_papers, resolve_papers, or get_paper_metadata.
                For NON_EVIDENCE, stateClaims must be empty because answer_without_product_state is not a product-state grounding tool.
                Do not include a free-text Sources used section.

                lockedScopeMode=%s
                memory=%s
                """.formatted(request.userMessage(), request.lockedScope().mode().name(), writeJson(request.memory()));
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private Map<String, Object> toolMessage(String toolCallId, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId == null ? "" : toolCallId);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private List<Map<String, Object>> copyMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> message : messages == null ? List.<Map<String, Object>>of() : messages) {
            copy.add(new LinkedHashMap<>(message));
        }
        return copy;
    }

    private Map<String, Object> firstToolCallRequiredMessage() {
        return message("user", """
                The previous response did not call a PaperLoom product function and is rejected.
                Your next response must contain tool_calls only. Do not output plain text, markdown, or an AnswerEnvelope yet.
                Select and call one available product function for the current user request only, not for older history or memory.
                History and memory cannot ground the final answer.
                Use answer_without_product_state when the current user request does not depend on product state, paper identity, references, pages, citations, or paper evidence.
                Do not call product-state or evidence tools for a current request that only needs a non-evidence response.
                """);
    }

    private Map<String, Object> toolResultPolicyMessage(ProductToolResult toolResult) {
        ProductToolEffect effect = toolResult == null ? ProductToolEffect.ERROR : toolResult.effect();
        String toolName = toolResult == null ? "" : toolResult.toolName();
        return message("user", """
                Tool result policy after %s (%s):
                If the current user request is already answered by the tool results, output the final AnswerEnvelope now.
                %s
                """.formatted(toolName, effect.name(), toolResultAnswerPolicy(toolResult)));
    }

    private Map<String, Object> toolCallRejectedPolicyMessage(ToolCallValidation validation) {
        return message("user", """
                Product tool contract correction:
                tool_call_rejected: %s
                The rejected tool was not executed and should not be treated as product evidence or product state.
                %s
                Continue from the valid tool results already present in this turn and output the fixed AnswerEnvelope when possible.
                If citeable evidence refs are already available, answer with answerType EVIDENCE_ANSWER and those evidenceRefs.
                """.formatted(validation.reason(), validation.nextAction()));
    }

    private Map<String, Object> finalRoundRequiredMessage() {
        return message("user", """
                This is the final allowed Product ReAct round for the current user turn.
                If available tool results can support any valid AnswerEnvelope, output that AnswerEnvelope now.
                Do not call another tool unless no valid AnswerEnvelope can be produced from the existing tool results.
                If no valid answer can be produced within this turn, output a CLARIFICATION_NEEDED AnswerEnvelope saying the user needs to narrow the question or continue in a new turn.
                """);
    }

    private Map<String, Object> answerEnvelopeCorrectionMessage(ProductStopReason stopReason,
                                                                Set<ProductToolEffect> successfulToolEffects,
                                                                Set<String> allowedEvidenceRefs) {
        String reason = stopReason == ProductStopReason.CITATION_VALIDATION_FAILED
                ? "The previous final response is rejected because its AnswerEnvelope was not grounded in the required product tool/evidence refs."
                : "The previous final response is rejected because it was not a valid complete AnswerEnvelope JSON object.";
        return message("user", """
                %s
                Re-output only one JSON object. Do not use markdown fences or plain text.
                Use exactly these top-level fields:
                answerType, answer, evidenceBasedClaims, stateClaims, limitations, nonEvidenceNotes, missingFields, reason.
                The answer field is the visible user answer; put any tables, bullets, recommendations, or other user-facing Markdown structure inside answer.
                evidenceBasedClaims and stateClaims must be arrays of JSON objects, never strings.
                For PRODUCT_STATE, stateClaims items must look like {"claim":"...","sourceTool":"list_papers"}.
                limitations, nonEvidenceNotes, and missingFields must be arrays of strings.
                Current successful tool effects: %s.
                Current valid evidence refs: %s.
                %s
                """.formatted(
                reason,
                effectNames(successfulToolEffects),
                allowedEvidenceRefs == null ? List.of() : List.copyOf(allowedEvidenceRefs),
                answerTypePolicyForEffects(successfulToolEffects, allowedEvidenceRefs)
        ));
    }

    private String toolResultAnswerPolicy(ProductToolResult toolResult) {
        ProductToolEffect effect = toolResult == null ? null : toolResult.effect();
        if (isReferenceResolutionFailure(toolResult)) {
            return """
                    This tool result is a paper reference resolution failure, not evidence insufficiency.
                    It does not ground answerType INSUFFICIENT_EVIDENCE and must not be described as the paper being unavailable, unparsed, unindexed, or inaccessible.
                    Call resolve_papers with the user's title, filename, arXiv-like id, ordinal, or wording, then call retrieve_evidence with the returned opaque paperRef.
                    """;
        }
        if (effect == null) {
            return genericAnswerPolicy();
        }
        return switch (effect) {
            case NO_PRODUCT_STATE -> """
                    This NO_PRODUCT_STATE result can only support answerType NON_EVIDENCE.
                    Use empty evidenceBasedClaims and empty stateClaims.
                    Do not include paper counts, paper titles, filenames, processing status, pages, citations, or evidence claims.
                    """;
            case SESSION_SCOPE -> sessionScopeAnswerPolicy(toolResult);
            case PRODUCT_STATE, PAPER_LIST, PAPER_DISCOVERY, PAPER_RESOLUTION, PAPER_METADATA -> productStateAnswerPolicy(toolResult);
            case EVIDENCE, REFERENCE, PAGE -> """
                    This paper-evidence result supports answerType EVIDENCE_ANSWER with evidenceRefs returned by tools.
                    Use INSUFFICIENT_EVIDENCE only when the returned evidence cannot support the requested paper claim.
                    Do not use PRODUCT_STATE for paper-content claims.
                    """;
            case NONE -> """
                    This result does not provide product state or paper evidence. Use NON_EVIDENCE only if the user request needs neither.
                    """;
            case ERROR -> "The tool did not produce usable grounding. Do not treat it as product state or paper evidence.";
        };
    }

    private String sessionScopeAnswerPolicy(ProductToolResult toolResult) {
        Map<String, Object> data = toolResult == null ? Map.of() : toolResult.data();
        if (Boolean.FALSE.equals(data.get("sourcePaperCountKnown"))) {
            return """
                    This SESSION_SCOPE result supports answerType PRODUCT_STATE for scope identity and lock status.
                    It does not provide the actual searchable paper count for AUTO_SOURCE scope.
                    For paper-count or library-state questions, call get_system_state or list_papers before the final AnswerEnvelope.
                    Use empty evidenceBasedClaims and no citations.
                    """;
        }
        return """
                This SESSION_SCOPE result supports answerType PRODUCT_STATE for scope identity, lock status, and fixed source-set size when sourcePaperCountKnown is true.
                Use empty evidenceBasedClaims, no citations, and cite get_session_scope in stateClaims as JSON objects, not strings.
                For broader library-state counts outside the fixed source set, call get_system_state or list_papers before the final AnswerEnvelope.
                """;
    }

    private String productStateAnswerPolicy(ProductToolResult toolResult) {
        String filteredListPolicy = "";
        if (toolResult != null
                && "list_papers".equals(toolResult.toolName())
                && Boolean.TRUE.equals(toolResult.data().get("filtered"))) {
            filteredListPolicy = """
                    For this filtered list_papers result, total/filteredTotal is the number of matched papers only.
                    scopePaperCount is the number of papers in the locked scope after the status filter.
                    Do not say the current library or session has zero papers when scopePaperCount is greater than zero.
                    If filteredTotal is zero, say no papers matched the supplied metadata filter, not that the library is empty.
                    """;
        }
        if (toolResult != null && "find_papers".equals(toolResult.toolName())) {
            filteredListPolicy = """
                    This find_papers result supports paper recommendation, discovery, and topic-based selection only.
                    It does not provide citeable paper evidence and cannot support paper-content claims about methods, experiments, results, or limitations.
                    If the user asks for content-based reasons, call retrieve_evidence for the selected paperRefs before final answer.
                    """;
        }
        return """
                This product-state result supports answerType PRODUCT_STATE.
                Use empty evidenceBasedClaims, no citations, and cite the source tool in stateClaims as JSON objects, not strings.
                Do not turn a product-state, scope, paper-count, paper-list, paper-resolution, or metadata request into a paper-content retrieval task.
                Use retrieve_evidence only when the current user request asks for paper content facts, methods, experiments, results, limitations, comparisons, pages, citations, or evidence.
                %s
                """.formatted(filteredListPolicy);
    }

    private String answerTypePolicyForEffects(Set<ProductToolEffect> successfulToolEffects,
                                              Set<String> allowedEvidenceRefs) {
        Set<ProductToolEffect> effects = successfulToolEffects == null ? Set.of() : successfulToolEffects;
        if (effects.isEmpty()) {
            return "No product tool has succeeded. Call a tool before producing the final AnswerEnvelope.";
        }
        if (effects.stream().allMatch(effect ->
                effect == ProductToolEffect.NONE || effect == ProductToolEffect.NO_PRODUCT_STATE)) {
            return """
                    Only NO_PRODUCT_STATE/non-grounding effects have succeeded.
                    The final answerType must be NON_EVIDENCE.
                    Use empty evidenceBasedClaims and empty stateClaims.
                    answer_without_product_state is not a product-state grounding tool and cannot support PRODUCT_STATE.
                    """;
        }
        if (hasEvidenceToolSuccess(effects)) {
            String refsText = allowedEvidenceRefs == null || allowedEvidenceRefs.isEmpty()
                    ? "No evidence refs are currently available."
                    : "Use only these evidence refs for EVIDENCE_ANSWER: " + List.copyOf(allowedEvidenceRefs) + ".";
            return """
                    Evidence/reference/page tools have succeeded.
                    Use answerType EVIDENCE_ANSWER with valid returned evidenceRefs, or INSUFFICIENT_EVIDENCE if the evidence cannot support the requested paper claim.
                    %s
                    Do not use PRODUCT_STATE for paper-content claims.
                    """.formatted(refsText);
        }
        if (hasProductStateToolSuccess(effects)) {
            return """
                    Product-state, scope, paper-list, paper-discovery, paper-resolution, or metadata tools have succeeded.
                    Use answerType PRODUCT_STATE, empty evidenceBasedClaims, no citations, and cite the source tool in stateClaims.
                    """;
        }
        return genericAnswerPolicy();
    }

    private String genericAnswerPolicy() {
        return """
                Select answerType from the successful tool effects:
                NO_PRODUCT_STATE -> NON_EVIDENCE.
                PRODUCT_STATE/SESSION_SCOPE/PAPER_LIST/PAPER_DISCOVERY/PAPER_RESOLUTION/PAPER_METADATA -> PRODUCT_STATE.
                EVIDENCE/REFERENCE/PAGE -> EVIDENCE_ANSWER or INSUFFICIENT_EVIDENCE.
                """;
    }

    private List<String> effectNames(Set<ProductToolEffect> effects) {
        if (effects == null || effects.isEmpty()) {
            return List.of();
        }
        return effects.stream().map(ProductToolEffect::name).toList();
    }

    private Map<String, Object> llmCall(int round,
                                        List<Map<String, Object>> promptMessages,
                                        List<AgentToolRegistry.AgentTool> tools,
                                        LlmProviderRouter.ReActTurn turn) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("round", round);
        call.put("purpose", "PRODUCT_REACT");
        call.put("promptMessagesJson", promptMessages);
        call.put("toolCatalogJson", tools == null ? List.of() : tools);
        call.put("rawResponseJson", turn.assistantMessage());
        call.put("finishReason", turn.finishReason());
        call.put("promptTokens", turn.promptTokens());
        call.put("completionTokens", turn.completionTokens());
        return call;
    }

    private Map<String, Object> toolCall(int round,
                                         LlmProviderRouter.ToolCallDecision toolCall,
                                         ProductToolResult toolResult,
                                         Instant startedAt,
                                         Instant finishedAt) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("round", round);
        call.put("toolName", toolCall.name());
        call.put("argumentsJson", toolCall.arguments());
        call.put("resultJson", toolResult.data());
        call.put("effect", toolResult.effect().name());
        call.put("success", toolResult.success());
        call.put("executed", true);
        call.put("rejected", false);
        call.put("startedAt", startedAt == null ? null : startedAt.toString());
        call.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        return call;
    }

    private Map<String, Object> rejectedToolCall(int round,
                                                 LlmProviderRouter.ToolCallDecision toolCall,
                                                 ProductToolResult toolResult,
                                                 Instant rejectedAt) {
        Map<String, Object> call = toolCall(round, toolCall, toolResult, rejectedAt, rejectedAt);
        call.put("executed", false);
        call.put("rejected", true);
        return call;
    }

    private String renderMarkdown(AnswerEnvelope envelope, String answerMarkdown) {
        String answer = answerMarkdown == null || answerMarkdown.isBlank() ? envelope.answer() : answerMarkdown;
        if (!envelope.limitations().isEmpty()) {
            answer += "\n\n限制\n" + bulletList(envelope.limitations());
        }
        if (!envelope.nonEvidenceNotes().isEmpty()) {
            answer += "\n\n非论文证据说明\n" + bulletList(envelope.nonEvidenceNotes());
        }
        return answer.trim();
    }

    private String bulletList(List<String> items) {
        return items.stream()
                .map(this::stringValue)
                .filter(item -> !item.isBlank())
                .map(item -> "- " + item)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private CitationRender renderCitations(AnswerEnvelope envelope,
                                           Map<String, Map<String, Object>> evidencePayloads) {
        if (envelope.answerType() != AnswerType.EVIDENCE_ANSWER) {
            return new CitationRender(envelope.answer(), List.of());
        }
        if (MODEL_NUMBERED_CITATION_PATTERN.matcher(envelope.answer()).find()) {
            throw new IllegalArgumentException("model generated final citation numbers");
        }
        Set<String> claimRefs = envelopeEvidenceRefs(envelope);
        LinkedHashMap<String, CitationReference> citations = new LinkedHashMap<>();
        Matcher markerMatcher = EVIDENCE_MARKER_PATTERN.matcher(envelope.answer());
        StringBuffer rendered = new StringBuffer();
        while (markerMatcher.find()) {
            String evidenceRef = markerMatcher.group(1) == null ? "" : markerMatcher.group(1).trim();
            if (!claimRefs.contains(evidenceRef) || !evidencePayloads.containsKey(evidenceRef)) {
                throw new IllegalArgumentException("unknown evidence marker");
            }
            CitationReference citation = citationFor(evidenceRef, citations, evidencePayloads.get(evidenceRef));
            markerMatcher.appendReplacement(rendered, Matcher.quoteReplacement("[" + citation.referenceNumber() + "]"));
        }
        markerMatcher.appendTail(rendered);
        if (!citations.isEmpty()) {
            return new CitationRender(rendered.toString().trim(), new ArrayList<>(citations.values()));
        }

        StringBuilder claimMarkdown = new StringBuilder(envelope.answer().trim());
        if (!claimRefs.isEmpty()) {
            claimMarkdown.append("\n\n依据");
            for (Map<String, Object> claim : envelope.evidenceBasedClaims()) {
                String claimText = stringValue(claim.get("claim"));
                List<CitationReference> claimCitations = citationsForClaim(claim, citations, evidencePayloads);
                if (claimText.isBlank() || claimCitations.isEmpty()) {
                    continue;
                }
                claimMarkdown.append("\n- ").append(claimText);
                for (CitationReference citation : claimCitations) {
                    claimMarkdown.append(" [").append(citation.referenceNumber()).append("]");
                }
            }
        }
        if (citations.isEmpty()) {
            throw new IllegalArgumentException("no renderable citation");
        }
        return new CitationRender(claimMarkdown.toString().trim(), new ArrayList<>(citations.values()));
    }

    private List<CitationReference> citationsForClaim(Map<String, Object> claim,
                                                      LinkedHashMap<String, CitationReference> citations,
                                                      Map<String, Map<String, Object>> evidencePayloads) {
        Object rawRefs = claim.get("evidenceRefs");
        if (!(rawRefs instanceof List<?> list)) {
            return List.of();
        }
        List<CitationReference> claimCitations = new ArrayList<>();
        for (Object item : list) {
            String evidenceRef = item == null ? "" : String.valueOf(item).trim();
            Map<String, Object> payload = evidencePayloads.get(evidenceRef);
            if (evidenceRef.isBlank() || payload == null) {
                throw new IllegalArgumentException("missing evidence payload");
            }
            claimCitations.add(citationFor(evidenceRef, citations, payload));
        }
        return claimCitations;
    }

    private CitationReference citationFor(String evidenceRef,
                                          LinkedHashMap<String, CitationReference> citations,
                                          Map<String, Object> payload) {
        CitationReference existing = citations.get(evidenceRef);
        if (existing != null) {
            return existing;
        }
        int referenceNumber = citations.size() + 1;
        String citationRef = "citation_pending_" + referenceNumber;
        Map<String, Object> display = new LinkedHashMap<>(payload);
        display.put("referenceNumber", referenceNumber);
        display.put("evidenceRef", evidenceRef);
        display.put("citationRef", citationRef);
        CitationReference citation = new CitationReference(referenceNumber, citationRef, evidenceRef, payload, display);
        citations.put(evidenceRef, citation);
        return citation;
    }

    private boolean validEvidenceRefs(AnswerEnvelope envelope, Set<String> allowedEvidenceRefs) {
        if (envelope.answerType() != AnswerType.EVIDENCE_ANSWER) {
            return true;
        }
        Set<String> cited = envelopeEvidenceRefs(envelope);
        return !cited.isEmpty() && allowedEvidenceRefs.containsAll(cited);
    }

    private boolean validToolGrounding(AnswerEnvelope envelope,
                                       Set<ProductToolEffect> successfulToolEffects,
                                       Set<String> allowedEvidenceRefs) {
        Set<ProductToolEffect> effects = successfulToolEffects == null ? Set.of() : successfulToolEffects;
        if (effects.contains(ProductToolEffect.EVIDENCE)
                && allowedEvidenceRefs != null
                && !allowedEvidenceRefs.isEmpty()
                && envelope.answerType() != AnswerType.EVIDENCE_ANSWER
                && envelope.answerType() != AnswerType.INSUFFICIENT_EVIDENCE) {
            return false;
        }
        return switch (envelope.answerType()) {
            case PRODUCT_STATE -> effects.contains(ProductToolEffect.PRODUCT_STATE)
                    || effects.contains(ProductToolEffect.PAPER_LIST)
                    || effects.contains(ProductToolEffect.PAPER_DISCOVERY)
                    || effects.contains(ProductToolEffect.SESSION_SCOPE)
                    || effects.contains(ProductToolEffect.PAPER_RESOLUTION)
                    || effects.contains(ProductToolEffect.PAPER_METADATA);
            case INSUFFICIENT_EVIDENCE -> effects.contains(ProductToolEffect.EVIDENCE)
                    || effects.contains(ProductToolEffect.REFERENCE)
                    || effects.contains(ProductToolEffect.PAGE);
            case NON_EVIDENCE -> effects.isEmpty()
                    || effects.stream().allMatch(effect ->
                    effect == ProductToolEffect.NONE || effect == ProductToolEffect.NO_PRODUCT_STATE);
            case EVIDENCE_ANSWER, CLARIFICATION_NEEDED -> true;
        };
    }

    private ToolCallValidation validateToolCall(LlmProviderRouter.ToolCallDecision toolCall,
                                                Set<ProductToolEffect> successfulToolEffects,
                                                Set<String> currentTurnEvidenceRefs) {
        String toolName = toolCall == null ? "" : stringValue(toolCall.name());
        List<String> nonOpaquePaperRefs = nonOpaquePaperRefsInArguments(safeArguments(toolCall));
        if (!nonOpaquePaperRefs.isEmpty()) {
            return ToolCallValidation.rejected(
                    "non_opaque_paper_ref_in_tool_args",
                    "paperRef/paperRefs arguments must be opaque PaperLoom refs returned by tools or the persistent reference registry. Invalid refs: "
                            + nonOpaquePaperRefs,
                    "Use resolve_papers with the user's title, filename, arXiv-like id, ordinal, or wording. Then call the requested paper tool with the returned paper_... paperRef.",
                    Map.of("invalidPaperRefs", nonOpaquePaperRefs)
            );
        }
        if ("answer_without_product_state".equals(toolName)
                && hasProductStateOrEvidenceToolSuccess(successfulToolEffects)) {
            return ToolCallValidation.rejected(
                    "answer_without_product_state cannot be used after product state, paper identity, metadata, reference, page, or evidence tools have already succeeded in this turn."
            );
        }
        if ("inspect_reference".equals(toolName)) {
            String evidenceRef = stringValue(safeArguments(toolCall).get("evidenceRef"));
            if (!evidenceRef.isBlank()
                    && currentTurnEvidenceRefs != null
                    && currentTurnEvidenceRefs.contains(evidenceRef)) {
                return ToolCallValidation.rejected(
                        "inspect_reference was requested for current-turn evidenceRef " + evidenceRef
                                + "; use the existing retrieve_evidence result directly instead of querying the persistent reference registry before final citation persistence."
                );
            }
        }
        return ToolCallValidation.accepted();
    }

    private boolean hasProductStateOrEvidenceToolSuccess(Set<ProductToolEffect> successfulToolEffects) {
        if (successfulToolEffects == null || successfulToolEffects.isEmpty()) {
            return false;
        }
        for (ProductToolEffect effect : successfulToolEffects) {
            if (effect == ProductToolEffect.PRODUCT_STATE
                    || effect == ProductToolEffect.SESSION_SCOPE
                    || effect == ProductToolEffect.PAPER_LIST
                    || effect == ProductToolEffect.PAPER_DISCOVERY
                    || effect == ProductToolEffect.PAPER_RESOLUTION
                    || effect == ProductToolEffect.PAPER_METADATA
                    || effect == ProductToolEffect.EVIDENCE
                    || effect == ProductToolEffect.REFERENCE
                    || effect == ProductToolEffect.PAGE) {
                return true;
            }
        }
        return false;
    }

    private boolean hasProductStateToolSuccess(Set<ProductToolEffect> successfulToolEffects) {
        if (successfulToolEffects == null || successfulToolEffects.isEmpty()) {
            return false;
        }
        for (ProductToolEffect effect : successfulToolEffects) {
            if (effect == ProductToolEffect.PRODUCT_STATE
                    || effect == ProductToolEffect.SESSION_SCOPE
                    || effect == ProductToolEffect.PAPER_LIST
                    || effect == ProductToolEffect.PAPER_DISCOVERY
                    || effect == ProductToolEffect.PAPER_RESOLUTION
                    || effect == ProductToolEffect.PAPER_METADATA) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEvidenceToolSuccess(Set<ProductToolEffect> successfulToolEffects) {
        if (successfulToolEffects == null || successfulToolEffects.isEmpty()) {
            return false;
        }
        for (ProductToolEffect effect : successfulToolEffects) {
            if (effect == ProductToolEffect.EVIDENCE
                    || effect == ProductToolEffect.REFERENCE
                    || effect == ProductToolEffect.PAGE) {
                return true;
            }
        }
        return false;
    }

    private boolean isReferenceResolutionFailure(ProductToolResult toolResult) {
        return toolResult != null
                && "unresolved_paper_constraints".equals(stringValue(toolResult.data().get("reason")));
    }

    private Map<String, Object> safeArguments(LlmProviderRouter.ToolCallDecision toolCall) {
        if (toolCall == null || toolCall.arguments() == null) {
            return Map.of();
        }
        return toolCall.arguments();
    }

    private List<String> nonOpaquePaperRefsInArguments(Object value) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        collectNonOpaquePaperRefs(value, refs);
        return new ArrayList<>(refs);
    }

    private void collectNonOpaquePaperRefs(Object value, Set<String> refs) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object nestedValue = entry.getValue();
                if ("paperRef".equals(key)) {
                    String ref = stringValue(nestedValue);
                    if (!ref.isBlank() && !isOpaquePaperRef(ref)) {
                        refs.add(ref);
                    }
                    continue;
                }
                if ("paperRefs".equals(key) && nestedValue instanceof List<?> list) {
                    for (Object item : list) {
                        String ref = stringValue(item);
                        if (!ref.isBlank() && !isOpaquePaperRef(ref)) {
                            refs.add(ref);
                        }
                    }
                    continue;
                }
                collectNonOpaquePaperRefs(nestedValue, refs);
            }
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> collectNonOpaquePaperRefs(item, refs));
        }
    }

    private boolean isOpaquePaperRef(String value) {
        return value != null && OPAQUE_PAPER_REF_PATTERN.matcher(value.trim()).matches();
    }

    private ProductToolResult rejectedToolResult(LlmProviderRouter.ToolCallDecision toolCall,
                                                 ToolCallValidation validation) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("error", "tool_call_rejected");
        data.put("code", validation.code());
        data.put("reason", validation.reason());
        data.put("nextAction", validation.nextAction());
        data.put("details", validation.details());
        data.put("executed", false);
        return new ProductToolResult(
                toolCall == null ? "" : toolCall.name(),
                false,
                data,
                ProductToolEffect.ERROR
        );
    }

    private boolean shouldRetryFinalFailure(ProductTurnResult candidate) {
        return candidate.resultStatus() == ProductResultStatus.FAILED
                && (candidate.stopReason() == ProductStopReason.ANSWER_SCHEMA_INVALID
                || candidate.stopReason() == ProductStopReason.CITATION_VALIDATION_FAILED);
    }

    private Set<String> envelopeEvidenceRefs(AnswerEnvelope envelope) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (Map<String, Object> claim : envelope.evidenceBasedClaims()) {
            Object rawRefs = claim.get("evidenceRefs");
            if (rawRefs instanceof List<?> list) {
                for (Object item : list) {
                    String ref = item == null ? "" : String.valueOf(item).trim();
                    if (!ref.isBlank()) {
                        refs.add(ref);
                    }
                }
            }
        }
        return refs;
    }

    private Set<String> evidenceRefs(ProductToolResult toolResult) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        Object evidence = toolResult.data().get("evidence");
        if (evidence instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String ref = stringValue(map.get("evidenceRef"));
                    if (!ref.isBlank()) {
                        refs.add(ref);
                    }
                }
            }
        }
        return refs;
    }

    private Map<String, Map<String, Object>> evidencePayloads(ProductToolResult toolResult) {
        if (!toolResult.evidencePayloads().isEmpty()) {
            return toolResult.evidencePayloads();
        }
        Map<String, Map<String, Object>> payloads = new LinkedHashMap<>();
        Object evidence = toolResult.data().get("evidence");
        if (evidence instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String ref = stringValue(map.get("evidenceRef"));
                    if (!ref.isBlank()) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        map.forEach((key, value) -> payload.put(String.valueOf(key), value));
                        payloads.put(ref, payload);
                    }
                }
            }
        }
        return payloads;
    }

    private List<Map<String, Object>> persistReferences(List<CitationReference> citations,
                                                        ProductTurnRequest request) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        if (referenceRegistry == null) {
            throw new IllegalStateException("reference registry unavailable");
        }
        List<Map<String, Object>> references = new ArrayList<>();
        for (CitationReference citation : citations) {
            String citationRef = citationRef(request.generationId(), citation.referenceNumber());
            Map<String, Object> display = new LinkedHashMap<>(citation.displayPayload());
            display.put("citationRef", citationRef);
            display.put("refId", citationRef);
            display.put("evidenceRef", citation.evidenceRef());
            referenceRegistry.save(new ConversationReferenceRegistry.ReferenceInput(
                    request.conversationId(),
                    scopeSnapshotId(request.lockedScope()),
                    request.generationId(),
                    citation.evidenceRef(),
                    PaperConversationReference.RefType.EVIDENCE,
                    stringValue(citation.sourcePayload().getOrDefault("chunkId", citation.evidenceRef())),
                    citation.sourcePayload(),
                    display
            ));
            referenceRegistry.save(new ConversationReferenceRegistry.ReferenceInput(
                    request.conversationId(),
                    scopeSnapshotId(request.lockedScope()),
                    request.generationId(),
                    citationRef,
                    PaperConversationReference.RefType.CITATION,
                    citation.evidenceRef(),
                    Map.of("evidenceRef", citation.evidenceRef()),
                    display
            ));
            references.add(display);
        }
        return references;
    }

    private String citationRef(String generationId, int referenceNumber) {
        String safeGenerationId = generationId == null || generationId.isBlank()
                ? "turn"
                : generationId.replaceAll("[^A-Za-z0-9_.-]", "_");
        return "citation_" + safeGenerationId + "_" + referenceNumber;
    }

    private String scopeSnapshotId(SourceScope scope) {
        SourceScope safeScope = scope == null ? SourceScope.auto() : scope;
        return safeScope.mode().name() + ":" + safeScope.paperIds().hashCode();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String strictEnvelopeJson(String rawContent) {
        String text = rawContent == null ? "" : rawContent.trim();
        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }
        throw new IllegalArgumentException("final answer must be exactly one JSON object");
    }

    private AnswerEnvelope parseEnvelope(String rawContent) throws Exception {
        JsonNode node = objectMapper.readTree(strictEnvelopeJson(rawContent));
        if (!isCompleteEnvelopeNode(node)) {
            throw new IllegalArgumentException("incomplete answer envelope");
        }
        return objectMapper.treeToValue(node, AnswerEnvelope.class);
    }

    private boolean isCompleteEnvelopeNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        if (!hasTextField(node, "answerType") || !hasTextField(node, "answer") || !hasTextField(node, "reason")) {
            return false;
        }
        try {
            AnswerType.valueOf(node.path("answerType").asText(""));
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return hasArrayField(node, "evidenceBasedClaims")
                && hasArrayField(node, "stateClaims")
                && hasArrayField(node, "limitations")
                && hasArrayField(node, "nonEvidenceNotes")
                && hasArrayField(node, "missingFields");
    }

    private boolean hasTextField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() && value.isTextual();
    }

    private boolean hasArrayField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() && value.isArray();
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private void publishProgressEvent(ProductTurnRequest request,
                                      List<ToolProgressEvent> progressEvents,
                                      ToolProgressEvent event) {
        if (event == null) {
            return;
        }
        progressEvents.add(event);
        try {
            request.progressListener().accept(event);
        } catch (Exception ignored) {
            // Progress events are UI telemetry. A failed listener must not change answer semantics.
        }
    }

    private String requesterId(Long userId) {
        return userId == null ? "" : String.valueOf(userId);
    }

    private static Map<String, Object> immutableMapAllowingNullValues(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        payload.forEach((key, value) -> copied.put(key, immutableValueAllowingNullValues(value)));
        return Collections.unmodifiableMap(copied);
    }

    private static Object immutableValueAllowingNullValues(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            map.forEach((key, nestedValue) ->
                    copied.put(String.valueOf(key), immutableValueAllowingNullValues(nestedValue)));
            return Collections.unmodifiableMap(copied);
        }
        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            for (Object item : list) {
                copied.add(immutableValueAllowingNullValues(item));
            }
            return Collections.unmodifiableList(copied);
        }
        return value;
    }

    private record CitationRender(
            String markdown,
            List<CitationReference> references
    ) {
    }

    private record CitationReference(
            int referenceNumber,
            String citationRef,
            String evidenceRef,
            Map<String, Object> sourcePayload,
            Map<String, Object> displayPayload
    ) {
        private CitationReference {
            citationRef = citationRef == null ? "" : citationRef.trim();
            evidenceRef = evidenceRef == null ? "" : evidenceRef.trim();
            sourcePayload = immutableMapAllowingNullValues(sourcePayload);
            displayPayload = immutableMapAllowingNullValues(displayPayload);
        }
    }

    private record ToolCallValidation(
            boolean allowed,
            String reason,
            String code,
            String nextAction,
            Map<String, Object> details
    ) {
        private static ToolCallValidation accepted() {
            return new ToolCallValidation(true, "", "", "", Map.of());
        }

        private static ToolCallValidation rejected(String reason) {
            return rejected("tool_call_rejected", reason,
                    "Produce a valid AnswerEnvelope from existing valid tool results, or call the correct product tool for the current request.",
                    Map.of());
        }

        private static ToolCallValidation rejected(String code,
                                                   String reason,
                                                   String nextAction,
                                                   Map<String, Object> details) {
            return new ToolCallValidation(false, reason == null || reason.isBlank()
                    ? "tool_call_rejected"
                    : reason,
                    code == null || code.isBlank() ? "tool_call_rejected" : code,
                    nextAction == null ? "" : nextAction.trim(),
                    details == null ? Map.of() : Map.copyOf(details));
        }
    }
}
