package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.Paper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class EvidenceToolExecutor {

    private final PaperRetrievalService paperRetrievalService;
    private final PaperService paperService;
    private final ConversationService conversationService;
    private final EvidenceLedgerService evidenceLedgerService;
    private final PaperPageWindowService pageWindowService;

    @Autowired
    public EvidenceToolExecutor(PaperRetrievalService paperRetrievalService,
                                PaperService paperService,
                                ConversationService conversationService,
                                EvidenceLedgerService evidenceLedgerService,
                                PaperPageWindowService pageWindowService) {
        this.paperRetrievalService = paperRetrievalService;
        this.paperService = paperService;
        this.conversationService = conversationService;
        this.evidenceLedgerService = evidenceLedgerService;
        this.pageWindowService = pageWindowService;
    }

    public EvidenceToolExecutor(PaperRetrievalService paperRetrievalService,
                                PaperService paperService,
                                ConversationService conversationService,
                                EvidenceLedgerService evidenceLedgerService) {
        this(paperRetrievalService, paperService, conversationService, evidenceLedgerService, null);
    }

    public EvidenceToolResult execute(String userId,
                                      String conversationId,
                                      PlannerAction action,
                                      SourceScope scope) {
        PlannerAction safeAction = action == null ? PlannerAction.clarify("missing_action") : action;
        SourceScope safeScope = scope == null ? SourceScope.auto() : scope;
        return switch (safeAction.type()) {
            case LIST_LIBRARY -> listLibrary(userId);
            case DISCOVER_PAPERS -> discoverPapers(userId, safeAction);
            case SEARCH_EVIDENCE -> searchEvidence(userId, safeAction, safeScope);
            case INSPECT_PAGE -> inspectPage(safeAction, safeScope);
            case INSPECT_REFERENCE -> inspectReference(userId, conversationId, safeAction, safeScope);
            case ASK_CLARIFICATION -> new EvidenceToolResult(safeAction.type(), EvidenceLedger.empty(), safeAction.reason());
            case ANSWER_WITH_LEDGER -> new EvidenceToolResult(safeAction.type(), EvidenceLedger.empty(), safeAction.reason());
        };
    }

    private EvidenceToolResult listLibrary(String userId) {
        List<Paper> papers = paperService.getAccessiblePapers(userId, null);
        List<PaperSource> sources = papers == null ? List.of() : papers.stream()
                .map(paper -> new PaperSource(
                        paper.getPaperId(),
                        displayTitle(paper),
                        paper.getOriginalFilename()
                ))
                .toList();
        EvidenceLedger ledger = new EvidenceLedger(
                sources,
                List.of(),
                new LedgerDiagnostics(sources.size(), 0, sources.size(), sources.isEmpty() ? "NO_USABLE_EVIDENCE" : "EXHAUSTED")
        );
        return new EvidenceToolResult(PlannerActionType.LIST_LIBRARY, ledger, "");
    }

    private EvidenceToolResult discoverPapers(String userId, PlannerAction action) {
        PaperRetrievalService.RetrievalResult retrieval = paperRetrievalService.discoverPapers(
                action.query(),
                userId,
                RetrievalBudget.forLibrarySearch()
        );
        if (retrieval == null) {
            return new EvidenceToolResult(PlannerActionType.DISCOVER_PAPERS, EvidenceLedger.empty(), "retrieval_empty");
        }
        EvidenceLedger ledger = evidenceLedgerService.fromSearchResults(retrieval.results(), RetrievalBudget.forLibrarySearch());
        return new EvidenceToolResult(PlannerActionType.DISCOVER_PAPERS, ledger, "");
    }

    private EvidenceToolResult searchEvidence(String userId, PlannerAction action, SourceScope scope) {
        List<String> scopePaperIds = effectivePaperIds(action, scope);
        RetrievalBudget budget = qaBudget(scope);
        PaperRetrievalService.RetrievalResult retrieval = paperRetrievalService.retrieve(
                action.query(),
                userId,
                budget,
                scopePaperIds
        );
        if (retrieval == null) {
            return new EvidenceToolResult(PlannerActionType.SEARCH_EVIDENCE, EvidenceLedger.empty(), "retrieval_empty");
        }
        List<SearchResult> scopedResults = filterByScope(retrieval.results(), scopePaperIds);
        EvidenceLedger ledger = evidenceLedgerService.fromSearchResults(scopedResults, budget);
        return new EvidenceToolResult(PlannerActionType.SEARCH_EVIDENCE, ledger, "");
    }

    private EvidenceToolResult inspectPage(PlannerAction action, SourceScope scope) {
        if (pageWindowService == null) {
            return new EvidenceToolResult(PlannerActionType.INSPECT_PAGE, EvidenceLedger.empty(), "page_window_unavailable");
        }
        List<String> paperIds = effectivePaperIds(action, scope);
        Integer pageNumber = action.pageNumber();
        if (paperIds.isEmpty() || pageNumber == null) {
            return new EvidenceToolResult(PlannerActionType.INSPECT_PAGE, EvidenceLedger.empty(), "missing_page_scope");
        }
        RetrievalBudget budget = qaBudget(scope);
        int radius = action.windowRadius() == null ? budget.pageWindowRadius() : action.windowRadius();
        List<SearchResult> results = pageWindowService.inspectPageWindow(paperIds.get(0), pageNumber, radius);
        EvidenceLedger ledger = evidenceLedgerService.fromSearchResults(results, budget);
        return new EvidenceToolResult(PlannerActionType.INSPECT_PAGE, ledger, "");
    }

    private List<SearchResult> filterByScope(List<SearchResult> results, List<String> scopePaperIds) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        if (scopePaperIds == null || scopePaperIds.isEmpty()) {
            return results;
        }
        LinkedHashSet<String> allowed = new LinkedHashSet<>(scopePaperIds);
        return results.stream()
                .filter(result -> result != null && allowed.contains(result.getPaperId()))
                .toList();
    }

    private EvidenceToolResult inspectReference(String userId,
                                                String conversationId,
                                                PlannerAction action,
                                                SourceScope scope) {
        Integer referenceNumber = action.referenceNumber() == null ? scope.referenceNumber() : action.referenceNumber();
        Long parsedUserId = parseLong(userId);
        if (referenceNumber == null || parsedUserId == null) {
            return new EvidenceToolResult(PlannerActionType.INSPECT_REFERENCE, EvidenceLedger.empty(), "missing_reference");
        }
        Optional<Map<String, Object>> detail;
        if (scope.conversationRecordId() != null) {
            detail = conversationService.findReferenceDetail(parsedUserId, scope.conversationRecordId(), referenceNumber);
        } else {
            detail = conversationService.findLatestReferenceDetail(parsedUserId, conversationId, referenceNumber);
        }
        if (detail.isEmpty()) {
            return new EvidenceToolResult(PlannerActionType.INSPECT_REFERENCE, EvidenceLedger.empty(), "reference_not_found");
        }
        EvidenceLedger ledger = evidenceLedgerService.fromSearchResults(
                List.of(referenceDetailToSearchResult(detail.get())),
                qaBudget(scope)
        );
        return new EvidenceToolResult(PlannerActionType.INSPECT_REFERENCE, ledger, "");
    }

    private RetrievalBudget qaBudget(SourceScope scope) {
        RetrievalBudgetProfile profile = scope == null ? RetrievalBudgetProfile.INTERACTIVE : scope.retrievalBudgetProfile();
        return profile.qaBudget();
    }

    private List<String> effectivePaperIds(PlannerAction action, SourceScope scope) {
        LinkedHashSet<String> paperIds = new LinkedHashSet<>();
        if (scope != null) {
            paperIds.addAll(scope.paperIds());
        }
        if (action != null) {
            paperIds.addAll(action.paperIds());
        }
        return new ArrayList<>(paperIds);
    }

    private SearchResult referenceDetailToSearchResult(Map<String, Object> detail) {
        String matchedText = stringValue(firstPresent(detail, "matchedChunkText", "evidenceSnippet", "anchorText"));
        SearchResult result = new SearchResult(
                stringValue(detail.get("paperId")),
                integerValue(detail.get("chunkId")),
                matchedText,
                doubleValue(detail.get("score")),
                null,
                null,
                false,
                stringValue(detail.get("paperTitle")),
                stringValue(detail.get("originalFilename")),
                integerValue(detail.get("pageNumber")),
                stringValue(detail.get("anchorText")),
                stringValue(detail.getOrDefault("retrievalMode", "REFERENCE")),
                matchedText,
                stringValue(detail.get("elementType")),
                stringValue(detail.get("sectionTitle")),
                integerValue(detail.get("sectionLevel")),
                stringValue(detail.get("bboxJson")),
                stringValue(detail.get("parserName")),
                stringValue(detail.get("parserVersion")),
                stringValue(detail.get("sourceKind")),
                stringValue(detail.get("tableId")),
                stringValue(detail.get("tableText")),
                stringValue(detail.get("tableMarkdown")),
                booleanValue(detail.get("tableScreenshotAvailable"))
        );
        result.setFigureId(stringValue(detail.get("figureId")));
        result.setFormulaId(stringValue(detail.get("formulaId")));
        result.setEvidenceRole(stringValue(detail.get("evidenceRole")));
        result.setRetrievalRoute("REFERENCE_SOURCE");
        result.setIntent(PaperAnswerService.Intent.REFERENCE_QA.name());
        return result;
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String displayTitle(Paper paper) {
        if (paper == null) {
            return "";
        }
        if (paper.getPaperTitle() != null && !paper.getPaperTitle().isBlank()) {
            return paper.getPaperTitle();
        }
        return paper.getOriginalFilename() == null ? "" : paper.getOriginalFilename();
    }

    private Long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            String text = stringValue(value);
            return text == null || text.isBlank() ? null : Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            String text = stringValue(value);
            return text == null || text.isBlank() ? null : Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? null : Boolean.parseBoolean(String.valueOf(value));
    }
}
