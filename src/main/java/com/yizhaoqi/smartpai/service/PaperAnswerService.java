package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PaperAnswerService {

    private static final Logger logger = LoggerFactory.getLogger(PaperAnswerService.class);

    private static final Duration FOCUS_TTL = Duration.ofDays(7);
    private static final Pattern EVIDENCE_TOKEN_PATTERN = Pattern.compile("\\{\\{E(\\d+)}}");
    private static final Pattern BRACKET_CITATION_PATTERN = Pattern.compile("\\[\\d+]");
    private static final Pattern USER_REFERENCE_PATTERN = Pattern.compile("\\[(\\d+)]");
    private static final Pattern LEGACY_SOURCE_PATTERN = Pattern.compile("(?:来源|source)\\s*#\\s*\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_TITLE_PATTERN = Pattern.compile("《([^》]+)》");
    private static final int MAX_RECOMMENDED_PAPERS = 15;

    private final PaperRetrievalService paperRetrievalService;
    private final PaperService paperService;
    private final ConversationService conversationService;
    private final LlmProviderRouter llmProviderRouter;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final PaperChatRouter paperChatRouter;
    private final EvidencePlanner evidencePlanner;
    private final EvidenceLedgerService evidenceLedgerService;
    private final EvidenceAnswerGenerator evidenceAnswerGenerator;
    private final EvidenceVerifier evidenceVerifier;
    private final EvidenceToolExecutor evidenceToolExecutor;
    private final TaskRouter taskRouter;
    private final PaperLibraryStatusService paperLibraryStatusService;

    public PaperAnswerService(PaperRetrievalService paperRetrievalService,
                              PaperService paperService,
                              ConversationService conversationService,
                              LlmProviderRouter llmProviderRouter,
                              RedisTemplate<String, String> redisTemplate,
                              ObjectMapper objectMapper,
                              PaperChatRouter paperChatRouter,
                              EvidencePlanner evidencePlanner,
                              EvidenceLedgerService evidenceLedgerService,
                              EvidenceAnswerGenerator evidenceAnswerGenerator,
                              EvidenceVerifier evidenceVerifier,
                              EvidenceToolExecutor evidenceToolExecutor) {
        this(
                paperRetrievalService,
                paperService,
                conversationService,
                llmProviderRouter,
                redisTemplate,
                objectMapper,
                paperChatRouter,
                evidencePlanner,
                evidenceLedgerService,
                evidenceAnswerGenerator,
                evidenceVerifier,
                evidenceToolExecutor,
                new LlmTaskRouter(llmProviderRouter, objectMapper),
                null
        );
    }

    @Autowired
    public PaperAnswerService(PaperRetrievalService paperRetrievalService,
                              PaperService paperService,
                              ConversationService conversationService,
                              LlmProviderRouter llmProviderRouter,
                              RedisTemplate<String, String> redisTemplate,
                              ObjectMapper objectMapper,
                              PaperChatRouter paperChatRouter,
                              EvidencePlanner evidencePlanner,
                              EvidenceLedgerService evidenceLedgerService,
                              EvidenceAnswerGenerator evidenceAnswerGenerator,
                              EvidenceVerifier evidenceVerifier,
                              EvidenceToolExecutor evidenceToolExecutor,
                              TaskRouter taskRouter,
                              PaperLibraryStatusService paperLibraryStatusService) {
        this.paperRetrievalService = paperRetrievalService;
        this.paperService = paperService;
        this.conversationService = conversationService;
        this.llmProviderRouter = llmProviderRouter;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.paperChatRouter = paperChatRouter;
        this.evidencePlanner = evidencePlanner;
        this.evidenceLedgerService = evidenceLedgerService;
        this.evidenceAnswerGenerator = evidenceAnswerGenerator;
        this.evidenceVerifier = evidenceVerifier;
        this.evidenceToolExecutor = evidenceToolExecutor;
        this.taskRouter = taskRouter == null ? new LlmTaskRouter(llmProviderRouter, objectMapper) : taskRouter;
        this.paperLibraryStatusService = paperLibraryStatusService;
    }

    PaperAnswerService(PaperRetrievalService paperRetrievalService,
                       PaperService paperService,
                       ConversationService conversationService,
                       LlmProviderRouter llmProviderRouter,
                       RedisTemplate<String, String> redisTemplate,
                       ObjectMapper objectMapper) {
        this(
                paperRetrievalService,
                paperService,
                conversationService,
                llmProviderRouter,
                redisTemplate,
                objectMapper,
                new PaperChatRouter(),
                new EvidencePlanner(llmProviderRouter, objectMapper),
                new EvidenceLedgerService(),
                new EvidenceAnswerGenerator(llmProviderRouter, new EvidenceVerifier()),
                new EvidenceVerifier(),
                new EvidenceToolExecutor(
                        paperRetrievalService,
                        paperService,
                        conversationService,
                        new EvidenceLedgerService()
                ),
                new LlmTaskRouter(llmProviderRouter, objectMapper),
                null
        );
    }

    public AnswerResult answer(String userId, String conversationId, String userMessage) {
        return answer(userId, conversationId, userMessage, null);
    }

    public AnswerResult answer(String userId, String conversationId, String userMessage, AnswerScope scope) {
        Intent intent = paperChatRouter.route(userMessage, scope);
        String taskQuery = userMessage == null ? "" : userMessage;
        if (intent == Intent.SMALLTALK) {
            return new AnswerResult(
                    "我在。你可以直接问论文的方法、实验、结论、表格或某个引用点。",
                    Map.of(),
                    intent,
                    0,
                    0,
                    false
            );
        }

        if (intent == Intent.AUTO_SOURCE_QA) {
            TaskRoutingResult routing = taskRouter.route(new TaskRoutingRequest(
                    userId,
                    conversationId,
                    userMessage,
                    scope == null || scope.paperIds().isEmpty()
                            ? SourceScope.auto(budgetProfile(scope))
                            : SourceScope.manual(scope.paperIds(), budgetProfile(scope))
            ));
            if (routing.failed()) {
                return routingFailure(routing.failure());
            }
            TaskDecision decision = routing.decision();
            intent = intentFor(decision.taskType());
            if (!decision.query().isBlank()) {
                taskQuery = decision.query();
            }
            if (intent == Intent.SMALLTALK) {
                return new AnswerResult(
                        "我在。你可以直接问论文的方法、实验、结论、表格或某个引用点。",
                        Map.of(),
                        intent,
                        0,
                        0,
                        false
                );
            }
            if (intent == Intent.LIBRARY_STATUS) {
                return renderLibraryStatus(userId, scope == null || scope.paperIds().isEmpty()
                        ? SourceScope.auto(budgetProfile(scope))
                        : SourceScope.manual(scope.paperIds(), budgetProfile(scope)));
            }
            if (intent == Intent.CLARIFY) {
                return clarify();
            }
        }

        FocusState scopedFocus = toFocus(scope);
        FocusState focus = scopedFocus == null ? readFocus(conversationId) : scopedFocus;
        if (focus == null && intent == Intent.FOLLOW_UP) {
            focus = readFocusFromHistory(userId, conversationId);
        }
        if (intent == Intent.REFERENCE_QA) {
            EvidenceLedger seedLedger = referenceSeedLedger(scope);
            return answerWithHarness(
                    userId,
                    conversationId,
                    taskQuery,
                    Intent.REFERENCE_QA,
                    sourceScopeFor(intent, scope, focus, taskQuery),
                    seedLedger
            );
        }
        if (intent == Intent.MANUAL_SOURCE_QA) {
            return answerWithHarness(
                    userId,
                    conversationId,
                    taskQuery,
                    Intent.MANUAL_SOURCE_QA,
                    sourceScopeFor(intent, scope, focus, taskQuery),
                    EvidenceLedger.empty()
            );
        }
        if (intent == Intent.FOLLOW_UP) {
            if (focus == null || focus.lastPaperIds().isEmpty()) {
                return clarify();
            }
            if (focus.lastPaperIds().size() > 1) {
                return new AnswerResult("你想讲第几篇？", Map.of(), Intent.CLARIFY, 0, focus.lastPaperIds().size(), false);
            }
            return answerWithHarness(
                    userId,
                    conversationId,
                    scopedQuery(taskQuery, focus),
                    Intent.MANUAL_SOURCE_QA,
                    sourceScopeFor(Intent.MANUAL_SOURCE_QA, scope, focus, taskQuery),
                    EvidenceLedger.empty()
            );
        }
        if (intent == Intent.CLARIFY) {
            return clarify();
        }
        if (intent == Intent.LIBRARY_SEARCH) {
            return answerWithHarness(
                    userId,
                    conversationId,
                    taskQuery,
                    Intent.LIBRARY_SEARCH,
                    SourceScope.auto(budgetProfile(scope)),
                    EvidenceLedger.empty()
            );
        }
        if (intent == Intent.PAPER_QA) {
            return answerWithHarness(
                    userId,
                    conversationId,
                    taskQuery,
                    Intent.PAPER_QA,
                    sourceScopeFor(intent, scope, focus, taskQuery),
                    EvidenceLedger.empty()
            );
        }
        return answerWithHarness(
                userId,
                conversationId,
                taskQuery,
                intent,
                sourceScopeFor(intent, scope, focus, taskQuery),
                EvidenceLedger.empty()
        );
    }

    private Intent intentFor(TaskType taskType) {
        return switch (taskType == null ? TaskType.CLARIFY : taskType) {
            case SMALLTALK -> Intent.SMALLTALK;
            case CLARIFY -> Intent.CLARIFY;
            case LIBRARY_STATUS -> Intent.LIBRARY_STATUS;
            case PAPER_DISCOVERY -> Intent.LIBRARY_SEARCH;
            case PAPER_QA -> Intent.PAPER_QA;
            case REFERENCE_QA -> Intent.REFERENCE_QA;
            case FOLLOW_UP -> Intent.FOLLOW_UP;
        };
    }

    private AnswerResult routingFailure(TaskRoutingFailure failure) {
        TaskRoutingFailure safeFailure = failure == null
                ? new TaskRoutingFailure(TaskRoutingFailure.ReasonCode.UNSUPPORTED_TASK, "", "missing_failure", Map.of())
                : failure;
        return new AnswerResult(
                safeFailure.userMessage(),
                Map.of(),
                Intent.CLARIFY,
                0,
                0,
                false,
                new AnswerDiagnostics(
                        "ROUTING_FAILURE",
                        ScopeMode.AUTO_SOURCE.name(),
                        0,
                        0,
                        0,
                        safeFailure.reasonCode().name()
                )
        );
    }

    private AnswerResult renderLibraryStatus(String userId, SourceScope sourceScope) {
        SourceScope effectiveScope = sourceScope == null ? SourceScope.auto() : sourceScope;
        if (paperLibraryStatusService == null) {
            return new AnswerResult(
                    "我无法可靠读取论文库状态，因此不会执行检索来猜测答案。",
                    Map.of(),
                    Intent.LIBRARY_STATUS,
                    0,
                    0,
                    false,
                    new AnswerDiagnostics(
                            Intent.LIBRARY_STATUS.name(),
                            scopeMode(effectiveScope, Intent.LIBRARY_STATUS).name(),
                            0,
                            0,
                            0,
                            "LIBRARY_STATUS_UNAVAILABLE"
                    )
            );
        }
        PaperLibraryStatus status = paperLibraryStatusService.statusFor(userId, effectiveScope);
        StringBuilder markdown = new StringBuilder()
                .append("**结论**\n")
                .append("当前可检索论文有 ")
                .append(status.searchableCount())
                .append(" 篇。\n\n")
                .append("**状态**\n")
                .append("- 可访问论文：").append(status.accessibleCount()).append(" 篇\n")
                .append("- 当前检索范围：").append(status.selectedScopeCount()).append(" 篇\n")
                .append("- 解析中：").append(status.parsingCount()).append(" 篇\n")
                .append("- 索引中：").append(status.indexingCount()).append(" 篇\n")
                .append("- 失败：").append(status.failedCount()).append(" 篇");
        if (!status.consistencyWarnings().isEmpty()) {
            markdown.append("\n\n**一致性问题**\n");
            for (String warning : status.consistencyWarnings()) {
                markdown.append("- ").append(warning).append("\n");
            }
        }
        return new AnswerResult(
                markdown.toString(),
                Map.of(),
                Intent.LIBRARY_STATUS,
                0,
                0,
                false,
                new AnswerDiagnostics(
                        Intent.LIBRARY_STATUS.name(),
                        scopeMode(effectiveScope, Intent.LIBRARY_STATUS).name(),
                        0,
                        0,
                        0,
                        "EXHAUSTED"
                )
        );
    }

    private AnswerResult answerWithHarness(String userId,
                                           String conversationId,
                                           String userMessage,
                                           Intent responseIntent,
                                           SourceScope sourceScope,
                                           EvidenceLedger seedLedger) {
        EvidenceLedger ledger = seedLedger == null ? EvidenceLedger.empty() : seedLedger;
        SourceScope effectiveScope = sourceScope == null ? SourceScope.auto() : sourceScope;
        PlannerAction lastAction = null;
        int plannerRounds = 0;
        List<String> attemptedQueries = new ArrayList<>();
        if (responseIntent == Intent.REFERENCE_QA && !ledger.evidence().isEmpty()) {
            return answerFromHarnessLedger(userId, conversationId, userMessage, responseIntent, effectiveScope,
                    ledger, plannerRounds, attemptedQueries);
        }
        for (int round = 0; round < 3; round++) {
            plannerRounds++;
            PlannerContext context = new PlannerContext(userId, userMessage, responseIntent, effectiveScope, ledger);
            PlannerAction action = evidencePlanner.plan(context);
            lastAction = action;
            if (action == null || action.type() == PlannerActionType.ASK_CLARIFICATION) {
                return responseIntent == Intent.REFERENCE_QA ? clarifyMissingReference() : clarify();
            }
            if (action.type() == PlannerActionType.ANSWER_WITH_LEDGER) {
                return answerFromHarnessLedger(userId, conversationId, userMessage, responseIntent, effectiveScope,
                        ledger, plannerRounds, attemptedQueries);
            }

            if (action.query() != null && !action.query().isBlank()) {
                attemptedQueries.add(action.query());
            }
            EvidenceToolResult toolResult = evidenceToolExecutor.execute(userId, conversationId, action, effectiveScope);
            ledger = mergeLedgers(ledger, toolResult.ledger());
            if (action.type() == PlannerActionType.LIST_LIBRARY) {
                return renderLibraryFromLedger(conversationId, ledger, plannerRounds, attemptedQueries);
            }
            if (action.type() == PlannerActionType.DISCOVER_PAPERS) {
                return renderRecommendationFromLedger(conversationId, userMessage, ledger, plannerRounds, attemptedQueries);
            }
            if (action.type() == PlannerActionType.INSPECT_REFERENCE && ledger.evidence().isEmpty()) {
                return clarifyMissingReference();
            }
        }

        if (responseIntent == Intent.LIBRARY_SEARCH) {
            return renderRecommendationFromLedger(conversationId, userMessage, ledger, plannerRounds, attemptedQueries);
        }
        if (lastAction != null && lastAction.type() == PlannerActionType.INSPECT_REFERENCE && ledger.evidence().isEmpty()) {
            return clarifyMissingReference();
        }
        return answerFromHarnessLedger(userId, conversationId, userMessage, responseIntent, effectiveScope,
                ledger, plannerRounds, attemptedQueries);
    }

    private AnswerResult renderLibraryFromLedger(String conversationId,
                                                 EvidenceLedger ledger,
                                                 int plannerRounds,
                                                 List<String> attemptedQueries) {
        EvidenceLedger safeLedger = ledger == null ? EvidenceLedger.empty() : ledger;
        List<PaperSource> sources = safeLedger.sourceSet();
        if (sources.isEmpty()) {
            writeFocus(conversationId, new FocusState(Intent.LIBRARY_SEARCH.name(), List.of(), List.of()));
            return new AnswerResult(
                    "**结论**\n当前可访问论文库中还没有论文。\n\n**限制**\n上传并完成解析后，论文才会出现在这里。",
                    Map.of(),
                    Intent.LIBRARY_SEARCH,
                    0,
                    0,
                    false,
                    diagnostics(Intent.LIBRARY_SEARCH, ScopeMode.AUTO_SOURCE, safeLedger.diagnostics(), 0, 0,
                            plannerRounds, attemptedQueries, false)
            );
        }
        StringBuilder markdown = new StringBuilder()
                .append("**结论**\n当前可访问论文库中有 ")
                .append(sources.size())
                .append(" 篇论文。\n\n")
                .append("**论文**\n");
        for (int i = 0; i < sources.size(); i++) {
            markdown.append(i + 1).append(". 《").append(displayTitle(sources.get(i))).append("》\n");
        }
        markdown.append("\n**限制**\n这是当前可访问论文库列表，不是全网论文推荐。");
        writeFocus(conversationId, new FocusState(
                Intent.LIBRARY_SEARCH.name(),
                sources.stream().map(PaperSource::paperId).toList(),
                sources.stream().map(this::displayTitle).toList()
        ));
        return new AnswerResult(
                markdown.toString(),
                Map.of(),
                Intent.LIBRARY_SEARCH,
                0,
                sources.size(),
                false,
                diagnostics(Intent.LIBRARY_SEARCH, ScopeMode.AUTO_SOURCE, safeLedger.diagnostics(), 0, sources.size(),
                        plannerRounds, attemptedQueries, false)
        );
    }

    private AnswerResult renderRecommendationFromLedger(String conversationId,
                                                        String userMessage,
                                                        EvidenceLedger ledger,
                                                        int plannerRounds,
                                                        List<String> attemptedQueries) {
        EvidenceLedger safeLedger = ledger == null ? EvidenceLedger.empty() : ledger;
        Map<String, com.yizhaoqi.smartpai.service.EvidenceItem> firstEvidenceByPaper = new LinkedHashMap<>();
        for (com.yizhaoqi.smartpai.service.EvidenceItem item : safeLedger.evidence()) {
            if (item.paperId() == null || item.paperId().isBlank()) {
                continue;
            }
            firstEvidenceByPaper.putIfAbsent(item.paperId(), item);
        }
        if (firstEvidenceByPaper.isEmpty()) {
            writeFocus(conversationId, new FocusState(Intent.LIBRARY_SEARCH.name(), List.of(), List.of()));
            return new AnswerResult(
                    "**结论**\n当前可访问论文库中，我没有找到与「" + userMessage + "」足够相关的论文。\n\n"
                            + "**限制**\n推荐只基于当前已上传且你有权限访问的论文库。",
                    Map.of(),
                    Intent.LIBRARY_SEARCH,
                    0,
                    0,
                    false,
                    diagnostics(Intent.LIBRARY_SEARCH, ScopeMode.AUTO_SOURCE, safeLedger.diagnostics(), 0, 0,
                            plannerRounds, attemptedQueries, false)
            );
        }

        StringBuilder markdown = new StringBuilder("**结论**\n");
        int paperCount = firstEvidenceByPaper.size();
        List<com.yizhaoqi.smartpai.service.EvidenceItem> displayedRecommendations = firstEvidenceByPaper.values().stream()
                .limit(MAX_RECOMMENDED_PAPERS)
                .toList();
        if (paperCount == 1) {
            markdown.append("当前可访问论文库中，我只找到 1 篇与「").append(userMessage).append("」相关的论文。\n\n");
        } else if (paperCount > MAX_RECOMMENDED_PAPERS) {
            markdown.append("当前可访问论文库中，我找到 ").append(paperCount)
                    .append(" 篇与「").append(userMessage).append("」相关的论文，先列出相关性最高的 ")
                    .append(MAX_RECOMMENDED_PAPERS)
                    .append(" 篇。\n\n");
        } else {
            markdown.append("当前可访问论文库中，我找到 ").append(paperCount)
                    .append(" 篇与「").append(userMessage).append("」相关的论文。\n\n");
        }
        markdown.append("**推荐**\n");
        Map<Integer, ChatHandler.ReferenceInfo> references = new LinkedHashMap<>();
        int referenceNumber = 1;
        for (com.yizhaoqi.smartpai.service.EvidenceItem item : displayedRecommendations) {
            markdown.append(referenceNumber)
                    .append(". 《").append(displayTitle(item)).append("》\n")
                    .append("   这篇论文与该问题相关，主要证据是：")
                    .append(shortText(recommendationEvidenceText(item), 96))
                    .append("。[")
                    .append(referenceNumber)
                    .append("]\n");
            references.put(referenceNumber, toReferenceInfo(item));
            referenceNumber++;
        }
        markdown.append("\n**限制**\n推荐只基于当前已上传且你有权限访问的论文库，不是全网论文推荐。");
        writeFocus(conversationId, new FocusState(
                Intent.LIBRARY_SEARCH.name(),
                displayedRecommendations.stream().map(com.yizhaoqi.smartpai.service.EvidenceItem::paperId).toList(),
                displayedRecommendations.stream().map(this::displayTitle).toList()
        ));
        return new AnswerResult(
                markdown.toString(),
                references,
                Intent.LIBRARY_SEARCH,
                safeLedger.evidence().size(),
                paperCount,
                false,
                diagnostics(Intent.LIBRARY_SEARCH, ScopeMode.AUTO_SOURCE, safeLedger.diagnostics(), safeLedger.evidence().size(), paperCount,
                        plannerRounds, attemptedQueries, false)
        );
    }

    private String recommendationEvidenceText(com.yizhaoqi.smartpai.service.EvidenceItem item) {
        String text = item == null ? "" : item.matchedText();
        String cleaned = text == null ? "" : text
                .replaceAll("(?i)\\s+filename:\\s*\\S+", " ")
                .replaceAll("(?i)^title\\s*:\\s*", "题名：")
                .replaceAll("(?i)\\s+abstract\\s*:\\s*", " 摘要：")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isBlank() ? (item == null ? "" : displayTitle(item)) : cleaned;
    }

    private AnswerResult answerFromHarnessLedger(String userId,
                                                String conversationId,
                                                String userMessage,
                                                Intent responseIntent,
                                                SourceScope sourceScope,
                                                EvidenceLedger ledger,
                                                int plannerRounds,
                                                List<String> attemptedQueries) {
        EvidenceLedger safeLedger = ledger == null ? EvidenceLedger.empty() : ledger;
        ScopeMode responseScopeMode = scopeMode(sourceScope, responseIntent);
        if (safeLedger.evidence().isEmpty()) {
            return new AnswerResult(
                    "我没有找到足够可靠的论文证据来回答这个问题。",
                    Map.of(),
                    responseIntent,
                    0,
                    0,
                    false,
                    diagnostics(responseIntent, responseScopeMode, safeLedger.diagnostics(), 0, 0,
                            plannerRounds, attemptedQueries, false)
            );
        }

        EvidenceAnswerGenerator.GeneratedAnswer generated = evidenceAnswerGenerator.generate(userId, userMessage, safeLedger);
        if (generated.valid()) {
            RenderedAnswer rendered = renderGeneratedAnswer(generated.rawMarkdown(), safeLedger);
            if (rendered != null) {
                writeFocus(conversationId, new FocusState(
                        responseIntent.name(),
                        safeLedger.sourceSet().stream().map(PaperSource::paperId).distinct().toList(),
                        safeLedger.sourceSet().stream().map(PaperSource::paperTitle).distinct().toList()
                ));
                return new AnswerResult(
                        rendered.markdown(),
                        rendered.references(),
                        responseIntent,
                        safeLedger.evidence().size(),
                        safeLedger.sourceSet().size(),
                        false,
                        diagnostics(responseIntent, responseScopeMode, safeLedger.diagnostics(),
                                safeLedger.evidence().size(), safeLedger.sourceSet().size(),
                                plannerRounds, attemptedQueries, false)
                );
            }
            logger.warn("harness answer render failed: userId={}, conversationId={}, intent={}, evidenceCount={}, sourceCount={}, verifierReason={}",
                    userId,
                    conversationId,
                    responseIntent,
                    safeLedger.evidence().size(),
                    safeLedger.sourceSet().size(),
                    generated.verifierReason());
        } else {
            logger.warn("harness answer verification failed: userId={}, conversationId={}, intent={}, evidenceCount={}, sourceCount={}, verifierReason={}, rawAnswerLength={}",
                    userId,
                    conversationId,
                    responseIntent,
                    safeLedger.evidence().size(),
                    safeLedger.sourceSet().size(),
                    generated.verifierReason(),
                    generated.rawMarkdown() == null ? 0 : generated.rawMarkdown().length());
        }
        return answerGenerationFailure(
                responseIntent,
                responseScopeMode,
                safeLedger.diagnostics(),
                plannerRounds,
                attemptedQueries
        );
    }

    private EvidenceLedger referenceSeedLedger(AnswerScope scope) {
        if (scope == null || !scope.hasReferenceSeed()) {
            return EvidenceLedger.empty();
        }
        return evidenceLedgerService.fromSearchResults(List.of(scope.toSearchResult()), RetrievalBudget.forQa());
    }

    private SourceScope sourceScopeFor(Intent intent, AnswerScope scope, FocusState focus, String userMessage) {
        RetrievalBudgetProfile budgetProfile = budgetProfile(scope);
        if (intent == Intent.REFERENCE_QA) {
            return SourceScope.reference(referenceNumberFor(scope, userMessage),
                    scope == null ? null : scope.conversationRecordId(),
                    scope == null ? List.of() : scope.paperIds(),
                    budgetProfile);
        }
        if (scope != null && !scope.paperIds().isEmpty()) {
            return SourceScope.manual(scope.paperIds(), budgetProfile);
        }
        if (intent == Intent.MANUAL_SOURCE_QA && focus != null && !focus.lastPaperIds().isEmpty()) {
            return SourceScope.manual(focus.lastPaperIds(), budgetProfile);
        }
        return SourceScope.auto(budgetProfile);
    }

    private RetrievalBudgetProfile budgetProfile(AnswerScope scope) {
        return scope == null ? RetrievalBudgetProfile.INTERACTIVE : scope.retrievalBudgetProfile();
    }

    private Integer referenceNumberFor(AnswerScope scope, String userMessage) {
        if (scope != null && scope.referenceNumber() != null) {
            return scope.referenceNumber();
        }
        Matcher matcher = USER_REFERENCE_PATTERN.matcher(userMessage == null ? "" : userMessage);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private EvidenceLedger mergeLedgers(EvidenceLedger current, EvidenceLedger next) {
        EvidenceLedger safeCurrent = current == null ? EvidenceLedger.empty() : current;
        EvidenceLedger safeNext = next == null ? EvidenceLedger.empty() : next;
        if (safeCurrent.evidence().isEmpty() && safeCurrent.sourceSet().isEmpty()) {
            return safeNext;
        }
        if (safeNext.evidence().isEmpty() && safeNext.sourceSet().isEmpty()) {
            return safeCurrent;
        }
        Map<String, PaperSource> sources = new LinkedHashMap<>();
        safeCurrent.sourceSet().forEach(source -> sources.putIfAbsent(source.paperId(), source));
        safeNext.sourceSet().forEach(source -> sources.putIfAbsent(source.paperId(), source));
        Map<String, com.yizhaoqi.smartpai.service.EvidenceItem> evidenceByKey = new LinkedHashMap<>();
        safeCurrent.evidence().forEach(item -> evidenceByKey.putIfAbsent(evidenceKey(item), item));
        safeNext.evidence().forEach(item -> evidenceByKey.putIfAbsent(evidenceKey(item), item));
        List<com.yizhaoqi.smartpai.service.EvidenceItem> renumbered = new ArrayList<>();
        int index = 1;
        for (com.yizhaoqi.smartpai.service.EvidenceItem item : evidenceByKey.values()) {
            renumbered.add(new com.yizhaoqi.smartpai.service.EvidenceItem(
                    "E" + index++,
                    item.paperId(),
                    item.paperTitle(),
                    item.originalFilename(),
                    item.pageNumber(),
                    item.chunkId(),
                    item.sourceKind(),
                    item.sectionTitle(),
                    item.matchedText(),
                    item.bboxJson(),
                    item.score(),
                    item.sourceType(),
                    item.evidenceAssetLevel(),
                    item.pdfEvidenceAvailable(),
                    item.pageScreenshotAvailable(),
                    item.figureScreenshotAvailable(),
                    item.assetWarnings(),
                    item.tableId(),
                    item.figureId(),
                    item.formulaId(),
                    item.evidenceRole(),
                    item.tableText(),
                    item.tableMarkdown(),
                    item.tableScreenshotAvailable()
            ));
        }
        LedgerDiagnostics diagnostics = new LedgerDiagnostics(
                safeCurrent.diagnostics().scannedCount() + safeNext.diagnostics().scannedCount(),
                renumbered.size(),
                sources.size(),
                safeNext.diagnostics().stopReason()
        );
        return new EvidenceLedger(List.copyOf(sources.values()), renumbered, diagnostics);
    }

    private String evidenceKey(com.yizhaoqi.smartpai.service.EvidenceItem item) {
        return item.paperId() + ":" + item.chunkId() + ":" + shortText(item.matchedText(), 64);
    }

    private AnswerResult answerReferenceQa(String conversationId,
                                           String userId,
                                           String userMessage,
                                           SearchResult seed) {
        if (!EvidenceQuality.isUsable(seed, 0.3d)) {
            return clarifyMissingReference();
        }
        List<EvidenceItem> ledger = List.of(toEvidenceItem("E1", seed));
        try {
            LlmProviderRouter.ReActTurn turn = llmProviderRouter.completeReActTurn(
                    userId,
                    buildEvidenceMessages(userMessage, ledger),
                    List.of(),
                    1200
            );
            RenderedAnswer rendered = renderVerifiedAnswer(turn.content(), ledger);
            if (rendered != null) {
                writeFocus(conversationId, new FocusState(
                        Intent.REFERENCE_QA.name(),
                        ledger.stream().map(EvidenceItem::paperId).distinct().toList(),
                        ledger.stream().map(EvidenceItem::paperTitle).distinct().toList()
                ));
                return new AnswerResult(rendered.markdown(), rendered.references(), Intent.REFERENCE_QA,
                        ledger.size(), distinctPaperCount(ledger), false);
            }
        } catch (Exception exception) {
            logger.warn("reference answer generation failed: userId={}, conversationId={}", userId, conversationId, exception);
        }
        return answerGenerationFailure(
                Intent.REFERENCE_QA,
                ScopeMode.REFERENCE_SOURCE,
                new LedgerDiagnostics(ledger.size(), ledger.size(), distinctPaperCount(ledger), "GENERATION_FAILED"),
                0,
                List.of()
        );
    }

    private AnswerResult answerQa(String userId,
                                  String conversationId,
                                  String userMessage,
                                  FocusState focus,
                                  Intent responseIntent) {
        String query = scopedQuery(userMessage, focus);
        RetrievalBudget budget = RetrievalBudget.forQa();
        List<String> scopePaperIds = focus == null ? List.of() : focus.lastPaperIds();
        PaperRetrievalService.RetrievalResult retrieval = paperRetrievalService.retrieve(query, userId, budget, scopePaperIds);
        List<SearchResult> results = retrieval.results();
        if (focus != null && focus.lastPaperIds().size() == 1) {
            String paperId = focus.lastPaperIds().get(0);
            results = results.stream()
                    .filter(result -> paperId.equals(result.getPaperId()))
                    .toList();
        } else if (focus != null && !focus.lastPaperIds().isEmpty()) {
            Set<String> paperIds = new LinkedHashSet<>(focus.lastPaperIds());
            results = results.stream()
                    .filter(result -> paperIds.contains(result.getPaperId()))
                    .toList();
        }
        EvidenceLedger ledger = evidenceLedgerService.fromSearchResults(results, budget);
        if (ledger.evidence().isEmpty()) {
            return new AnswerResult(
                    "我没有找到足够可靠的论文证据来回答这个问题。",
                    Map.of(),
                    responseIntent,
                    0,
                    0,
                    false,
                    diagnostics(responseIntent, scopeMode(focus, responseIntent), retrieval.diagnostics(), 0, 0)
            );
        }

        EvidenceAnswerGenerator.GeneratedAnswer generated = evidenceAnswerGenerator.generate(userId, userMessage, ledger);
        if (generated.valid()) {
            RenderedAnswer rendered = renderGeneratedAnswer(generated.rawMarkdown(), ledger);
            if (rendered != null) {
                writeFocus(conversationId, new FocusState(
                        responseIntent.name(),
                        ledger.sourceSet().stream().map(PaperSource::paperId).distinct().toList(),
                        ledger.sourceSet().stream().map(PaperSource::paperTitle).distinct().toList()
                ));
                return new AnswerResult(rendered.markdown(), rendered.references(), responseIntent,
                        ledger.evidence().size(), ledger.sourceSet().size(), false,
                        diagnostics(responseIntent, scopeMode(focus, responseIntent), retrieval.diagnostics(),
                        ledger.evidence().size(), ledger.sourceSet().size()));
            }
            logger.warn("legacy harness answer render failed: userId={}, conversationId={}, intent={}, evidenceCount={}, sourceCount={}, verifierReason={}",
                    userId,
                    conversationId,
                    responseIntent,
                    ledger.evidence().size(),
                    ledger.sourceSet().size(),
                    generated.verifierReason());
        } else {
            logger.warn("legacy harness answer verification failed: userId={}, conversationId={}, intent={}, evidenceCount={}, sourceCount={}, verifierReason={}, rawAnswerLength={}",
                    userId,
                    conversationId,
                    responseIntent,
                    ledger.evidence().size(),
                    ledger.sourceSet().size(),
                    generated.verifierReason(),
                    generated.rawMarkdown() == null ? 0 : generated.rawMarkdown().length());
        }
        return answerGenerationFailure(
                responseIntent,
                scopeMode(focus, responseIntent),
                ledger.diagnostics(),
                0,
                List.of()
        );
    }

    private AnswerResult answerGenerationFailure(Intent responseIntent,
                                                 ScopeMode responseScopeMode,
                                                 LedgerDiagnostics ledgerDiagnostics,
                                                 int plannerRounds,
                                                 List<String> attemptedQueries) {
        return new AnswerResult(
                "我找到了论文证据，但这次没有生成可验证的答案。请重试，或者缩小问题范围后再问。",
                Map.of(),
                responseIntent,
                0,
                0,
                false,
                diagnostics(responseIntent, responseScopeMode, ledgerDiagnostics, 0, 0,
                        plannerRounds, attemptedQueries, false)
        );
    }

    private List<Map<String, Object>> buildEvidenceMessages(String userMessage, List<EvidenceItem> ledger) {
        StringBuilder system = new StringBuilder()
                .append("你是 PaperLoom 论文阅读助手。只能基于给定 evidence 回答。\n")
                .append("引用只能写 {{E1}}、{{E2}} 这种 token，禁止写 [1]、来源#1、paperId、chunk 或 References 列表。\n")
                .append("不要编造论文标题；如果要写论文标题，只能写 evidence 中出现的 paperTitle。\n")
                .append("Evidence 已按可信度从高到低排序，优先使用编号小的 evidence。\n")
                .append("回答使用 **结论** / **依据** / **限制**，每条依据最多 1-2 个 evidence token。\n\n");
        for (EvidenceItem item : ledger) {
            system.append(item.evidenceId()).append("\n")
                    .append("paperTitle: ").append(item.paperTitle()).append("\n");
            if (item.pageNumber() != null) {
                system.append("page: ").append(item.pageNumber()).append("\n");
            }
            system.append("sourceKind: ").append(item.sourceKind()).append("\n")
                    .append("matchedText: ").append(shortText(item.matchedText(), 900)).append("\n\n");
        }
        return List.of(
                Map.of("role", "system", "content", system.toString()),
                Map.of("role", "user", "content", userMessage)
        );
    }

    private RenderedAnswer renderVerifiedAnswer(String rawAnswer, List<EvidenceItem> ledger) {
        if (rawAnswer == null || rawAnswer.isBlank()
                || BRACKET_CITATION_PATTERN.matcher(rawAnswer).find()
                || LEGACY_SOURCE_PATTERN.matcher(rawAnswer).find()
                || mentionsUnknownQuotedTitle(rawAnswer, ledger)) {
            return null;
        }

        Map<String, EvidenceItem> byId = ledger.stream()
                .collect(Collectors.toMap(EvidenceItem::evidenceId, item -> item, (left, ignored) -> left, LinkedHashMap::new));
        Matcher matcher = EVIDENCE_TOKEN_PATTERN.matcher(rawAnswer);
        Set<String> usedEvidenceIds = new LinkedHashSet<>();
        while (matcher.find()) {
            String evidenceId = "E" + matcher.group(1);
            EvidenceItem evidence = byId.get(evidenceId);
            if (evidence == null || !EvidenceQuality.isUsable(evidence.result(), 0.3d)) {
                return null;
            }
            usedEvidenceIds.add(evidenceId);
        }
        if (usedEvidenceIds.isEmpty()) {
            return null;
        }
        LinkedHashMap<String, Integer> evidenceToReference = new LinkedHashMap<>();
        for (EvidenceItem item : ledger) {
            if (usedEvidenceIds.contains(item.evidenceId())) {
                evidenceToReference.put(item.evidenceId(), evidenceToReference.size() + 1);
            }
        }

        matcher = EVIDENCE_TOKEN_PATTERN.matcher(rawAnswer);
        StringBuilder markdown = new StringBuilder();
        Map<Integer, ChatHandler.ReferenceInfo> references = new LinkedHashMap<>();
        while (matcher.find()) {
            String evidenceId = "E" + matcher.group(1);
            matcher.appendReplacement(markdown, "[" + evidenceToReference.get(evidenceId) + "]");
        }
        matcher.appendTail(markdown);
        for (Map.Entry<String, Integer> entry : evidenceToReference.entrySet()) {
            EvidenceItem item = byId.get(entry.getKey());
            references.put(entry.getValue(), toReferenceInfo(item.result(), item.matchedText()));
        }
        return new RenderedAnswer(markdown.toString(), references);
    }

    private RenderedAnswer renderGeneratedAnswer(String rawAnswer, EvidenceLedger ledger) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return null;
        }
        Map<String, com.yizhaoqi.smartpai.service.EvidenceItem> byId = ledger.evidence().stream()
                .collect(Collectors.toMap(
                        com.yizhaoqi.smartpai.service.EvidenceItem::evidenceId,
                        item -> item,
                        (left, ignored) -> left,
                        LinkedHashMap::new
                ));
        Matcher matcher = EVIDENCE_TOKEN_PATTERN.matcher(rawAnswer);
        Set<String> usedEvidenceIds = new LinkedHashSet<>();
        while (matcher.find()) {
            String evidenceId = "E" + matcher.group(1);
            if (!byId.containsKey(evidenceId)) {
                return null;
            }
            usedEvidenceIds.add(evidenceId);
        }
        if (usedEvidenceIds.isEmpty()) {
            return null;
        }
        LinkedHashMap<String, Integer> evidenceToReference = new LinkedHashMap<>();
        for (com.yizhaoqi.smartpai.service.EvidenceItem item : ledger.evidence()) {
            if (usedEvidenceIds.contains(item.evidenceId())) {
                evidenceToReference.put(item.evidenceId(), evidenceToReference.size() + 1);
            }
        }
        matcher = EVIDENCE_TOKEN_PATTERN.matcher(rawAnswer);
        StringBuilder markdown = new StringBuilder();
        Map<Integer, ChatHandler.ReferenceInfo> references = new LinkedHashMap<>();
        while (matcher.find()) {
            String evidenceId = "E" + matcher.group(1);
            matcher.appendReplacement(markdown, Matcher.quoteReplacement(
                    citationReplacement(byId.get(evidenceId), evidenceToReference.get(evidenceId), rawAnswer)
            ));
        }
        matcher.appendTail(markdown);
        for (Map.Entry<String, Integer> entry : evidenceToReference.entrySet()) {
            references.put(entry.getValue(), toReferenceInfo(byId.get(entry.getKey())));
        }
        return new RenderedAnswer(markdown.toString(), references);
    }

    private String citationReplacement(com.yizhaoqi.smartpai.service.EvidenceItem item, Integer referenceNumber, String rawAnswer) {
        String citation = "[" + referenceNumber + "]";
        String context = importantSectionContext(item, rawAnswer);
        if (context.isBlank()) {
            return citation;
        }
        return "（" + context + "）" + citation;
    }

    private String importantSectionContext(com.yizhaoqi.smartpai.service.EvidenceItem item, String rawAnswer) {
        if (item == null || item.sectionTitle() == null || item.sectionTitle().isBlank()) {
            return "";
        }
        String sectionTitle = shortText(item.sectionTitle(), 120);
        if (rawAnswer != null && rawAnswer.toLowerCase(Locale.ROOT).contains(sectionTitle.toLowerCase(Locale.ROOT))) {
            return "";
        }
        if (!isImportantSectionTitle(sectionTitle, item.sourceKind())) {
            return "";
        }
        return sectionTitle;
    }

    private boolean isImportantSectionTitle(String sectionTitle, String sourceKind) {
        if ("TABLE".equalsIgnoreCase(sourceKind) || "FIGURE".equalsIgnoreCase(sourceKind)) {
            return true;
        }
        String lower = sectionTitle == null ? "" : sectionTitle.toLowerCase(Locale.ROOT);
        return lower.matches("^\\d+(?:\\.\\d+)*\\s+.+");
    }

    private AnswerDiagnostics diagnostics(Intent intent,
                                          ScopeMode scopeMode,
                                          PaperRetrievalService.RetrievalDiagnostics retrievalDiagnostics,
                                          int acceptedEvidenceCount,
                                          int sourceCount) {
        return new AnswerDiagnostics(
                intent.name(),
                scopeMode.name(),
                retrievalDiagnostics == null ? 0 : retrievalDiagnostics.scannedCount(),
                acceptedEvidenceCount,
                sourceCount,
                retrievalDiagnostics == null ? PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE.name()
                        : retrievalDiagnostics.stopReason().name()
        );
    }

    private AnswerDiagnostics diagnostics(Intent intent,
                                          ScopeMode scopeMode,
                                          LedgerDiagnostics ledgerDiagnostics,
                                          int acceptedEvidenceCount,
                                          int sourceCount) {
        return diagnostics(intent, scopeMode, ledgerDiagnostics, acceptedEvidenceCount, sourceCount, 0, List.of(), false);
    }

    private AnswerDiagnostics diagnostics(Intent intent,
                                          ScopeMode scopeMode,
                                          LedgerDiagnostics ledgerDiagnostics,
                                          int acceptedEvidenceCount,
                                          int sourceCount,
                                          int plannerRounds,
                                          List<String> attemptedQueries,
                                          boolean fallbackUsed) {
        return new AnswerDiagnostics(
                intent.name(),
                scopeMode.name(),
                ledgerDiagnostics == null ? 0 : ledgerDiagnostics.scannedCount(),
                acceptedEvidenceCount,
                sourceCount,
                ledgerDiagnostics == null ? PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE.name()
                        : ledgerDiagnostics.stopReason(),
                plannerRounds,
                attemptedQueries,
                fallbackUsed
        );
    }

    private ScopeMode scopeMode(FocusState focus, Intent intent) {
        if (intent == Intent.REFERENCE_QA) {
            return ScopeMode.REFERENCE_SOURCE;
        }
        if (focus != null && !focus.lastPaperIds().isEmpty()) {
            return ScopeMode.MANUAL_SOURCE;
        }
        return ScopeMode.AUTO_SOURCE;
    }

    private ScopeMode scopeMode(SourceScope sourceScope, Intent intent) {
        if (intent == Intent.REFERENCE_QA) {
            return ScopeMode.REFERENCE_SOURCE;
        }
        if (sourceScope != null && sourceScope.mode() == com.yizhaoqi.smartpai.service.ScopeMode.MANUAL_SOURCE) {
            return ScopeMode.MANUAL_SOURCE;
        }
        return ScopeMode.AUTO_SOURCE;
    }

    private boolean mentionsUnknownQuotedTitle(String rawAnswer, List<EvidenceItem> ledger) {
        Set<String> allowedTitles = ledger.stream()
                .map(EvidenceItem::paperTitle)
                .filter(title -> title != null && !title.isBlank())
                .collect(Collectors.toSet());
        Matcher matcher = CHINESE_TITLE_PATTERN.matcher(rawAnswer);
        while (matcher.find()) {
            if (!allowedTitles.contains(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private AnswerResult clarify() {
        return new AnswerResult("你想讲哪一篇？可以点上一条推荐里的编号，或者直接说论文标题。",
                Map.of(), Intent.CLARIFY, 0, 0, false);
    }

    private AnswerResult clarifyMissingReference() {
        return new AnswerResult("我找不到这个引用对应的证据。请点击回答里的 citation，或指定论文后再问。",
                Map.of(), Intent.CLARIFY, 0, 0, false);
    }

    private List<EvidenceItem> buildQaLedger(List<SearchResult> results, RetrievalBudget budget) {
        List<EvidenceItem> ledger = new ArrayList<>();
        int tokenEstimate = 0;
        for (SearchResult result : results == null ? List.<SearchResult>of() : results) {
            if (result.getPaperId() == null
                    || result.getChunkId() == null
                    || !EvidenceQuality.isUsable(result, budget.minScore())) {
                continue;
            }
            int nextTokens = estimateTokens(EvidenceQuality.bestEvidenceText(result));
            if (!ledger.isEmpty() && tokenEstimate + nextTokens > budget.contextTokenBudget()) {
                break;
            }
            ledger.add(toEvidenceItem("E" + (ledger.size() + 1), result));
            tokenEstimate += nextTokens;
        }
        return ledger;
    }

    private EvidenceItem toEvidenceItem(String evidenceId, SearchResult result) {
        String matchedText = result.getMatchedChunkText() != null && !result.getMatchedChunkText().isBlank()
                ? result.getMatchedChunkText()
                : result.getTextContent();
        return new EvidenceItem(
                evidenceId,
                result.getPaperId(),
                displayTitle(result),
                result.getOriginalFilename(),
                result.getPageNumber(),
                result.getChunkId(),
                result.getSourceKind() == null || result.getSourceKind().isBlank() ? "TEXT" : result.getSourceKind(),
                matchedText == null ? "" : matchedText,
                result.getBboxJson(),
                result.getScore(),
                result
        );
    }

    private ChatHandler.ReferenceInfo toReferenceInfo(SearchResult result, String matchedText) {
        return new ChatHandler.ReferenceInfo(
                result.getPaperId(),
                displayTitle(result),
                result.getOriginalFilename(),
                result.getPageNumber(),
                result.getAnchorText(),
                result.getRetrievalMode(),
                retrievalLabel(result.getRetrievalMode()),
                result.getRetrievalQuery() == null ? result.getOriginalQuery() : result.getRetrievalQuery(),
                matchedText,
                shortText(matchedText, 160),
                result.getScore(),
                result.getChunkId(),
                result.getElementType(),
                result.getSectionTitle(),
                result.getSectionLevel(),
                result.getBboxJson(),
                result.getParserName(),
                result.getParserVersion(),
                result.getSourceKind(),
                result.getTableId(),
                result.getFigureId(),
                result.getFormulaId(),
                result.getEvidenceRole(),
                result.getRetrievalRoute(),
                result.getIntent(),
                result.getRankReason(),
                result.getTableText(),
                result.getTableMarkdown(),
                result.getTableScreenshotAvailable(),
                result.getSourceType(),
                result.getEvidenceAssetLevel(),
                result.getPdfEvidenceAvailable(),
                result.getPageScreenshotAvailable(),
                result.getFigureScreenshotAvailable(),
                result.getAssetWarnings()
        );
    }

    private ChatHandler.ReferenceInfo toReferenceInfo(com.yizhaoqi.smartpai.service.EvidenceItem item) {
        return new ChatHandler.ReferenceInfo(
                item.paperId(),
                item.paperTitle(),
                item.originalFilename(),
                item.pageNumber(),
                item.matchedText(),
                "HARNESS",
                retrievalLabel("HYBRID"),
                null,
                item.matchedText(),
                shortText(item.matchedText(), 160),
                item.score(),
                item.chunkId(),
                null,
                item.sectionTitle(),
                null,
                item.bboxJson(),
                null,
                null,
                item.sourceKind(),
                item.tableId(),
                item.figureId(),
                item.formulaId(),
                item.evidenceRole(),
                "EVIDENCE_HARNESS",
                null,
                null,
                item.tableText(),
                item.tableMarkdown(),
                item.tableScreenshotAvailable(),
                item.sourceType(),
                item.evidenceAssetLevel(),
                item.pdfEvidenceAvailable(),
                item.pageScreenshotAvailable(),
                item.figureScreenshotAvailable(),
                item.assetWarnings()
        );
    }

    private String retrievalLabel(String retrievalMode) {
        return "HYBRID".equalsIgnoreCase(retrievalMode) || "EXPANDED_HYBRID".equalsIgnoreCase(retrievalMode)
                ? "混合召回（语义相关 + 关键词命中）"
                : retrievalMode;
    }

    private SearchResult resolveReferenceSeed(String userId,
                                              String conversationId,
                                              String userMessage,
                                              AnswerScope scope) {
        if (scope != null && scope.hasReferenceSeed()) {
            return scope.toSearchResult();
        }

        Integer referenceNumber = scope == null ? null : scope.referenceNumber();
        if (referenceNumber == null) {
            Matcher matcher = USER_REFERENCE_PATTERN.matcher(userMessage == null ? "" : userMessage);
            if (matcher.find()) {
                referenceNumber = Integer.parseInt(matcher.group(1));
            }
        }
        if (referenceNumber == null || conversationService == null) {
            return null;
        }

        Long parsedUserId = parseLong(userId);
        if (parsedUserId == null) {
            return null;
        }
        if (scope != null && scope.conversationRecordId() != null) {
            return conversationService.findReferenceDetail(parsedUserId, scope.conversationRecordId(), referenceNumber)
                    .map(this::referenceDetailToSearchResult)
                    .orElse(null);
        }
        return conversationService.findLatestReferenceDetail(parsedUserId, conversationId, referenceNumber)
                .map(this::referenceDetailToSearchResult)
                .orElse(null);
    }

    private FocusState toFocus(AnswerScope scope) {
        if (scope == null || scope.paperIds().isEmpty()) {
            return null;
        }
        return new FocusState(Intent.MANUAL_SOURCE_QA.name(), scope.paperIds(), scope.paperTitles());
    }

    private FocusState readFocusFromHistory(String userId, String conversationId) {
        Long parsedUserId = parseLong(userId);
        if (parsedUserId == null || conversationService == null) {
            return null;
        }
        return conversationService.findLatestReferenceFocus(parsedUserId, conversationId)
                .map(this::referenceMappingsToFocus)
                .orElse(null);
    }

    private FocusState referenceMappingsToFocus(Map<String, Object> rawMappings) {
        List<String> paperIds = new ArrayList<>();
        List<String> paperTitles = new ArrayList<>();
        for (Object rawDetail : rawMappings.values()) {
            if (!(rawDetail instanceof Map<?, ?> detail)) {
                continue;
            }
            String paperId = stringValue(detail.get("paperId"));
            if (paperId == null || paperId.isBlank() || paperIds.contains(paperId)) {
                continue;
            }
            paperIds.add(paperId);
            String title = stringValue(firstPresentObjectMap(detail, "paperTitle", "originalFilename"));
            paperTitles.add(title == null ? paperId : title);
        }
        if (paperIds.isEmpty()) {
            return null;
        }
        return new FocusState(Intent.MANUAL_SOURCE_QA.name(), paperIds, paperTitles);
    }

    private SearchResult referenceDetailToSearchResult(Map<String, Object> detail) {
        SearchResult result = new SearchResult(
                stringValue(detail.get("paperId")),
                integerValue(detail.get("chunkId")),
                stringValue(firstPresent(detail, "matchedChunkText", "evidenceSnippet", "anchorText")),
                doubleValue(detail.get("score")),
                null,
                null,
                false,
                stringValue(detail.get("paperTitle")),
                stringValue(detail.get("originalFilename")),
                integerValue(detail.get("pageNumber")),
                stringValue(detail.get("anchorText")),
                stringValue(detail.getOrDefault("retrievalMode", "REFERENCE")),
                stringValue(firstPresent(detail, "matchedChunkText", "evidenceSnippet", "anchorText")),
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
        result.setSourceType(stringValue(detail.get("sourceType")));
        result.setEvidenceAssetLevel(stringValue(detail.get("evidenceAssetLevel")));
        result.setPdfEvidenceAvailable(booleanValue(detail.get("pdfEvidenceAvailable")));
        result.setPageScreenshotAvailable(booleanValue(detail.get("pageScreenshotAvailable")));
        result.setFigureScreenshotAvailable(booleanValue(detail.get("figureScreenshotAvailable")));
        result.setAssetWarnings(stringListValue(detail.get("assetWarnings")));
        result.setRetrievalRoute("REFERENCE_SOURCE");
        result.setIntent(Intent.REFERENCE_QA.name());
        return result;
    }

    private String scopedQuery(String userMessage, FocusState focus) {
        if (focus != null && focus.lastPaperTitles().size() == 1) {
            return focus.lastPaperTitles().get(0) + " " + userMessage;
        }
        return userMessage;
    }

    private FocusState readFocus(String conversationId) {
        if (redisTemplate == null || conversationId == null || conversationId.isBlank()) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(focusKey(conversationId));
            if (json == null || json.isBlank()) {
                return null;
            }
            Map<String, Object> value = objectMapper.readValue(json, new TypeReference<>() {});
            return new FocusState(
                    String.valueOf(value.getOrDefault("lastIntent", "")),
                    stringList(value.get("lastPaperIds")),
                    stringList(value.get("lastPaperTitles"))
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeFocus(String conversationId, FocusState focus) {
        if (redisTemplate == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "lastIntent", focus.lastIntent(),
                    "lastPaperIds", focus.lastPaperIds(),
                    "lastPaperTitles", focus.lastPaperTitles()
            ));
            redisTemplate.opsForValue().set(focusKey(conversationId), json, FOCUS_TTL);
        } catch (Exception ignored) {
            // Focus is helpful, not required for correctness.
        }
    }

    private String focusKey(String conversationId) {
        return "paperloom:chat:focus:" + conversationId;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private Long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private Object firstPresentObjectMap(Map<?, ?> map, String... keys) {
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

    private List<String> stringListValue(Object raw) {
        if (!(raw instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
                .filter(value -> value != null && !String.valueOf(value).isBlank())
                .map(String::valueOf)
                .toList();
    }

    private int distinctPaperCount(List<EvidenceItem> ledger) {
        return (int) ledger.stream().map(EvidenceItem::paperId).distinct().count();
    }

    private String displayTitle(SearchResult result) {
        if (result == null) {
            return "";
        }
        if (result.getPaperTitle() != null && !result.getPaperTitle().isBlank()) {
            return result.getPaperTitle();
        }
        return result.getOriginalFilename() == null ? "" : result.getOriginalFilename();
    }

    private String displayTitle(PaperSource source) {
        if (source == null) {
            return "";
        }
        if (source.paperTitle() != null && !source.paperTitle().isBlank()) {
            return source.paperTitle();
        }
        return source.originalFilename() == null ? "" : source.originalFilename();
    }

    private String displayTitle(com.yizhaoqi.smartpai.service.EvidenceItem item) {
        if (item == null) {
            return "";
        }
        if (item.paperTitle() != null && !item.paperTitle().isBlank()) {
            return item.paperTitle();
        }
        return item.originalFilename() == null ? "" : item.originalFilename();
    }

    private String shortText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= maxChars) {
            return cleaned;
        }
        return cleaned.substring(0, maxChars) + "...";
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    public enum Intent {
        SMALLTALK,
        LIBRARY_STATUS,
        LIBRARY_SEARCH,
        PAPER_QA,
        AUTO_SOURCE_QA,
        MANUAL_SOURCE_QA,
        REFERENCE_QA,
        FOLLOW_UP,
        CLARIFY
    }

    public enum ScopeMode {
        AUTO_SOURCE,
        MANUAL_SOURCE,
        REFERENCE_SOURCE
    }

    public record AnswerResult(
            String markdown,
            Map<Integer, ChatHandler.ReferenceInfo> referenceMappings,
            Intent intent,
            int evidenceCount,
            int uniquePaperCount,
            boolean fallbackUsed,
            AnswerDiagnostics diagnostics
    ) {
        public AnswerResult(String markdown,
                            Map<Integer, ChatHandler.ReferenceInfo> referenceMappings,
                            Intent intent,
                            int evidenceCount,
                            int uniquePaperCount,
                            boolean fallbackUsed) {
            this(
                    markdown,
                    referenceMappings,
                    intent,
                    evidenceCount,
                    uniquePaperCount,
                    fallbackUsed,
                    new AnswerDiagnostics(
                            intent == null ? "UNKNOWN" : intent.name(),
                            ScopeMode.AUTO_SOURCE.name(),
                            evidenceCount,
                            evidenceCount,
                            uniquePaperCount,
                            evidenceCount == 0 ? PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE.name()
                                    : PaperRetrievalService.StopReason.EXHAUSTED.name()
                    )
            );
        }

        public AnswerResult {
            referenceMappings = referenceMappings == null ? Map.of() : referenceMappings;
            diagnostics = diagnostics == null
                    ? new AnswerDiagnostics(
                    intent == null ? "UNKNOWN" : intent.name(),
                    ScopeMode.AUTO_SOURCE.name(),
                    evidenceCount,
                    evidenceCount,
                    uniquePaperCount,
                    evidenceCount == 0 ? PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE.name()
                            : PaperRetrievalService.StopReason.EXHAUSTED.name()
            )
                    : diagnostics;
        }
    }

    public record AnswerDiagnostics(
            String route,
            String scopeMode,
            int scannedCount,
            int acceptedEvidenceCount,
            int sourceCount,
            String stopReason,
            int plannerRounds,
            List<String> attemptedQueries,
            boolean fallbackUsed
    ) {
        public AnswerDiagnostics(String route,
                                 String scopeMode,
                                 int scannedCount,
                                 int acceptedEvidenceCount,
                                 int sourceCount,
                                 String stopReason) {
            this(route, scopeMode, scannedCount, acceptedEvidenceCount, sourceCount, stopReason, 0, List.of(), false);
        }

        public AnswerDiagnostics {
            attemptedQueries = attemptedQueries == null ? List.of() : attemptedQueries.stream()
                    .filter(query -> query != null && !query.isBlank())
                    .distinct()
                    .toList();
        }
    }

    public record AnswerScope(
            List<String> paperIds,
            List<String> paperTitles,
            Integer referenceNumber,
            Long conversationRecordId,
            Integer chunkId,
            Integer pageNumber,
            String paperId,
            String paperTitle,
            String originalFilename,
            String matchedText,
            String bboxJson,
            String sourceKind,
            RetrievalBudgetProfile retrievalBudgetProfile
    ) {
        public AnswerScope(List<String> paperIds,
                           List<String> paperTitles,
                           Integer referenceNumber,
                           Long conversationRecordId,
                           Integer chunkId,
                           Integer pageNumber,
                           String paperId,
                           String paperTitle,
                           String originalFilename,
                           String matchedText,
                           String bboxJson,
                           String sourceKind) {
            this(
                    paperIds,
                    paperTitles,
                    referenceNumber,
                    conversationRecordId,
                    chunkId,
                    pageNumber,
                    paperId,
                    paperTitle,
                    originalFilename,
                    matchedText,
                    bboxJson,
                    sourceKind,
                    RetrievalBudgetProfile.INTERACTIVE
            );
        }

        public AnswerScope {
            paperIds = paperIds == null ? List.of() : paperIds.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
            paperTitles = paperTitles == null ? List.of() : paperTitles.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
            if ((paperTitles == null || paperTitles.isEmpty()) && paperTitle != null && !paperTitle.isBlank()) {
                paperTitles = List.of(paperTitle);
            }
            retrievalBudgetProfile = retrievalBudgetProfile == null
                    ? RetrievalBudgetProfile.INTERACTIVE
                    : retrievalBudgetProfile;
        }

        boolean hasReferenceSeed() {
            return paperId != null && !paperId.isBlank() && matchedText != null && !matchedText.isBlank();
        }

        public AnswerScope withPaperIds(List<String> controlledPaperIds) {
            return new AnswerScope(
                    controlledPaperIds,
                    paperTitles,
                    referenceNumber,
                    conversationRecordId,
                    chunkId,
                    pageNumber,
                    paperId,
                    paperTitle,
                    originalFilename,
                    matchedText,
                    bboxJson,
                    sourceKind,
                    retrievalBudgetProfile
            );
        }

        SearchResult toSearchResult() {
            SearchResult result = new SearchResult(
                    paperId,
                    chunkId,
                    matchedText,
                    null,
                    null,
                    null,
                    false,
                    paperTitle,
                    originalFilename,
                    pageNumber,
                    matchedText,
                    "REFERENCE",
                    matchedText,
                    null,
                    null,
                    null,
                    bboxJson,
                    null,
                    null,
                    sourceKind,
                    null,
                    null,
                    null,
                    false
            );
            result.setRetrievalRoute("REFERENCE_SOURCE");
            result.setIntent(Intent.REFERENCE_QA.name());
            return result;
        }
    }

    record EvidenceItem(
            String evidenceId,
            String paperId,
            String paperTitle,
            String originalFilename,
            Integer pageNumber,
            Integer chunkId,
            String sourceKind,
            String matchedText,
            String bboxJson,
            Double score,
            SearchResult result
    ) {
    }

    private record FocusState(
            String lastIntent,
            List<String> lastPaperIds,
            List<String> lastPaperTitles
    ) {
    }

    private record RenderedAnswer(
            String markdown,
            Map<Integer, ChatHandler.ReferenceInfo> references
    ) {
    }
}
