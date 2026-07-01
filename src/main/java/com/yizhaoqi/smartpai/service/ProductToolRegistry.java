package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperConversationReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class ProductToolRegistry {

    private static final Set<String> FORBIDDEN_ARGUMENTS = Set.of(
            "paperId",
            "paperIds",
            "chunkId",
            "chunkIds",
            "sql",
            "sqlQuery",
            "esQuery",
            "topK",
            "searchMode",
            "rerank",
            "rerankEnabled",
            "pageWindow",
            "pageWindowK",
            "windowRadius",
            "includeVisualAssets",
            "budgetProfile",
            "retrievalBudgetProfile",
            "evidenceTypes",
            "resolvedPaperId",
            "resolvedPaperIds"
    );
    private static final Set<String> RAW_IDENTIFIER_FIELDS = Set.of(
            "paperId",
            "paperIds",
            "chunkId",
            "chunkIds",
            "sourceEntityId",
            "resolvedPaperId",
            "resolvedPaperIds",
            "sqlId",
            "esId",
            "vectorId"
    );
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_RESOLVE_LIMIT = 10;
    private static final int MAX_RESOLVE_LIMIT = 30;

    private final ObjectMapper objectMapper;
    private final PaperLibraryStatusService paperLibraryStatusService;
    private final ConversationScopeService conversationScopeService;
    private final PaperCollectionService paperCollectionService;
    private final PaperService paperService;
    private final PaperSearchabilityService paperSearchabilityService;
    private final PaperRetrievalService paperRetrievalService;
    private final EvidenceToolExecutor evidenceToolExecutor;
    private final ConversationReferenceRegistry referenceRegistry;
    private final List<AgentToolRegistry.AgentTool> tools;

    public ProductToolRegistry(ObjectMapper objectMapper) {
        this(objectMapper, null, null, null, null, null, null, null);
    }

    public ProductToolRegistry(ObjectMapper objectMapper,
                               PaperLibraryStatusService paperLibraryStatusService,
                               ConversationScopeService conversationScopeService,
                               PaperCollectionService paperCollectionService) {
        this(objectMapper, paperLibraryStatusService, conversationScopeService, paperCollectionService, null, null, null, null, null);
    }

    public ProductToolRegistry(ObjectMapper objectMapper,
                               PaperLibraryStatusService paperLibraryStatusService,
                               ConversationScopeService conversationScopeService,
                               PaperCollectionService paperCollectionService,
                               ConversationReferenceRegistry referenceRegistry) {
        this(objectMapper, paperLibraryStatusService, conversationScopeService, paperCollectionService, null, null,
                null, null, referenceRegistry);
    }

    public ProductToolRegistry(ObjectMapper objectMapper,
                               PaperLibraryStatusService paperLibraryStatusService,
                               ConversationScopeService conversationScopeService,
                               PaperCollectionService paperCollectionService,
                               PaperService paperService,
                               PaperSearchabilityService paperSearchabilityService,
                               ConversationReferenceRegistry referenceRegistry) {
        this(objectMapper, paperLibraryStatusService, conversationScopeService, paperCollectionService,
                paperService, paperSearchabilityService, null, null, referenceRegistry);
    }

    public ProductToolRegistry(ObjectMapper objectMapper,
                               PaperLibraryStatusService paperLibraryStatusService,
                               ConversationScopeService conversationScopeService,
                               PaperCollectionService paperCollectionService,
                               PaperService paperService,
                               PaperSearchabilityService paperSearchabilityService,
                               EvidenceToolExecutor evidenceToolExecutor,
                               ConversationReferenceRegistry referenceRegistry) {
        this(objectMapper, paperLibraryStatusService, conversationScopeService, paperCollectionService,
                paperService, paperSearchabilityService, null, evidenceToolExecutor, referenceRegistry);
    }

    @Autowired
    public ProductToolRegistry(ObjectMapper objectMapper,
                               PaperLibraryStatusService paperLibraryStatusService,
                               ConversationScopeService conversationScopeService,
                               PaperCollectionService paperCollectionService,
                               PaperService paperService,
                               PaperSearchabilityService paperSearchabilityService,
                               PaperRetrievalService paperRetrievalService,
                               EvidenceToolExecutor evidenceToolExecutor,
                               ConversationReferenceRegistry referenceRegistry) {
        this.objectMapper = objectMapper;
        this.paperLibraryStatusService = paperLibraryStatusService;
        this.conversationScopeService = conversationScopeService;
        this.paperCollectionService = paperCollectionService;
        this.paperService = paperService;
        this.paperSearchabilityService = paperSearchabilityService;
        this.paperRetrievalService = paperRetrievalService;
        this.evidenceToolExecutor = evidenceToolExecutor;
        this.referenceRegistry = referenceRegistry;
        this.tools = List.of(
                answerWithoutProductStateTool(),
                getSystemStateTool(),
                getSessionScopeTool(),
                listPapersTool(),
                findPapersTool(),
                resolvePapersTool(),
                getPaperMetadataTool(),
                retrieveEvidenceTool(),
                inspectReferenceTool(),
                inspectPageTool()
        );
    }

    public List<AgentToolRegistry.AgentTool> listTools() {
        return tools;
    }

    public ProductToolResult execute(String toolName, Map<String, Object> arguments, ProductToolContext context) {
        ProductToolContext safeContext = context == null
                ? new ProductToolContext(null, "", "", SourceScope.auto())
                : context;
        Map<String, Object> safeArgs = arguments == null ? Map.of() : arguments;
        String forbiddenArgument = firstForbiddenArgument(safeArgs);
        if (forbiddenArgument != null) {
            return new ProductToolResult(
                    toolName,
                    false,
                    Map.of("error", "forbidden_product_tool_argument", "argument", forbiddenArgument),
                    ProductToolEffect.ERROR
            );
        }
        return switch (toolName == null ? "" : toolName) {
            case "answer_without_product_state" -> answerWithoutProductState(safeArgs);
            case "get_system_state" -> getSystemState(safeContext);
            case "get_session_scope" -> getSessionScope(safeContext);
            case "list_papers" -> listPapers(safeArgs, safeContext);
            case "find_papers" -> findPapers(safeArgs, safeContext);
            case "resolve_papers" -> resolvePapers(safeArgs, safeContext);
            case "get_paper_metadata" -> getPaperMetadata(safeArgs, safeContext);
            case "inspect_reference" -> inspectReference(safeArgs, safeContext);
            case "inspect_page" -> inspectPage(safeArgs, safeContext);
            case "retrieve_evidence" -> retrieveEvidence(safeArgs, safeContext);
            default -> error(toolName, "unsupported_product_tool");
        };
    }

    private ProductToolResult answerWithoutProductState(Map<String, Object> args) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("allowed", true);
        data.put("reason", stringValue(args.get("reason")));
        data.put("answerDraft", stringValue(args.get("answerDraft")));
        data.put("constraints", Map.of(
                "mayIncludePaperCounts", false,
                "mayIncludePaperTitles", false,
                "mayIncludeFilenames", false,
                "mayIncludeProcessingStatus", false,
                "mayIncludeReferences", false,
                "mayIncludePages", false,
                "mayIncludeCitations", false,
                "mayIncludeEvidenceClaims", false
        ));
        return new ProductToolResult("answer_without_product_state", true, data, ProductToolEffect.NO_PRODUCT_STATE);
    }

    private ProductToolResult getSessionScope(ProductToolContext context) {
        SourceScope lockedScope = context.lockedScope() == null ? SourceScope.auto() : context.lockedScope();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scopeMode", lockedScope.mode().name());
        data.put("scopeLocked", true);
        data.put("scopeStatus", "READY");
        data.put("sourceLabel", lockedScope.paperIds().isEmpty() ? "All searchable papers" : "Selected papers");
        if (lockedScope.paperIds().isEmpty()) {
            data.put("sourcePaperCountKnown", false);
        } else {
            data.put("sourcePaperCountKnown", true);
            data.put("sourcePaperCount", lockedScope.paperIds().size());
        }
        data.put("constraints", Map.of(
                "immutable", true,
                "productOnly", true,
                "evalExcluded", true,
                "searchableOnlyForRetrieval", true,
                "llmMayChangeScope", false
        ));
        return new ProductToolResult("get_session_scope", true, data, ProductToolEffect.SESSION_SCOPE);
    }

    private ProductToolResult getPaperMetadata(Map<String, Object> args, ProductToolContext context) {
        if (referenceRegistry == null) {
            return error("get_paper_metadata", "reference_registry_unavailable");
        }
        List<String> paperRefs = stringListValue(args.get("paperRefs"));
        if (paperRefs.isEmpty()) {
            return new ProductToolResult(
                    "get_paper_metadata",
                    true,
                    Map.of("total", 0, "papers", List.of(), "missingPaperRefs", List.of()),
                    ProductToolEffect.PAPER_METADATA
            );
        }
        List<Map<String, Object>> papers = new ArrayList<>();
        List<String> missingRefs = new ArrayList<>();
        for (String paperRef : paperRefs) {
            Optional<ConversationReferenceRegistry.ResolvedReference> resolved =
                    referenceRegistry.resolve(
                            context.conversationId(),
                            scopeSnapshotId(context.lockedScope()),
                            paperRef,
                            PaperConversationReference.RefType.PAPER
                    );
            if (resolved.isEmpty()) {
                missingRefs.add(paperRef);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata =
                    (Map<String, Object>) sanitizeLlmVisibleValue(resolved.get().displayPayload());
            papers.add(metadata);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", papers.size());
        data.put("papers", papers);
        data.put("missingPaperRefs", missingRefs);
        return new ProductToolResult("get_paper_metadata", true, data, ProductToolEffect.PAPER_METADATA);
    }

    private ProductToolResult getSystemState(ProductToolContext context) {
        if (paperLibraryStatusService == null) {
            return new ProductToolResult(
                    "get_system_state",
                    false,
                    Map.of("error", "paper_library_status_service_unavailable"),
                    ProductToolEffect.ERROR
            );
        }
        PaperLibraryStatus status = paperLibraryStatusService.statusFor(userId(context.userId()), context.lockedScope());
        int processingCount = status.parsingCount() + status.indexingCount();
        Map<String, Object> byStatus = new LinkedHashMap<>();
        byStatus.put("AVAILABLE", status.searchableCount());
        byStatus.put("PROCESSING", processingCount);
        byStatus.put("FAILED", status.failedCount());
        byStatus.put("NOT_IN_SCOPE", 0);
        byStatus.put("NOT_VISIBLE", 0);

        Map<String, Object> sessionScope = new LinkedHashMap<>();
        sessionScope.put("scopeMode", context.lockedScope().mode().name());
        sessionScope.put("sourcePaperCount", context.lockedScope().paperIds().isEmpty()
                ? null
                : context.lockedScope().paperIds().size());
        sessionScope.put("immutable", true);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productPaperCount", status.accessibleCount());
        data.put("searchablePaperCount", status.selectedScopeCount());
        data.put("papersByProcessingStatus", byStatus);
        data.put("indexingPendingCount", processingCount);
        data.put("currentSessionScope", sessionScope);
        data.put("visibleCollectionCount", visibleCollectionCount(context.userId()));
        data.put("warnings", status.consistencyWarnings());
        return new ProductToolResult("get_system_state", true, data, ProductToolEffect.PRODUCT_STATE);
    }

    private ProductToolResult listPapers(Map<String, Object> args, ProductToolContext context) {
        ProductToolResult serviceError = paperListingServiceError("list_papers");
        if (serviceError != null) {
            return serviceError;
        }
        String statusFilter = productStatus(args.get("status"), "AVAILABLE");
        int page = boundedInteger(args.get("page"), DEFAULT_PAGE, 1, Integer.MAX_VALUE);
        int pageSize = boundedInteger(args.get("pageSize"), DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);

        FilteredPapers filtered = filteredPapers(args, context, statusFilter);
        if (filtered.invalidRegex()) {
            return new ProductToolResult(
                    "list_papers",
                    true,
                    Map.of(
                            "total", 0,
                            "page", page,
                            "pageSize", pageSize,
                            "papers", List.of(),
                            "errors", List.of("invalid_title_regex")
                    ),
                    ProductToolEffect.PAPER_LIST
            );
        }
        List<Paper> sorted = sortPapers(filtered.papers(), stringValue(args.get("sort")));
        int fromIndex = Math.min(sorted.size(), (page - 1) * pageSize);
        int toIndex = Math.min(sorted.size(), fromIndex + pageSize);
        List<Map<String, Object>> papers = sorted.subList(fromIndex, toIndex).stream()
                .map(paper -> paperMetadata(paper, context))
                .toList();
        try {
            persistPaperRefs(Map.of("papers", papers), context);
        } catch (Exception exception) {
            return error("list_papers", "reference_registry_persistence_failed");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", sorted.size());
        data.put("filteredTotal", sorted.size());
        data.put("scopePaperCount", statusScopedPaperCount(context, statusFilter));
        data.put("filtered", hasPaperListFilter(args));
        data.put("page", page);
        data.put("pageSize", pageSize);
        data.put("papers", papers);
        data.put("scope", Map.of(
                "scopeMode", context.lockedScope().mode().name(),
                "immutable", true
        ));
        return new ProductToolResult(
                "list_papers",
                true,
                sanitizeProductToolData(data),
                ProductToolEffect.PAPER_LIST
        );
    }

    private ProductToolResult findPapers(Map<String, Object> args, ProductToolContext context) {
        if (paperRetrievalService == null) {
            return error("find_papers", "paper_retrieval_service_unavailable");
        }
        if (referenceRegistry == null) {
            return error("find_papers", "reference_registry_unavailable");
        }
        String query = firstNonBlank(stringValue(args.get("query")),
                firstNonBlank(stringValue(args.get("topic")), stringValue(args.get("semanticQuery"))));
        if (query.isBlank()) {
            return error("find_papers", "missing_query");
        }
        int limit = boundedInteger(args.get("limit"), DEFAULT_RESOLVE_LIMIT, 1, MAX_RESOLVE_LIMIT);
        PaperRetrievalService.RetrievalResult retrieval = paperRetrievalService.discoverPapers(
                query,
                userId(context.userId()),
                RetrievalBudget.forLibrarySearch(),
                context.lockedScope().paperIds()
        );
        List<SearchResult> uniqueResults = uniquePaperResults(retrieval == null ? List.of() : retrieval.results());
        List<Map<String, Object>> papers = uniqueResults.stream()
                .limit(limit)
                .map(result -> paperSearchResultMetadata(result, context))
                .toList();
        try {
            persistPaperRefs(Map.of("papers", papers), context);
        } catch (Exception exception) {
            return error("find_papers", "reference_registry_persistence_failed");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("total", uniqueResults.size());
        data.put("returned", papers.size());
        data.put("papers", papers);
        data.put("selectionBasis", "semantic_paper_search");
        data.put("citationsAvailable", false);
        data.put("requiresEvidenceToolForPaperClaims", true);
        data.put("scope", Map.of(
                "scopeMode", context.lockedScope().mode().name(),
                "immutable", true
        ));
        data.put("diagnostics", retrieval == null ? Map.of() : diagnosticsMap(retrieval.diagnostics()));
        return new ProductToolResult(
                "find_papers",
                true,
                sanitizeProductToolData(data),
                ProductToolEffect.PAPER_DISCOVERY
        );
    }

    private ProductToolResult resolvePapers(Map<String, Object> args, ProductToolContext context) {
        ProductToolResult serviceError = paperListingServiceError("resolve_papers");
        if (serviceError != null) {
            return serviceError;
        }
        if (referenceRegistry == null) {
            return error("resolve_papers", "reference_registry_unavailable");
        }

        List<Paper> accessibleInScope = accessiblePapers(context);
        Map<String, Paper> papersById = new LinkedHashMap<>();
        for (Paper paper : accessibleInScope) {
            if (paper != null && !stringValue(paper.getPaperId()).isBlank()) {
                papersById.put(paper.getPaperId(), paper);
            }
        }

        LinkedHashMap<String, Paper> resolved = new LinkedHashMap<>();
        List<String> missingPaperRefs = new ArrayList<>();
        for (String paperRef : stringListValue(args.get("paperRefs"))) {
            Optional<ConversationReferenceRegistry.ResolvedReference> reference =
                    resolveReference(paperRef, context, PaperConversationReference.RefType.PAPER);
            if (reference.isEmpty()) {
                missingPaperRefs.add(paperRef);
                continue;
            }
            String paperId = paperIdFromReference(reference.get());
            Paper paper = papersById.get(paperId);
            if (paper == null) {
                missingPaperRefs.add(paperRef);
                continue;
            }
            resolved.put(paper.getPaperId(), paper);
        }

        List<String> missingCitationRefs = new ArrayList<>();
        for (String citationRef : stringListValue(args.get("citationRefs"))) {
            Optional<ConversationReferenceRegistry.ResolvedReference> reference =
                    resolveReference(citationRef, context, PaperConversationReference.RefType.CITATION);
            if (reference.isEmpty()) {
                missingCitationRefs.add(citationRef);
                continue;
            }
            String paperId = paperIdFromCitationReference(reference.get());
            if (paperId.isBlank()) {
                String paperRef = firstNonBlank(
                        stringValue(reference.get().sourcePayload().get("paperRef")),
                        stringValue(reference.get().displayPayload().get("paperRef"))
                );
                if (!paperRef.isBlank()) {
                    Optional<ConversationReferenceRegistry.ResolvedReference> paperReference =
                            resolveReference(paperRef, context, PaperConversationReference.RefType.PAPER);
                    if (paperReference.isPresent()) {
                        paperId = paperIdFromReference(paperReference.get());
                    }
                }
            }
            Paper paper = papersById.get(paperId);
            if (paper == null) {
                missingCitationRefs.add(citationRef);
                continue;
            }
            resolved.put(paper.getPaperId(), paper);
        }

        boolean selectorUsed = hasSelectionCriteria(args);
        boolean invalidRegex = false;
        if (selectorUsed) {
            FilteredPapers selected = filteredPapers(args, context, "AVAILABLE");
            invalidRegex = selected.invalidRegex();
            if (!invalidRegex) {
                int ordinal = boundedInteger(args.get("ordinal"), 0, 0, Integer.MAX_VALUE);
                List<Paper> selectedPapers = sortPapers(selected.papers(), stringValue(args.get("sort")));
                if (ordinal > 0) {
                    if (ordinal <= selectedPapers.size()) {
                        Paper paper = selectedPapers.get(ordinal - 1);
                        resolved.put(paper.getPaperId(), paper);
                    }
                } else {
                    selectedPapers.forEach(paper -> resolved.put(paper.getPaperId(), paper));
                }
            }
        }

        int limit = boundedInteger(args.get("limit"), DEFAULT_RESOLVE_LIMIT, 1, MAX_RESOLVE_LIMIT);
        List<Map<String, Object>> papers = resolved.values().stream()
                .limit(limit)
                .map(paper -> paperMetadata(paper, context))
                .toList();
        try {
            persistPaperRefs(Map.of("papers", papers), context);
        } catch (Exception exception) {
            return error("resolve_papers", "reference_registry_persistence_failed");
        }

        boolean ambiguous = selectorUsed && resolved.size() > 1;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resolved", !papers.isEmpty() && !ambiguous);
        data.put("ambiguous", ambiguous);
        data.put("total", resolved.size());
        data.put("returned", papers.size());
        data.put("papers", papers);
        data.put("candidates", ambiguous ? papers : List.of());
        data.put("missingPaperRefs", missingPaperRefs);
        data.put("missingCitationRefs", missingCitationRefs);
        if (invalidRegex) {
            data.put("reason", "invalid_title_regex");
        } else if (resolved.isEmpty()) {
            data.put("reason", "no_matching_papers");
        }
        return new ProductToolResult(
                "resolve_papers",
                true,
                sanitizeProductToolData(data),
                ProductToolEffect.PAPER_RESOLUTION
        );
    }

    private ProductToolResult retrieveEvidence(Map<String, Object> args, ProductToolContext context) {
        if (evidenceToolExecutor == null) {
            return error("retrieve_evidence", "evidence_tool_executor_unavailable");
        }
        if (referenceRegistry == null) {
            return error("retrieve_evidence", "reference_registry_unavailable");
        }
        String query = evidenceQuery(args);
        if (query.isBlank()) {
            return error("retrieve_evidence", "missing_question");
        }

        LinkedHashSet<String> constrainedPaperIds = new LinkedHashSet<>();
        List<String> missingPaperRefs = new ArrayList<>();
        for (String paperRef : paperRefsFromConstraints(args.get("paperConstraints"))) {
            Optional<ConversationReferenceRegistry.ResolvedReference> resolved =
                    resolveReference(paperRef, context, PaperConversationReference.RefType.PAPER);
            if (resolved.isEmpty()) {
                missingPaperRefs.add(paperRef);
                continue;
            }
            String paperId = paperIdFromReference(resolved.get());
            if (paperId.isBlank()) {
                missingPaperRefs.add(paperRef);
                continue;
            }
            constrainedPaperIds.add(paperId);
        }
        if (!missingPaperRefs.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sourceCount", 0);
            data.put("papers", List.of());
            data.put("evidence", List.of());
            data.put("missingPaperRefs", missingPaperRefs);
            data.put("reason", "unresolved_paper_constraints");
            return new ProductToolResult(
                    "retrieve_evidence",
                    true,
                    data,
                    ProductToolEffect.EVIDENCE
            );
        }

        if (hasTitleSelection(args)) {
            ProductToolResult serviceError = paperListingServiceError("retrieve_evidence");
            if (serviceError != null) {
                return serviceError;
            }
            FilteredPapers selected = filteredPapers(args, context, "AVAILABLE");
            if (selected.invalidRegex()) {
                return new ProductToolResult(
                        "retrieve_evidence",
                        true,
                        Map.of(
                                "sourceCount", 0,
                                "papers", List.of(),
                                "evidence", List.of(),
                                "reason", "invalid_title_regex"
                        ),
                        ProductToolEffect.EVIDENCE
                );
            }
            selected.papers().stream()
                    .map(Paper::getPaperId)
                    .filter(paperId -> paperId != null && !paperId.isBlank())
                    .forEach(constrainedPaperIds::add);
        }

        List<String> paperIds = new ArrayList<>(constrainedPaperIds);
        PlannerAction action = new PlannerAction(
                PlannerActionType.SEARCH_EVIDENCE,
                query,
                "product_react_tool",
                paperIds,
                null
        );
        SourceScope retrievalScope = scopedSource(context, paperIds);
        EvidenceToolResult toolResult = evidenceToolExecutor.execute(
                userId(context.userId()),
                context.conversationId(),
                action,
                retrievalScope
        );
        Map<String, Object> rawData = withPageRefs(ledgerData(toolResult.ledger(), context));
        if (toolResult.message() != null && !toolResult.message().isBlank()) {
            rawData.put("message", toolResult.message());
        }
        try {
            persistPaperRefs(rawData, context);
            persistPageRefs(rawData, context);
        } catch (Exception exception) {
            return error("retrieve_evidence", "reference_registry_persistence_failed");
        }
        return new ProductToolResult(
                "retrieve_evidence",
                true,
                sanitizeProductToolData(rawData),
                ProductToolEffect.EVIDENCE,
                hiddenEvidencePayloads(rawData)
        );
    }

    private ProductToolResult inspectReference(Map<String, Object> args, ProductToolContext context) {
        if (referenceRegistry == null) {
            return error("inspect_reference", "reference_registry_unavailable");
        }
        String citationRef = stringValue(args.get("citationRef"));
        String evidenceRef = stringValue(args.get("evidenceRef"));
        if (citationRef.isBlank() && evidenceRef.isBlank()) {
            return new ProductToolResult(
                    "inspect_reference",
                    true,
                    Map.of("found", false, "reason", "missing_reference_id"),
                    ProductToolEffect.REFERENCE
            );
        }
        String refId = citationRef.isBlank() ? evidenceRef : citationRef;
        PaperConversationReference.RefType refType = citationRef.isBlank()
                ? PaperConversationReference.RefType.EVIDENCE
                : PaperConversationReference.RefType.CITATION;
        Optional<ConversationReferenceRegistry.ResolvedReference> resolved =
                referenceRegistry.resolve(
                        context.conversationId(),
                        scopeSnapshotId(context.lockedScope()),
                        refId,
                        refType
                );
        if (resolved.isEmpty()) {
            return new ProductToolResult(
                    "inspect_reference",
                    true,
                    Map.of("found", false, "refId", refId, "reason", "reference_not_found"),
                    ProductToolEffect.REFERENCE
            );
        }
        ConversationReferenceRegistry.ResolvedReference reference = resolved.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("found", true);
        data.put("refId", reference.refId());
        data.put("refType", reference.refType().name());
        data.put("turnId", reference.turnId());
        data.put("reference", sanitizeLlmVisibleValue(reference.displayPayload()));
        return new ProductToolResult("inspect_reference", true, data, ProductToolEffect.REFERENCE);
    }

    private ProductToolResult inspectPage(Map<String, Object> args, ProductToolContext context) {
        String pageRef = stringValue(args.get("pageRef"));
        if (pageRef.isBlank()) {
            return inspectPageByPaperRef(args, context);
        }
        if (referenceRegistry == null) {
            return error("inspect_page", "reference_registry_unavailable");
        }
        Optional<ConversationReferenceRegistry.ResolvedReference> resolved =
                referenceRegistry.resolve(
                        context.conversationId(),
                        scopeSnapshotId(context.lockedScope()),
                        pageRef,
                        PaperConversationReference.RefType.PAGE
                );
        if (resolved.isEmpty()) {
            return new ProductToolResult(
                    "inspect_page",
                    true,
                    Map.of("found", false, "pageRef", pageRef, "reason", "reference_not_found"),
                    ProductToolEffect.PAGE
            );
        }
        ConversationReferenceRegistry.ResolvedReference reference = resolved.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("found", true);
        data.put("pageRef", reference.refId());
        data.put("refType", reference.refType().name());
        data.put("turnId", reference.turnId());
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) sanitizeLlmVisibleValue(reference.displayPayload());
        data.put("page", page);
        String evidenceRef = stringValue(page.get("evidenceRef"));
        if (evidenceRef.isBlank()) {
            evidenceRef = stringValue(reference.sourcePayload().get("evidenceRef"));
        }
        if (evidenceRef.isBlank()) {
            return new ProductToolResult("inspect_page", true, data, ProductToolEffect.PAGE);
        }
        data.put("evidence", List.of(page));
        Map<String, Object> hiddenPayload = new LinkedHashMap<>(reference.sourcePayload());
        hiddenPayload.putIfAbsent("evidenceRef", evidenceRef);
        return new ProductToolResult(
                "inspect_page",
                true,
                data,
                ProductToolEffect.PAGE,
                Map.of(evidenceRef, hiddenPayload)
        );
    }

    private ProductToolResult inspectPageByPaperRef(Map<String, Object> args, ProductToolContext context) {
        if (referenceRegistry == null) {
            return error("inspect_page", "reference_registry_unavailable");
        }
        String paperRef = stringValue(args.get("paperRef"));
        Integer pageNumber = integerValue(args.get("pageNumber"));
        if (paperRef.isBlank() || pageNumber == null) {
            return new ProductToolResult(
                    "inspect_page",
                    true,
                    Map.of("found", false, "reason", "missing_page_scope"),
                    ProductToolEffect.PAGE
            );
        }
        Optional<ConversationReferenceRegistry.ResolvedReference> resolved =
                referenceRegistry.resolve(
                        context.conversationId(),
                        scopeSnapshotId(context.lockedScope()),
                        paperRef,
                        PaperConversationReference.RefType.PAPER
                );
        if (resolved.isEmpty()) {
            return new ProductToolResult(
                    "inspect_page",
                    true,
                    Map.of("found", false, "paperRef", paperRef, "reason", "reference_not_found"),
                    ProductToolEffect.PAGE
            );
        }
        String paperId = firstNonBlank(
                resolved.get().sourceEntityId(),
                stringValue(resolved.get().sourcePayload().get("paperId"))
        );
        if (paperId.isBlank()) {
            return error("inspect_page", "reference_registry_payload_invalid");
        }
        if (evidenceToolExecutor == null) {
            return error("inspect_page", "evidence_tool_executor_unavailable");
        }
        PlannerAction action = new PlannerAction(
                PlannerActionType.INSPECT_PAGE,
                "",
                "product_react_tool",
                List.of(paperId),
                null,
                pageNumber,
                null
        );
        EvidenceToolResult toolResult = evidenceToolExecutor.execute(
                userId(context.userId()),
                context.conversationId(),
                action,
                SourceScope.manual(List.of(paperId), context.lockedScope().retrievalBudgetProfile())
        );
        Map<String, Object> rawData = withPageRefs(ledgerData(toolResult.ledger(), context));
        rawData.put("pageNumber", pageNumber);
        if (toolResult.message() != null && !toolResult.message().isBlank()) {
            rawData.put("message", toolResult.message());
        }
        try {
            persistPaperRefs(rawData, context);
            persistPageRefs(rawData, context);
        } catch (Exception exception) {
            return error("inspect_page", "reference_registry_persistence_failed");
        }
        return new ProductToolResult(
                "inspect_page",
                true,
                sanitizeProductToolData(rawData),
                ProductToolEffect.PAGE,
                hiddenEvidencePayloads(rawData)
        );
    }

    private Map<String, Object> sanitizeProductToolData(Map<String, Object> rawData) {
        Object sanitized = sanitizeLlmVisibleValue(rawData == null ? Map.of() : rawData);
        if (sanitized instanceof Map<?, ?> map) {
            Map<String, Object> data = new LinkedHashMap<>();
            map.forEach((key, value) -> data.put(String.valueOf(key), value));
            return data;
        }
        return Map.of();
    }

    private Object sanitizeLlmVisibleValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                String field = String.valueOf(key);
                if (!RAW_IDENTIFIER_FIELDS.contains(field)) {
                    sanitized.put(field, sanitizeLlmVisibleValue(nestedValue));
                }
            });
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::sanitizeLlmVisibleValue)
                    .toList();
        }
        return value;
    }

    private Map<String, Map<String, Object>> hiddenEvidencePayloads(Map<String, Object> rawData) {
        Object rawEvidence = rawData == null ? null : rawData.get("evidence");
        if (!(rawEvidence instanceof List<?> evidenceList)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> payloads = new LinkedHashMap<>();
        for (Object item : evidenceList) {
            if (item instanceof Map<?, ?> map) {
                String evidenceRef = stringValue(map.get("evidenceRef"));
                if (evidenceRef.isBlank()) {
                    continue;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                map.forEach((key, value) -> payload.put(String.valueOf(key), value));
                payloads.put(evidenceRef, payload);
            }
        }
        return payloads;
    }

    private Map<String, Object> withPageRefs(Map<String, Object> rawData) {
        Object copied = copyWithPageRefs(rawData == null ? Map.of() : rawData);
        if (copied instanceof Map<?, ?> map) {
            Map<String, Object> data = new LinkedHashMap<>();
            map.forEach((key, value) -> data.put(String.valueOf(key), value));
            return data;
        }
        return Map.of();
    }

    private Object copyWithPageRefs(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> copied.put(String.valueOf(key), copyWithPageRefs(nestedValue)));
            String pageRef = pageRefFor(copied);
            if (!pageRef.isBlank()) {
                copied.putIfAbsent("pageRef", pageRef);
            }
            return copied;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::copyWithPageRefs)
                    .toList();
        }
        return value;
    }

    private void persistPaperRefs(Map<String, Object> rawData, ProductToolContext context) {
        for (Map<String, Object> paperRefPayload : paperRefPayloads(rawData)) {
            String paperRef = stringValue(paperRefPayload.get("paperRef"));
            String paperId = stringValue(paperRefPayload.get("paperId"));
            if (paperRef.isBlank() || paperId.isBlank()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> displayPayload = (Map<String, Object>) sanitizeLlmVisibleValue(paperRefPayload);
            referenceRegistry.save(new ConversationReferenceRegistry.ReferenceInput(
                    context.conversationId(),
                    scopeSnapshotId(context.lockedScope()),
                    context.generationId(),
                    paperRef,
                    PaperConversationReference.RefType.PAPER,
                    paperId,
                    paperRefPayload,
                    displayPayload
            ));
        }
    }

    private void persistPageRefs(Map<String, Object> rawData, ProductToolContext context) {
        for (Map<String, Object> pageRefPayload : pageRefPayloads(rawData)) {
            String pageRef = stringValue(pageRefPayload.get("pageRef"));
            String paperId = stringValue(pageRefPayload.get("paperId"));
            Integer pageNumber = integerValue(pageRefPayload.get("pageNumber"));
            if (pageRef.isBlank() || paperId.isBlank() || pageNumber == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> displayPayload = (Map<String, Object>) sanitizeLlmVisibleValue(pageRefPayload);
            referenceRegistry.save(new ConversationReferenceRegistry.ReferenceInput(
                    context.conversationId(),
                    scopeSnapshotId(context.lockedScope()),
                    context.generationId(),
                    pageRef,
                    PaperConversationReference.RefType.PAGE,
                    paperId + ":page:" + pageNumber,
                    pageRefPayload,
                    displayPayload
            ));
        }
    }

    private List<Map<String, Object>> paperRefPayloads(Object rawData) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        collectPaperRefPayloads(rawData, payloads, new LinkedHashSet<>());
        return payloads;
    }

    private void collectPaperRefPayloads(Object value,
                                         List<Map<String, Object>> payloads,
                                         Set<String> seenRefs) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> normalized.put(String.valueOf(key), nestedValue));
            String paperRef = stringValue(normalized.get("paperRef"));
            String paperId = stringValue(normalized.get("paperId"));
            if (!paperRef.isBlank() && !paperId.isBlank() && seenRefs.add(paperRef)) {
                payloads.add(normalized);
            }
            normalized.values().forEach(nestedValue -> collectPaperRefPayloads(nestedValue, payloads, seenRefs));
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> collectPaperRefPayloads(item, payloads, seenRefs));
        }
    }

    private List<Map<String, Object>> pageRefPayloads(Object rawData) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        collectPageRefPayloads(rawData, payloads, new LinkedHashSet<>());
        return payloads;
    }

    private void collectPageRefPayloads(Object value,
                                        List<Map<String, Object>> payloads,
                                        Set<String> seenRefs) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> normalized.put(String.valueOf(key), nestedValue));
            String pageRef = stringValue(normalized.get("pageRef"));
            String paperId = stringValue(normalized.get("paperId"));
            Integer pageNumber = integerValue(normalized.get("pageNumber"));
            if (!pageRef.isBlank() && !paperId.isBlank() && pageNumber != null && seenRefs.add(pageRef)) {
                payloads.add(normalized);
            }
            normalized.values().forEach(nestedValue -> collectPageRefPayloads(nestedValue, payloads, seenRefs));
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> collectPageRefPayloads(item, payloads, seenRefs));
        }
    }

    private String pageRefFor(Map<String, Object> payload) {
        String existing = stringValue(payload.get("pageRef"));
        if (!existing.isBlank()) {
            return existing;
        }
        String paperRef = stringValue(payload.get("paperRef"));
        Integer pageNumber = integerValue(payload.get("pageNumber"));
        if (paperRef.isBlank() || pageNumber == null || pageNumber < 1) {
            return "";
        }
        return "page_" + paperRef.replaceAll("[^A-Za-z0-9_\\-]", "_") + "_" + pageNumber;
    }

    private List<String> paperRefsFromConstraints(Object rawConstraints) {
        if (!(rawConstraints instanceof List<?> constraints)) {
            return List.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (Object item : constraints) {
            if (item instanceof Map<?, ?> map) {
                String paperRef = stringValue(map.get("paperRef"));
                if (!paperRef.isBlank()) {
                    refs.add(paperRef);
                }
            }
        }
        return new ArrayList<>(refs);
    }

    private ProductToolResult error(String toolName, String reason) {
        return new ProductToolResult(
                toolName,
                false,
                Map.of("error", reason),
                ProductToolEffect.ERROR
        );
    }

    private String firstForbiddenArgument(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (FORBIDDEN_ARGUMENTS.contains(key)) {
                    return key;
                }
                String nestedForbiddenArgument = firstForbiddenArgument(entry.getValue());
                if (nestedForbiddenArgument != null) {
                    return nestedForbiddenArgument;
                }
            }
            return null;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String nestedForbiddenArgument = firstForbiddenArgument(item);
                if (nestedForbiddenArgument != null) {
                    return nestedForbiddenArgument;
                }
            }
        }
        return null;
    }

    private Integer integerValue(Object value) {
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<String> stringListValue(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> item == null ? "" : String.valueOf(item).trim())
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    private ProductToolResult paperListingServiceError(String toolName) {
        if (paperService == null || paperSearchabilityService == null) {
            return error(toolName, "product_paper_services_unavailable");
        }
        if (referenceRegistry == null) {
            return error(toolName, "reference_registry_unavailable");
        }
        return null;
    }

    private List<Paper> accessiblePapers(ProductToolContext context) {
        List<Paper> accessible = paperService.getAccessiblePapers(userId(context.userId()), null);
        List<Paper> safeAccessible = accessible == null ? List.of() : accessible;
        Set<String> requested = new LinkedHashSet<>(context.lockedScope().paperIds());
        return safeAccessible.stream()
                .filter(paper -> paper != null && !stringValue(paper.getPaperId()).isBlank())
                .filter(paper -> requested.isEmpty() || requested.contains(paper.getPaperId()))
                .toList();
    }

    private FilteredPapers filteredPapers(Map<String, Object> args,
                                          ProductToolContext context,
                                          String statusFilter) {
        Pattern titlePattern = null;
        String titleRegex = stringValue(args.get("titleRegex"));
        if (!titleRegex.isBlank()) {
            try {
                titlePattern = Pattern.compile(titleRegex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            } catch (PatternSyntaxException exception) {
                return new FilteredPapers(List.of(), true);
            }
        }
        Pattern finalTitlePattern = titlePattern;
        String titleQuery = firstNonBlank(stringValue(args.get("titleQuery")), stringValue(args.get("userMention")));
        String author = stringValue(args.get("author"));
        YearRange yearRange = yearRange(args.get("yearRange"));
        List<Paper> papers = accessiblePapers(context).stream()
                .filter(paper -> statusMatches(statusFilter, paper))
                .filter(paper -> titleQuery.isBlank() || textContains(displayTitle(paper), titleQuery)
                        || textContains(paper.getOriginalFilename(), titleQuery))
                .filter(paper -> finalTitlePattern == null || finalTitlePattern.matcher(displayTitle(paper)).find()
                        || finalTitlePattern.matcher(stringValue(paper.getOriginalFilename())).find())
                .filter(paper -> author.isBlank() || textContains(paper.getAuthors(), author))
                .filter(paper -> yearMatches(yearRange, paper.getPublicationYear()))
                .toList();
        return new FilteredPapers(papers, false);
    }

    private boolean statusMatches(String statusFilter, Paper paper) {
        String safeStatus = productStatus(statusFilter, "AVAILABLE");
        if ("ALL".equals(safeStatus)) {
            return true;
        }
        if ("NOT_IN_SCOPE".equals(safeStatus) || "NOT_VISIBLE".equals(safeStatus)) {
            return false;
        }
        return safeStatus.equals(productStatusFor(paper));
    }

    private String productStatus(Object rawStatus, String defaultStatus) {
        String status = stringValue(rawStatus).toUpperCase(Locale.ROOT);
        if (status.isBlank()) {
            return defaultStatus;
        }
        return switch (status) {
            case "AVAILABLE", "PROCESSING", "FAILED", "NOT_IN_SCOPE", "NOT_VISIBLE", "ALL" -> status;
            default -> defaultStatus;
        };
    }

    private String productStatusFor(Paper paper) {
        if (paperSearchabilityService.isSearchable(paper)) {
            return "AVAILABLE";
        }
        String status = paper == null || paper.getVectorizationStatus() == null
                ? ""
                : paper.getVectorizationStatus().trim().toUpperCase(Locale.ROOT);
        if (Paper.VECTORIZATION_STATUS_FAILED.equals(status)) {
            return "FAILED";
        }
        return "PROCESSING";
    }

    private List<Paper> sortPapers(List<Paper> papers, String sort) {
        String safeSort = sort == null || sort.isBlank() ? "recent" : sort.trim().toLowerCase(Locale.ROOT);
        Comparator<Paper> comparator = switch (safeSort) {
            case "title" -> Comparator
                    .comparing(this::displayTitle, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Paper::getPaperId, Comparator.nullsLast(String::compareTo));
            case "year" -> Comparator
                    .comparing(Paper::getPublicationYear, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(this::displayTitle, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator
                    .comparing(Paper::getMergedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .reversed()
                    .thenComparing(Paper::getId, Comparator.nullsLast(Comparator.reverseOrder()));
        };
        return (papers == null ? List.<Paper>of() : papers).stream()
                .sorted(comparator)
                .toList();
    }

    private Map<String, Object> paperMetadata(Paper paper, ProductToolContext context) {
        String paperId = stringValue(paper.getPaperId());
        String paperRef = paperRefFor(paperId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paperRef", paperRef);
        data.put("paperId", paperId);
        data.put("title", displayTitle(paper));
        data.put("originalFilename", paper.getOriginalFilename());
        data.put("status", productStatusFor(paper));
        data.put("authors", paper.getAuthors());
        data.put("venue", paper.getVenue());
        data.put("year", paper.getPublicationYear());
        data.put("abstract", paper.getAbstractText());
        data.put("doi", paper.getDoi());
        data.put("arxivId", paper.getArxivId());
        data.put("scopeMode", context.lockedScope().mode().name());
        return data;
    }

    private List<SearchResult> uniquePaperResults(List<SearchResult> results) {
        LinkedHashMap<String, SearchResult> byPaperId = new LinkedHashMap<>();
        for (SearchResult result : results == null ? List.<SearchResult>of() : results) {
            if (result == null || stringValue(result.getPaperId()).isBlank()) {
                continue;
            }
            byPaperId.putIfAbsent(result.getPaperId(), result);
        }
        return new ArrayList<>(byPaperId.values());
    }

    private Map<String, Object> paperSearchResultMetadata(SearchResult result, ProductToolContext context) {
        String paperId = stringValue(result == null ? null : result.getPaperId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paperRef", paperRefFor(paperId));
        data.put("paperId", paperId);
        data.put("title", stringValue(result == null ? null : result.getPaperTitle()));
        data.put("originalFilename", stringValue(result == null ? null : result.getOriginalFilename()));
        data.put("score", result == null ? null : result.getScore());
        data.put("matchSnippet", truncateForTool(EvidenceQuality.bestEvidenceText(result), 500));
        data.put("matchSourceKind", stringValue(result == null ? null : result.getSourceKind()));
        data.put("retrievalMode", stringValue(result == null ? null : result.getRetrievalMode()));
        data.put("retrievalRoute", stringValue(result == null ? null : result.getRetrievalRoute()));
        data.put("rankReason", stringValue(result == null ? null : result.getRankReason()));
        data.put("scopeMode", context.lockedScope().mode().name());
        return data;
    }

    private String truncateForTool(String value, int maxChars) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 3)).trim() + "...";
    }

    private String evidenceQuery(Map<String, Object> args) {
        String question = stringValue(args.get("question"));
        List<String> subQuestions = stringListValue(args.get("subQuestions"));
        if (subQuestions.isEmpty()) {
            return question;
        }
        String joinedSubQuestions = String.join("\n", subQuestions);
        return question.isBlank() ? joinedSubQuestions : question + "\n" + joinedSubQuestions;
    }

    private boolean hasTitleSelection(Map<String, Object> args) {
        return !stringValue(args.get("titleQuery")).isBlank()
                || !stringValue(args.get("titleRegex")).isBlank()
                || !stringValue(args.get("author")).isBlank()
                || args.containsKey("yearRange");
    }

    private SourceScope scopedSource(ProductToolContext context, List<String> paperIds) {
        if (paperIds == null || paperIds.isEmpty()) {
            return context.lockedScope();
        }
        return SourceScope.manual(paperIds, context.lockedScope().retrievalBudgetProfile());
    }

    private Map<String, Object> ledgerData(EvidenceLedger ledger, ProductToolContext context) {
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

    private Map<String, Object> paperSourceMetadata(PaperSource source, ProductToolContext context) {
        Map<String, Object> data = new LinkedHashMap<>();
        String paperId = source == null ? "" : stringValue(source.paperId());
        data.put("paperRef", paperRefFor(paperId));
        data.put("paperId", paperId);
        data.put("title", source == null ? "" : source.paperTitle());
        data.put("originalFilename", source == null ? "" : source.originalFilename());
        data.put("scopeMode", context.lockedScope().mode().name());
        return data;
    }

    private Map<String, Object> evidenceMetadata(EvidenceItem item, ProductToolContext context) {
        Map<String, Object> data = new LinkedHashMap<>();
        String paperId = item == null ? "" : stringValue(item.paperId());
        data.put("evidenceRef", item == null ? "" : item.evidenceId());
        data.put("paperRef", paperRefFor(paperId));
        data.put("paperId", paperId);
        data.put("paperTitle", item == null ? "" : item.paperTitle());
        data.put("originalFilename", item == null ? "" : item.originalFilename());
        data.put("pageNumber", item == null ? null : item.pageNumber());
        data.put("chunkId", item == null ? null : item.chunkId());
        data.put("sourceKind", item == null ? "" : item.sourceKind());
        data.put("sectionTitle", item == null ? "" : item.sectionTitle());
        data.put("matchedText", item == null ? "" : item.matchedText());
        data.put("bboxJson", item == null ? "" : item.bboxJson());
        data.put("score", item == null ? null : item.score());
        data.put("assetLevel", item == null ? "" : item.evidenceAssetLevel());
        data.put("evidenceAssetLevel", item == null ? "" : item.evidenceAssetLevel());
        data.put("pdfEvidenceAvailable", item != null && Boolean.TRUE.equals(item.pdfEvidenceAvailable()));
        data.put("pageScreenshotAvailable", item != null && Boolean.TRUE.equals(item.pageScreenshotAvailable()));
        data.put("figureScreenshotAvailable", item != null && Boolean.TRUE.equals(item.figureScreenshotAvailable()));
        data.put("assetWarnings", item == null ? List.of() : item.assetWarnings());
        data.put("tableId", item == null ? "" : item.tableId());
        data.put("figureId", item == null ? "" : item.figureId());
        data.put("formulaId", item == null ? "" : item.formulaId());
        data.put("evidenceRole", item == null ? "" : item.evidenceRole());
        data.put("tableText", item == null ? "" : item.tableText());
        data.put("tableMarkdown", item == null ? "" : item.tableMarkdown());
        data.put("tableScreenshotAvailable", item != null && Boolean.TRUE.equals(item.tableScreenshotAvailable()));
        data.put("scopeMode", context.lockedScope().mode().name());
        return data;
    }

    private Map<String, Object> diagnosticsMap(LedgerDiagnostics diagnostics) {
        LedgerDiagnostics safeDiagnostics = diagnostics == null
                ? new LedgerDiagnostics(0, 0, 0, "NO_USABLE_EVIDENCE")
                : diagnostics;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scannedCount", safeDiagnostics.scannedCount());
        data.put("acceptedEvidenceCount", safeDiagnostics.acceptedEvidenceCount());
        data.put("sourceCount", safeDiagnostics.sourceCount());
        data.put("stopReason", safeDiagnostics.stopReason());
        return data;
    }

    private Map<String, Object> diagnosticsMap(PaperRetrievalService.RetrievalDiagnostics diagnostics) {
        PaperRetrievalService.RetrievalDiagnostics safeDiagnostics = diagnostics == null
                ? new PaperRetrievalService.RetrievalDiagnostics(
                0,
                0,
                0,
                PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE
        )
                : diagnostics;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scannedCount", safeDiagnostics.scannedCount());
        data.put("acceptedEvidenceCount", safeDiagnostics.acceptedEvidenceCount());
        data.put("sourceCount", safeDiagnostics.sourceCount());
        data.put("stopReason", safeDiagnostics.stopReason().name());
        return data;
    }

    private Optional<ConversationReferenceRegistry.ResolvedReference> resolveReference(
            String refId,
            ProductToolContext context,
            PaperConversationReference.RefType refType) {
        return referenceRegistry.resolve(
                context.conversationId(),
                scopeSnapshotId(context.lockedScope()),
                refId,
                refType
        );
    }

    private String paperIdFromReference(ConversationReferenceRegistry.ResolvedReference reference) {
        return firstNonBlank(
                reference.sourceEntityId(),
                stringValue(reference.sourcePayload().get("paperId"))
        );
    }

    private String paperIdFromCitationReference(ConversationReferenceRegistry.ResolvedReference reference) {
        return firstNonBlank(
                stringValue(reference.sourcePayload().get("paperId")),
                stringValue(reference.displayPayload().get("paperId"))
        );
    }

    private boolean hasSelectionCriteria(Map<String, Object> args) {
        return !stringValue(args.get("titleQuery")).isBlank()
                || !stringValue(args.get("titleRegex")).isBlank()
                || !stringValue(args.get("userMention")).isBlank()
                || !stringValue(args.get("author")).isBlank()
                || args.containsKey("yearRange")
                || boundedInteger(args.get("ordinal"), 0, 0, Integer.MAX_VALUE) > 0;
    }

    private int boundedInteger(Object value, int defaultValue, int min, int max) {
        Integer parsed = integerValue(value);
        if (parsed == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private String displayTitle(Paper paper) {
        if (paper == null) {
            return "";
        }
        String title = paper.getPaperTitle();
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        String filename = paper.getOriginalFilename();
        return filename == null || filename.isBlank() ? stringValue(paper.getPaperId()) : filename.trim();
    }

    private int statusScopedPaperCount(ProductToolContext context, String statusFilter) {
        return (int) accessiblePapers(context).stream()
                .filter(paper -> statusMatches(statusFilter, paper))
                .count();
    }

    private boolean hasPaperListFilter(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return false;
        }
        return !stringValue(args.get("titleQuery")).isBlank()
                || !stringValue(args.get("titleRegex")).isBlank()
                || !stringValue(args.get("userMention")).isBlank()
                || !stringValue(args.get("author")).isBlank()
                || args.containsKey("yearRange");
    }

    private boolean textContains(String value, String query) {
        String normalizedValue = normalize(value);
        String normalizedQuery = normalize(query);
        return !normalizedQuery.isBlank() && normalizedValue.contains(normalizedQuery);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private YearRange yearRange(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new YearRange(null, null);
        }
        return new YearRange(integerValue(map.get("from")), integerValue(map.get("to")));
    }

    private boolean yearMatches(YearRange range, Integer year) {
        if (range.from() == null && range.to() == null) {
            return true;
        }
        if (year == null) {
            return false;
        }
        return (range.from() == null || year >= range.from())
                && (range.to() == null || year <= range.to());
    }

    private String paperRefFor(String paperId) {
        String safePaperId = stringValue(paperId);
        if (safePaperId.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(safePaperId.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("paper_");
            for (int i = 0; i < 8 && i < hashed.length; i++) {
                builder.append(String.format("%02x", hashed[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            return "paper_" + Integer.toUnsignedString(safePaperId.hashCode(), 16);
        }
    }

    private int visibleCollectionCount(Long userId) {
        if (paperCollectionService == null || userId == null) {
            return 0;
        }
        List<Map<String, Object>> collections = paperCollectionService.listCollections(userId);
        return collections == null ? 0 : collections.size();
    }

    private String scopeSnapshotId(SourceScope scope) {
        SourceScope safeScope = scope == null ? SourceScope.auto() : scope;
        return safeScope.mode().name() + ":" + safeScope.paperIds().hashCode();
    }

    private String userId(Long userId) {
        return userId == null ? "" : String.valueOf(userId);
    }

    private record FilteredPapers(List<Paper> papers, boolean invalidRegex) {
        private FilteredPapers {
            papers = papers == null ? List.of() : papers;
        }
    }

    private record YearRange(Integer from, Integer to) {
    }

    private AgentToolRegistry.AgentTool answerWithoutProductStateTool() {
        return new AgentToolRegistry.AgentTool(
                "answer_without_product_state",
                "Use only for smalltalk, PaperLoom capability explanation, unsupported non-paper requests, or clarification that does not depend on product state or paper evidence.",
                objectSchema(Map.of(
                        "reason", enumSchema("Why product state is not needed.", List.of(
                                "smalltalk",
                                "ui_help",
                                "unsupported_non_paper_request",
                                "needs_user_clarification_without_product_state"
                        )),
                        "answerDraft", stringSchema("Draft answer without paper counts, titles, filenames, status, references, pages, citations, or evidence claims.")
                ), List.of("reason", "answerDraft"))
        );
    }

    private AgentToolRegistry.AgentTool getSystemStateTool() {
        return new AgentToolRegistry.AgentTool(
                "get_system_state",
                "Return product semantic state for the current paper library and locked session scope. Do not expose parser, provider, queue, index, or infrastructure internals.",
                objectSchema(Map.of(
                        "include", arrayEnumSchema("Product-state sections to include.", List.of(
                                "library",
                                "processing",
                                "retrieval",
                                "session",
                                "collections"
                        ))
                ), List.of())
        );
    }

    private AgentToolRegistry.AgentTool getSessionScopeTool() {
        return new AgentToolRegistry.AgentTool(
                "get_session_scope",
                "Return the locked session evidence universe and product constraints. Does not list papers and does not read paper content.",
                objectSchema(Map.of(), List.of())
        );
    }

    private AgentToolRegistry.AgentTool listPapersTool() {
        return new AgentToolRegistry.AgentTool(
                "list_papers",
                "List paper-level metadata inside the locked session scope. Use for browsing or explicit title/filename/author filters. Do not use for semantic topic search or recommendations. Does not read paper content or create citations.",
                objectSchema(Map.of(
                        "page", integerSchema("1-based page number."),
                        "pageSize", integerSchema("Page size."),
                        "status", enumSchema("Product semantic status filter.", List.of(
                                "AVAILABLE",
                                "PROCESSING",
                                "FAILED",
                                "NOT_IN_SCOPE",
                                "NOT_VISIBLE",
                                "ALL"
                        )),
                        "titleQuery", stringSchema("Plain title or filename substring filter. Do not use as semantic topic search."),
                        "titleRegex", stringSchema("Explicit title regular expression selected by the user."),
                        "author", stringSchema("Author filter."),
                        "yearRange", objectSchema(Map.of(
                                "from", integerSchema("Start year."),
                                "to", integerSchema("End year.")
                        ), List.of()),
                        "sort", enumSchema("Sort order.", List.of("recent", "title", "year"))
                ), List.of())
        );
    }

    private AgentToolRegistry.AgentTool findPapersTool() {
        return new AgentToolRegistry.AgentTool(
                "find_papers",
                "Find papers semantically related to a natural-language topic inside the locked session scope. Use for paper recommendation, discovery, or topic-based selection. Returns opaque paper refs and non-citeable selection rationale; use retrieve_evidence before making paper-content claims.",
                objectSchema(Map.of(
                        "query", stringSchema("Natural-language paper discovery topic or recommendation need."),
                        "limit", integerSchema("Maximum candidate papers to return.")
                ), List.of("query"))
        );
    }

    private AgentToolRegistry.AgentTool resolvePapersTool() {
        return new AgentToolRegistry.AgentTool(
                "resolve_papers",
                "Resolve user-mentioned papers to opaque paper refs inside the locked session scope. Use this for titles, filenames, arXiv-like ids, ordinals, and follow-up wording. Return candidates when ambiguous and do not guess.",
                objectSchema(Map.of(
                        "userMention", stringSchema("The user's paper mention, ordinal, title, filename, or follow-up wording."),
                        "paperRefs", arrayStringSchema("Opaque paper refs previously returned by PaperLoom. Values must start with paper_; never pass filenames, arXiv ids, DOI values, titles, or raw paper ids here."),
                        "citationRefs", arrayStringSchema("Opaque citation refs previously returned by PaperLoom."),
                        "titleQuery", stringSchema("Plain title or filename substring filter. Do not use as semantic topic search."),
                        "titleRegex", stringSchema("Explicit title regular expression selected by the user."),
                        "author", stringSchema("Author filter."),
                        "yearRange", objectSchema(Map.of(
                                "from", integerSchema("Start year."),
                                "to", integerSchema("End year.")
                        ), List.of()),
                        "limit", integerSchema("Maximum candidates to return.")
                ), List.of())
        );
    }

    private AgentToolRegistry.AgentTool getPaperMetadataTool() {
        return new AgentToolRegistry.AgentTool(
                "get_paper_metadata",
                "Return bibliographic and product metadata for opaque paper refs. Does not read paper content or create citations.",
                objectSchema(Map.of(
                        "paperRefs", arrayStringSchema("Opaque paper refs returned by PaperLoom. Values must start with paper_; resolve title/filename/arXiv-like mentions with resolve_papers first."),
                        "fields", arrayStringSchema("Requested metadata fields.")
                ), List.of("paperRefs"))
        );
    }

    private AgentToolRegistry.AgentTool retrieveEvidenceTool() {
        return new AgentToolRegistry.AgentTool(
                "retrieve_evidence",
                "Retrieve citeable paper evidence for content questions using product semantic inputs. The backend controls retrieval strategy.",
                objectSchema(Map.of(
                        "question", stringSchema("Natural-language question to answer from paper evidence."),
                        "subQuestions", arrayStringSchema("Optional natural-language subquestions or comparison aspects."),
                        "paperConstraints", arrayObjectSchema("Opaque paper-ref constraints inside locked scope.", objectSchema(Map.of(
                                "paperRef", stringSchema("Opaque PaperLoom paper ref returned by tools or reference registry. Must start with paper_; never pass filenames, arXiv ids, DOI values, titles, ordinals, or raw paper ids.")
                        ), List.of("paperRef"))),
                        "titleQuery", stringSchema("Optional plain title substring constraint. Do not use as semantic topic search."),
                        "titleRegex", stringSchema("Optional explicit title regular expression selected by the user.")
                ), List.of("question"))
        );
    }

    private AgentToolRegistry.AgentTool inspectReferenceTool() {
        return new AgentToolRegistry.AgentTool(
                "inspect_reference",
                "Inspect an opaque citation or evidence ref from the persistent reference registry. Does not perform unrelated retrieval.",
                objectSchema(Map.of(
                        "citationRef", stringSchema("Opaque citation ref."),
                        "evidenceRef", stringSchema("Opaque evidence ref.")
                ), List.of())
        );
    }

    private AgentToolRegistry.AgentTool inspectPageTool() {
        return new AgentToolRegistry.AgentTool(
                "inspect_page",
                "Inspect known page context from an opaque page ref or paper ref plus page number. Does not broaden the session scope.",
                objectSchema(Map.of(
                        "pageRef", stringSchema("Opaque page ref."),
                        "paperRef", stringSchema("Opaque PaperLoom paper ref returned by tools or reference registry. Must start with paper_; resolve title/filename/arXiv-like mentions with resolve_papers first."),
                        "pageNumber", integerSchema("1-based page number.")
                ), List.of())
        );
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties == null ? Map.of() : properties);
        schema.put("required", required == null ? List.of() : required);
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

    private Map<String, Object> enumSchema(String description, List<String> values) {
        Map<String, Object> schema = stringSchema(description);
        schema.put("enum", values == null ? List.of() : values);
        return schema;
    }

    private Map<String, Object> arrayStringSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("items", Map.of("type", "string"));
        return schema;
    }

    private Map<String, Object> arrayEnumSchema(String description, List<String> values) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("items", Map.of(
                "type", "string",
                "enum", values == null ? List.of() : values
        ));
        return schema;
    }

    private Map<String, Object> arrayObjectSchema(String description, Map<String, Object> itemSchema) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("items", itemSchema == null ? Map.of("type", "object") : itemSchema);
        return schema;
    }
}
