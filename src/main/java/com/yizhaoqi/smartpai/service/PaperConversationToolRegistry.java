package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.ConversationScopeMode;
import com.yizhaoqi.smartpai.model.Paper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PaperConversationToolRegistry {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 30;

    private final ObjectMapper objectMapper;
    private final PaperLibraryStatusService paperLibraryStatusService;
    private final ConversationScopeService conversationScopeService;
    private final PaperService paperService;
    private final PaperSearchabilityService paperSearchabilityService;
    private final PaperRetrievalService paperRetrievalService;
    private final EvidenceLedgerService evidenceLedgerService;
    private final EvidenceToolExecutor evidenceToolExecutor;
    private final Map<String, PaperRefState> paperRefsByConversation = new ConcurrentHashMap<>();
    private final List<AgentToolRegistry.AgentTool> tools;

    public PaperConversationToolRegistry(ObjectMapper objectMapper,
                                         PaperLibraryStatusService paperLibraryStatusService,
                                         ConversationScopeService conversationScopeService,
                                         PaperService paperService,
                                         PaperSearchabilityService paperSearchabilityService,
                                         PaperRetrievalService paperRetrievalService,
                                         EvidenceLedgerService evidenceLedgerService,
                                         EvidenceToolExecutor evidenceToolExecutor) {
        this.objectMapper = objectMapper;
        this.paperLibraryStatusService = paperLibraryStatusService;
        this.conversationScopeService = conversationScopeService;
        this.paperService = paperService;
        this.paperSearchabilityService = paperSearchabilityService;
        this.paperRetrievalService = paperRetrievalService;
        this.evidenceLedgerService = evidenceLedgerService;
        this.evidenceToolExecutor = evidenceToolExecutor;
        this.tools = List.of(
                answerWithoutProductStateTool(),
                getSessionScopeTool(),
                getLibraryStatusTool(),
                listPapersTool(),
                resolvePapersTool(),
                getPaperMetadataTool(),
                discoverPapersTool(),
                retrieveEvidenceTool(),
                inspectReferenceTool(),
                inspectPageTool()
        );
    }

    public List<AgentToolRegistry.AgentTool> listTools() {
        return tools;
    }

    public ToolExecutionResult execute(String toolName, Map<String, Object> arguments, ToolContext context) {
        ToolContext safeContext = context == null
                ? new ToolContext("", "", SourceScope.auto(), RetrievalBudgetProfile.INTERACTIVE)
                : context;
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        try {
            return switch (toolName == null ? "" : toolName) {
                case "answer_without_product_state" -> answerWithoutProductState(args);
                case "get_session_scope" -> getSessionScope(safeContext);
                case "get_library_status" -> getLibraryStatus(safeContext);
                case "list_papers" -> listPapers(args, safeContext);
                case "resolve_papers" -> resolvePapers(args, safeContext);
                case "get_paper_metadata" -> getPaperMetadata(args, safeContext);
                case "discover_papers" -> discoverPapers(args, safeContext);
                case "retrieve_evidence" -> retrieveEvidence(args, safeContext);
                case "inspect_reference" -> inspectReference(args, safeContext);
                case "inspect_page" -> inspectPage(args, safeContext);
                default -> error(toolName, "unsupported_tool", Map.of("toolName", toolName == null ? "" : toolName));
            };
        } catch (Exception exception) {
            return error(toolName, exception.getClass().getSimpleName(), Map.of());
        }
    }

    private ToolExecutionResult getSessionScope(ToolContext context) {
        Long userId = parseLong(context.userId());
        Map<String, Object> data = new LinkedHashMap<>();
        if (userId != null && conversationScopeService != null && !context.conversationId().isBlank()) {
            ConversationScopeService.EffectiveConversationScope scope =
                    conversationScopeService.resolveForChat(userId, context.conversationId());
            data.put("scopeMode", scope.mode().name());
            data.put("scopeLocked", scope.locked());
            data.put("scopeStatus", scope.status().name());
            data.put("sourceLabel", scope.label());
            data.put("sourcePaperCount", scope.mode() == ConversationScopeMode.SOURCE_SET_SNAPSHOT
                    ? scope.paperIds().size()
                    : null);
        } else {
            data.put("scopeMode", context.sourceScope().mode().name());
            data.put("scopeLocked", true);
            data.put("scopeStatus", "READY");
            data.put("sourceLabel", "Current session");
            data.put("sourcePaperCount", context.sourceScope().paperIds().isEmpty()
                    ? null
                    : context.sourceScope().paperIds().size());
        }
        data.put("constraints", Map.of(
                "productOnly", true,
                "evalExcluded", true,
                "searchableOnlyForRetrieval", true
        ));
        return result("get_session_scope", true, data, ToolEffect.SESSION_SCOPE);
    }

    private ToolExecutionResult answerWithoutProductState(Map<String, Object> args) {
        String reason = stringArg(args, "reason", "needs_user_clarification_without_product_state");
        String answerDraft = stringArg(args, "answerDraft", "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("allowed", true);
        data.put("reason", reason);
        data.put("answerDraft", answerDraft);
        data.put("constraints", Map.of(
                "mayIncludePaperCounts", false,
                "mayIncludePaperTitles", false,
                "mayIncludeFilenames", false,
                "mayIncludeEvidenceClaims", false
        ));
        return result("answer_without_product_state", true, data, ToolEffect.NO_PRODUCT_STATE);
    }

    private ToolExecutionResult getLibraryStatus(ToolContext context) {
        PaperLibraryStatus status = paperLibraryStatusService.statusFor(context.userId(), context.sourceScope());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accessibleCount", status.accessibleCount());
        data.put("searchableCount", status.searchableCount());
        data.put("selectedScopeCount", status.selectedScopeCount());
        data.put("parsingCount", status.parsingCount());
        data.put("indexingCount", status.indexingCount());
        data.put("failedCount", status.failedCount());
        data.put("warnings", status.consistencyWarnings());
        return result("get_library_status", true, data, ToolEffect.LIBRARY_STATUS);
    }

    private ToolExecutionResult listPapers(Map<String, Object> args, ToolContext context) {
        String statusFilter = stringArg(args, "status", "searchable").toLowerCase(Locale.ROOT);
        int page = intArg(args, "page", DEFAULT_PAGE, 1, Integer.MAX_VALUE);
        int pageSize = intArg(args, "pageSize", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
        List<Paper> filtered = accessiblePapers(context).stream()
                .filter(paper -> statusMatches(statusFilter, paper))
                .sorted(Comparator
                        .comparing(Paper::getMergedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed()
                        .thenComparing(Paper::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int fromIndex = Math.min(filtered.size(), (page - 1) * pageSize);
        int toIndex = Math.min(filtered.size(), fromIndex + pageSize);
        List<Map<String, Object>> papers = filtered.subList(fromIndex, toIndex).stream()
                .map(paper -> paperMetadata(paper, context))
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", filtered.size());
        data.put("page", page);
        data.put("pageSize", pageSize);
        data.put("papers", papers);
        return result("list_papers", true, data, ToolEffect.PAPER_LIST);
    }

    private ToolExecutionResult resolvePapers(Map<String, Object> args, ToolContext context) {
        String selection = stringArg(args, "selection", "");
        LinkedHashSet<String> resolvedPaperIds = new LinkedHashSet<>();
        for (String paperRef : stringListArg(args, "paperRefs")) {
            String paperId = lookupPaperId(context, paperRef);
            if (paperId != null) {
                resolvedPaperIds.add(paperId);
            }
        }
        String refSelection = selection.trim().toUpperCase(Locale.ROOT);
        if (refSelection.matches("P\\d+")) {
            String paperId = lookupPaperId(context, refSelection);
            if (paperId != null) {
                resolvedPaperIds.add(paperId);
            }
        }
        Integer ordinal = ordinal(selection);
        if (ordinal != null) {
            String paperId = lookupPaperIdByOrdinal(context, ordinal);
            if (paperId != null) {
                resolvedPaperIds.add(paperId);
            }
        }
        List<Paper> candidates = accessiblePapers(context);
        if (!selection.isBlank() && resolvedPaperIds.isEmpty()) {
            String normalizedSelection = normalize(selection);
            candidates.stream()
                    .filter(paper -> normalize(displayTitle(paper)).contains(normalizedSelection)
                            || normalize(paper.getOriginalFilename()).contains(normalizedSelection))
                    .map(Paper::getPaperId)
                    .filter(Objects::nonNull)
                    .forEach(resolvedPaperIds::add);
        }
        List<Map<String, Object>> papers = candidates.stream()
                .filter(paper -> resolvedPaperIds.contains(paper.getPaperId()))
                .map(paper -> paperMetadata(paper, context))
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resolved", !papers.isEmpty() && papers.size() == 1);
        data.put("ambiguous", papers.size() > 1);
        data.put("papers", papers);
        data.put("clarificationOptions", papers.size() > 1 ? papers : List.of());
        data.put("total", papers.size());
        return result("resolve_papers", true, data, ToolEffect.PAPER_RESOLUTION);
    }

    private ToolExecutionResult getPaperMetadata(Map<String, Object> args, ToolContext context) {
        Set<String> requestedIds = resolvePaperRefs(context, stringListArg(args, "paperRefs"));
        List<Map<String, Object>> papers = accessiblePapers(context).stream()
                .filter(paper -> requestedIds.isEmpty() || requestedIds.contains(paper.getPaperId()))
                .map(paper -> paperMetadata(paper, context))
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", papers.size());
        data.put("papers", papers);
        return result("get_paper_metadata", true, data, ToolEffect.PAPER_METADATA);
    }

    private ToolExecutionResult discoverPapers(Map<String, Object> args, ToolContext context) {
        String query = stringArg(args, "query", "");
        if (query.isBlank()) {
            return error("discover_papers", "missing_query", Map.of());
        }
        int limit = intArg(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        PaperRetrievalService.RetrievalResult retrieval = paperRetrievalService.discoverPapers(
                query,
                context.userId(),
                RetrievalBudget.forLibrarySearch(),
                context.sourceScope().paperIds()
        );
        EvidenceLedger ledger = evidenceLedgerService.fromSearchResults(
                retrieval == null ? List.of() : retrieval.results(),
                RetrievalBudget.forLibrarySearch()
        );
        List<Map<String, Object>> papers = ledger.sourceSet().stream()
                .limit(limit)
                .map(source -> paperSourceMetadata(source, context))
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", ledger.sourceSet().size());
        data.put("papers", papers);
        data.put("diagnostics", diagnosticsMap(ledger.diagnostics()));
        return result("discover_papers", true, data, ToolEffect.PAPER_DISCOVERY);
    }

    private ToolExecutionResult retrieveEvidence(Map<String, Object> args, ToolContext context) {
        String query = stringArg(args, "query", "");
        if (query.isBlank()) {
            return error("retrieve_evidence", "missing_query", Map.of());
        }
        LinkedHashSet<String> scopedPaperIds = new LinkedHashSet<>(stringListArg(args, "resolvedPaperIds"));
        scopedPaperIds.addAll(resolvePaperRefs(context, stringListArg(args, "paperRefs")));
        List<String> paperIds = new ArrayList<>(scopedPaperIds);
        PlannerAction action = new PlannerAction(
                PlannerActionType.SEARCH_EVIDENCE,
                query,
                "react_tool",
                paperIds,
                null
        );
        EvidenceToolResult toolResult = evidenceToolExecutor.execute(context.userId(), context.conversationId(), action,
                scopedSource(context, paperIds));
        Map<String, Object> data = ledgerData(toolResult.ledger(), context);
        return result("retrieve_evidence", true, data, ToolEffect.EVIDENCE);
    }

    private ToolExecutionResult inspectReference(Map<String, Object> args, ToolContext context) {
        Integer referenceNumber = nullableInt(args.get("referenceNumber"));
        PlannerAction action = new PlannerAction(
                PlannerActionType.INSPECT_REFERENCE,
                "",
                "react_tool",
                List.of(),
                referenceNumber
        );
        EvidenceToolResult toolResult = evidenceToolExecutor.execute(context.userId(), context.conversationId(), action, context.sourceScope());
        Map<String, Object> data = ledgerData(toolResult.ledger(), context);
        data.put("referenceNumber", referenceNumber);
        return result("inspect_reference", true, data, ToolEffect.REFERENCE);
    }

    private ToolExecutionResult inspectPage(Map<String, Object> args, ToolContext context) {
        String paperRef = stringArg(args, "paperRef", "");
        String paperId = stringArg(args, "resolvedPaperId", "");
        if (paperId.isBlank()) {
            paperId = lookupPaperId(context, paperRef);
        }
        Integer pageNumber = nullableInt(args.get("pageNumber"));
        Integer windowRadius = nullableInt(args.get("windowRadius"));
        if (paperId == null || paperId.isBlank() || pageNumber == null) {
            return error("inspect_page", "missing_page_scope", Map.of());
        }
        PlannerAction action = new PlannerAction(
                PlannerActionType.INSPECT_PAGE,
                "",
                "react_tool",
                List.of(paperId),
                null,
                pageNumber,
                windowRadius
        );
        EvidenceToolResult toolResult = evidenceToolExecutor.execute(context.userId(), context.conversationId(), action,
                scopedSource(context, List.of(paperId)));
        Map<String, Object> data = ledgerData(toolResult.ledger(), context);
        data.put("pageNumber", pageNumber);
        return result("inspect_page", true, data, ToolEffect.PAGE);
    }

    private List<Paper> accessiblePapers(ToolContext context) {
        List<Paper> accessible = paperService.getAccessiblePapers(context.userId(), null);
        List<Paper> safeAccessible = accessible == null ? List.of() : accessible;
        Set<String> requested = new LinkedHashSet<>(context.sourceScope().paperIds());
        if (requested.isEmpty()) {
            return safeAccessible;
        }
        return safeAccessible.stream()
                .filter(paper -> paper != null && requested.contains(paper.getPaperId()))
                .toList();
    }

    private boolean statusMatches(String statusFilter, Paper paper) {
        if ("all".equals(statusFilter)) {
            return true;
        }
        return statusFor(paper).equals(statusFilter);
    }

    private String statusFor(Paper paper) {
        if (paperSearchabilityService.isSearchable(paper)) {
            return "searchable";
        }
        String status = paper == null || paper.getVectorizationStatus() == null
                ? ""
                : paper.getVectorizationStatus().trim().toUpperCase(Locale.ROOT);
        if (Paper.VECTORIZATION_STATUS_FAILED.equals(status)) {
            return "failed";
        }
        if (Paper.VECTORIZATION_STATUS_MINERU_RUNNING.equals(status)
                || Paper.VECTORIZATION_STATUS_MINERU_ARTIFACT_SAVED.equals(status)
                || Paper.VECTORIZATION_STATUS_MAPPING_STRUCTURED_CONTENT.equals(status)
                || Paper.VECTORIZATION_STATUS_RENDERING_VISUAL_ASSETS.equals(status)
                || Paper.VECTORIZATION_STATUS_CHUNKING.equals(status)) {
            return "parsing";
        }
        return "indexing";
    }

    private Map<String, Object> paperMetadata(Paper paper, ToolContext context) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paperRef", paperRef(context, paper.getPaperId()));
        data.put("paperId", paper.getPaperId());
        data.put("title", displayTitle(paper));
        data.put("originalFilename", paper.getOriginalFilename());
        data.put("status", statusFor(paper));
        data.put("indexed", paperSearchabilityService.isSearchable(paper));
        data.put("authors", paper.getAuthors());
        data.put("venue", paper.getVenue());
        data.put("year", paper.getPublicationYear());
        data.put("abstract", paper.getAbstractText());
        data.put("doi", paper.getDoi());
        data.put("arxivId", paper.getArxivId());
        return data;
    }

    private Map<String, Object> paperSourceMetadata(PaperSource source, ToolContext context) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paperRef", paperRef(context, source.paperId()));
        data.put("paperId", source.paperId());
        data.put("title", source.paperTitle());
        data.put("originalFilename", source.originalFilename());
        return data;
    }

    private Map<String, Object> ledgerData(EvidenceLedger ledger, ToolContext context) {
        EvidenceLedger safeLedger = ledger == null ? EvidenceLedger.empty() : ledger;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceCount", safeLedger.sourceSet().size());
        data.put("papers", safeLedger.sourceSet().stream()
                .map(source -> paperSourceMetadata(source, context))
                .toList());
        data.put("evidence", safeLedger.evidence().stream()
                .map(item -> evidenceMetadata(item, context))
                .toList());
        data.put("diagnostics", diagnosticsMap(safeLedger.diagnostics()));
        return data;
    }

    private Map<String, Object> evidenceMetadata(EvidenceItem item, ToolContext context) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("evidenceRef", item.evidenceId());
        data.put("paperRef", paperRef(context, item.paperId()));
        data.put("paperId", item.paperId());
        data.put("paperTitle", item.paperTitle());
        data.put("originalFilename", item.originalFilename());
        data.put("pageNumber", item.pageNumber());
        data.put("chunkId", item.chunkId());
        data.put("sourceKind", item.sourceKind());
        data.put("sectionTitle", item.sectionTitle());
        data.put("matchedText", item.matchedText());
        data.put("bboxJson", item.bboxJson());
        data.put("score", item.score());
        data.put("assetLevel", item.evidenceAssetLevel());
        data.put("evidenceAssetLevel", item.evidenceAssetLevel());
        data.put("pdfEvidenceAvailable", item.pdfEvidenceAvailable());
        data.put("pageScreenshotAvailable", item.pageScreenshotAvailable());
        data.put("figureScreenshotAvailable", item.figureScreenshotAvailable());
        data.put("assetWarnings", item.assetWarnings());
        data.put("tableId", item.tableId());
        data.put("figureId", item.figureId());
        data.put("formulaId", item.formulaId());
        data.put("evidenceRole", item.evidenceRole());
        data.put("tableText", item.tableText());
        data.put("tableMarkdown", item.tableMarkdown());
        data.put("tableScreenshotAvailable", item.tableScreenshotAvailable());
        return data;
    }

    private Map<String, Object> diagnosticsMap(LedgerDiagnostics diagnostics) {
        LedgerDiagnostics safeDiagnostics = diagnostics == null
                ? new LedgerDiagnostics(0, 0, 0, PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE.name())
                : diagnostics;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scannedCount", safeDiagnostics.scannedCount());
        data.put("acceptedEvidenceCount", safeDiagnostics.acceptedEvidenceCount());
        data.put("sourceCount", safeDiagnostics.sourceCount());
        data.put("stopReason", safeDiagnostics.stopReason());
        return data;
    }

    private SourceScope scopedSource(ToolContext context, List<String> paperIds) {
        if (paperIds == null || paperIds.isEmpty()) {
            return context.sourceScope();
        }
        return SourceScope.manual(paperIds, context.retrievalBudgetProfile());
    }

    private Set<String> resolvePaperRefs(ToolContext context, List<String> paperRefs) {
        LinkedHashSet<String> paperIds = new LinkedHashSet<>();
        for (String ref : paperRefs == null ? List.<String>of() : paperRefs) {
            String paperId = lookupPaperId(context, ref);
            if (paperId != null) {
                paperIds.add(paperId);
            }
        }
        return paperIds;
    }

    private String paperRef(ToolContext context, String paperId) {
        if (paperId == null || paperId.isBlank()) {
            return "";
        }
        return paperRefState(context).refFor(paperId);
    }

    private String lookupPaperId(ToolContext context, String paperRef) {
        if (paperRef == null || paperRef.isBlank()) {
            return null;
        }
        return paperRefState(context).paperIdFor(paperRef.trim().toUpperCase(Locale.ROOT));
    }

    private String lookupPaperIdByOrdinal(ToolContext context, int ordinal) {
        if (ordinal < 1) {
            return null;
        }
        return paperRefState(context).paperIdAt(ordinal);
    }

    private PaperRefState paperRefState(ToolContext context) {
        String key = context.conversationId().isBlank()
                ? context.userId()
                : context.userId() + ":" + context.conversationId();
        return paperRefsByConversation.computeIfAbsent(key, ignored -> new PaperRefState());
    }

    private ToolExecutionResult result(String toolName,
                                       boolean success,
                                       Map<String, Object> data,
                                       ToolEffect effect) {
        return new ToolExecutionResult(toolName, success, writeJson(data), data, effect);
    }

    private ToolExecutionResult error(String toolName, String reason, Map<String, Object> metadata) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("error", reason == null || reason.isBlank() ? "tool_error" : reason);
        data.put("metadata", metadata == null ? Map.of() : metadata);
        return result(toolName == null ? "" : toolName, false, data, ToolEffect.ERROR);
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            return "{\"error\":\"json_serialization_failed\"}";
        }
    }

    private AgentToolRegistry.AgentTool answerWithoutProductStateTool() {
        return new AgentToolRegistry.AgentTool(
                "answer_without_product_state",
                "Use only when the user can be answered without reading current PaperLoom product state. The final answer must not include paper counts, titles, filenames, metadata, processing status, or evidence claims.",
                objectSchema(Map.of(
                        "reason", enumSchema("Why no product paper state is needed.", List.of(
                                "smalltalk",
                                "ui_help",
                                "unsupported_non_paper_request",
                                "needs_user_clarification_without_product_state"
                        )),
                        "answerDraft", stringSchema("Draft answer that contains no product paper facts.")
                ), List.of("reason", "answerDraft"))
        );
    }

    private AgentToolRegistry.AgentTool getSessionScopeTool() {
        return new AgentToolRegistry.AgentTool(
                "get_session_scope",
                "Return the current PaperLoom session scope and product/eval constraints. Does not return paper lists or chunks.",
                objectSchema(Map.of(), List.of())
        );
    }

    private AgentToolRegistry.AgentTool getLibraryStatusTool() {
        return new AgentToolRegistry.AgentTool(
                "get_library_status",
                "Return counts for accessible, searchable, parsing, indexing, and failed product papers in the current session scope. Does not read chunks.",
                objectSchema(Map.of(
                        "scope", enumSchema("Scope selector. Only current_session is allowed.", List.of("current_session"))
                ), List.of("scope"))
        );
    }

    private AgentToolRegistry.AgentTool listPapersTool() {
        return new AgentToolRegistry.AgentTool(
                "list_papers",
                "List paper-level metadata in the current session scope. Use for enumerating available/searchable/failed/parsing papers. Does not read chunks or create citations.",
                objectSchema(Map.of(
                        "scope", enumSchema("Scope selector. Only current_session is allowed.", List.of("current_session")),
                        "status", enumSchema("Paper processing/searchability status.", List.of("searchable", "parsing", "indexing", "failed", "all")),
                        "page", integerSchema("1-based page number."),
                        "pageSize", integerSchema("Page size, max 50."),
                        "sort", enumSchema("Sort order.", List.of("recent"))
                ), List.of("scope", "status"))
        );
    }

    private AgentToolRegistry.AgentTool resolvePapersTool() {
        return new AgentToolRegistry.AgentTool(
                "resolve_papers",
                "Resolve a user paper selection such as a paperRef, title, filename, or prior ordinal within the current session scope. Does not read chunks.",
                objectSchema(Map.of(
                        "scope", enumSchema("Scope selector. Only current_session is allowed.", List.of("current_session")),
                        "selection", stringSchema("The user's paper selection text."),
                        "basis", enumSchema("Resolution basis.", List.of("conversation_history", "current_session", "latest_tool_result")),
                        "paperRefs", arrayStringSchema("Optional paperRef values to resolve."),
                        "maxResults", integerSchema("Maximum candidates to return.")
                ), List.of("scope", "selection"))
        );
    }

    private AgentToolRegistry.AgentTool getPaperMetadataTool() {
        return new AgentToolRegistry.AgentTool(
                "get_paper_metadata",
                "Return bibliographic and processing metadata for resolved papers. Does not read chunks or create citations.",
                objectSchema(Map.of(
                        "paperRefs", arrayStringSchema("Resolved paperRef values."),
                        "fields", arrayStringSchema("Requested metadata fields.")
                ), List.of("paperRefs"))
        );
    }

    private AgentToolRegistry.AgentTool discoverPapersTool() {
        return new AgentToolRegistry.AgentTool(
                "discover_papers",
                "Find relevant papers by topic using product paper-level metadata/search. This is not content QA and does not create evidence citations.",
                objectSchema(Map.of(
                        "query", stringSchema("Paper discovery topic."),
                        "scope", enumSchema("Scope selector. Only current_session is allowed.", List.of("current_session")),
                        "limit", integerSchema("Maximum papers to return.")
                ), List.of("query", "scope"))
        );
    }

    private AgentToolRegistry.AgentTool retrieveEvidenceTool() {
        return new AgentToolRegistry.AgentTool(
                "retrieve_evidence",
                "Retrieve citeable paper evidence for content questions. Final answers may cite only returned evidence.",
                objectSchema(Map.of(
                        "query", stringSchema("Evidence retrieval query."),
                        "scope", enumSchema("Scope selector. Only current_session is allowed.", List.of("current_session")),
                        "paperRefs", arrayStringSchema("Optional resolved paperRef filters."),
                        "evidenceTypes", arrayStringSchema("Desired evidence types such as TEXT, TABLE, FIGURE, FORMULA."),
                        "budgetProfile", enumSchema("Retrieval budget profile.", List.of("interactive", "high_recall", "deep_audit")),
                        "limit", integerSchema("Maximum evidence count.")
                ), List.of("query", "scope"))
        );
    }

    private AgentToolRegistry.AgentTool inspectReferenceTool() {
        return new AgentToolRegistry.AgentTool(
                "inspect_reference",
                "Resolve an existing citation/reference from a previous assistant answer. Does not perform unrelated retrieval.",
                objectSchema(Map.of(
                        "referenceNumber", integerSchema("User-visible citation number."),
                        "turn", enumSchema("Reference source turn.", List.of("latest_assistant"))
                ), List.of("referenceNumber"))
        );
    }

    private AgentToolRegistry.AgentTool inspectPageTool() {
        return new AgentToolRegistry.AgentTool(
                "inspect_page",
                "Inspect a known page or page window in a resolved paper. Requires paperRef and pageNumber.",
                objectSchema(Map.of(
                        "paperRef", stringSchema("Resolved paperRef."),
                        "pageNumber", integerSchema("1-based page number."),
                        "windowRadius", integerSchema("Number of nearby pages to include."),
                        "includeVisualAssets", booleanSchema("Whether visual assets are useful.")
                ), List.of("paperRef", "pageNumber"))
        );
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> stringSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> integerSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> booleanSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "boolean");
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> arrayStringSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("items", Map.of("type", "string"));
        return schema;
    }

    private Map<String, Object> enumSchema(String description, List<String> values) {
        Map<String, Object> schema = stringSchema(description);
        schema.put("enum", values);
        return schema;
    }

    private String displayTitle(Paper paper) {
        if (paper == null) {
            return "";
        }
        if (paper.getPaperTitle() != null && !paper.getPaperTitle().isBlank()) {
            return paper.getPaperTitle();
        }
        return paper.getOriginalFilename() == null ? paper.getPaperId() : paper.getOriginalFilename();
    }

    private String stringArg(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? defaultValue : text;
    }

    private int intArg(Map<String, Object> args, String key, int defaultValue, int min, int max) {
        Integer value = nullableInt(args.get(key));
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }

    private Integer nullableInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private List<String> stringListArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> item == null ? "" : String.valueOf(item).trim())
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private Integer ordinal(String selection) {
        String text = selection == null ? "" : selection.trim();
        if (text.isBlank()) {
            return null;
        }
        String digits = text.replaceAll("[^0-9]", "");
        if (!digits.isBlank()) {
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return chineseOrdinal(text);
    }

    private Integer chineseOrdinal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String numerals = text.replaceAll("[^一二两三四五六七八九十]", "");
        if (numerals.isBlank()) {
            return null;
        }
        if ("十".equals(numerals)) {
            return 10;
        }
        int value = 0;
        int tenIndex = numerals.indexOf('十');
        if (tenIndex >= 0) {
            int tens = tenIndex == 0 ? 1 : chineseDigit(numerals.charAt(tenIndex - 1));
            int ones = tenIndex == numerals.length() - 1 ? 0 : chineseDigit(numerals.charAt(tenIndex + 1));
            value = tens * 10 + ones;
        } else if (numerals.length() == 1) {
            value = chineseDigit(numerals.charAt(0));
        }
        return value <= 0 ? null : value;
    }

    private int chineseDigit(char value) {
        return switch (value) {
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> 0;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record ToolContext(
            String userId,
            String conversationId,
            SourceScope sourceScope,
            RetrievalBudgetProfile retrievalBudgetProfile
    ) {
        public ToolContext {
            userId = userId == null ? "" : userId.trim();
            conversationId = conversationId == null ? "" : conversationId.trim();
            sourceScope = sourceScope == null ? SourceScope.auto() : sourceScope;
            retrievalBudgetProfile = retrievalBudgetProfile == null
                    ? RetrievalBudgetProfile.INTERACTIVE
                    : retrievalBudgetProfile;
        }
    }

    public record ToolExecutionResult(
            String toolName,
            boolean success,
            String content,
            Map<String, Object> data,
            ToolEffect effect
    ) {
        public ToolExecutionResult {
            toolName = toolName == null ? "" : toolName;
            content = content == null ? "" : content;
            data = data == null ? Map.of() : data;
            effect = effect == null ? ToolEffect.NONE : effect;
        }
    }

    public enum ToolEffect {
        SESSION_SCOPE,
        LIBRARY_STATUS,
        PAPER_LIST,
        PAPER_RESOLUTION,
        PAPER_METADATA,
        PAPER_DISCOVERY,
        EVIDENCE,
        REFERENCE,
        PAGE,
        NO_PRODUCT_STATE,
        ERROR,
        NONE
    }

    private static final class PaperRefState {
        private final LinkedHashMap<String, String> paperIdByRef = new LinkedHashMap<>();
        private final LinkedHashMap<String, String> refByPaperId = new LinkedHashMap<>();

        private synchronized String refFor(String paperId) {
            String existing = refByPaperId.get(paperId);
            if (existing != null) {
                return existing;
            }
            String ref = "P" + (paperIdByRef.size() + 1);
            paperIdByRef.put(ref, paperId);
            refByPaperId.put(paperId, ref);
            return ref;
        }

        private synchronized String paperIdFor(String paperRef) {
            return paperIdByRef.get(paperRef);
        }

        private synchronized String paperIdAt(int ordinal) {
            if (ordinal < 1 || ordinal > paperIdByRef.size()) {
                return null;
            }
            return new ArrayList<>(paperIdByRef.values()).get(ordinal - 1);
        }
    }
}
