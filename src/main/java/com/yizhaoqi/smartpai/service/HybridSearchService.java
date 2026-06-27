package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.util.ObjectBuilder;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.config.PaperSearchIndex;
import com.yizhaoqi.smartpai.entity.PaperChunkDocument;
import com.yizhaoqi.smartpai.entity.PaperSearchDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperTable;
import com.yizhaoqi.smartpai.model.PaperVisualAsset;
import com.yizhaoqi.smartpai.repository.PaperTableRepository;
import com.yizhaoqi.smartpai.repository.PaperVisualAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 论文混合检索服务，结合文本匹配和向量相似度搜索。
 */
@Service
public class HybridSearchService {

    static final String KEYWORD_MATCH_FIELD = "retrievalTextContent";

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);
    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private PaperRepository paperRepository;

    @Autowired
    private PaperTableRepository paperTableRepository;

    @Autowired
    private PaperVisualAssetRepository paperVisualAssetRepository;

    /**
     * 自适应检索入口，返回检索结果和本轮扫描/停止原因诊断。
     */
    public AdaptiveSearchResult adaptiveSearchWithPermission(String query, String userId, RetrievalBudget budget) {
        return adaptiveSearchWithPermission(query, userId, budget, List.of());
    }

    public AdaptiveSearchResult adaptiveSearchWithPermission(String query,
                                                             String userId,
                                                             RetrievalBudget budget,
                                                             List<String> scopePaperIds) {
        RetrievalBudget effectiveBudget = budget == null ? RetrievalBudget.forQa() : budget;
        List<String> effectiveScopePaperIds = normalizeScopePaperIds(scopePaperIds);
        logger.debug("开始自适应带权限搜索，查询: {}, 用户ID: {}, batch: {}", query, userId, effectiveBudget.pageBatchSize());

        try {
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            String userDbId = getUserDbId(userId);
            final List<Float> queryVector = embedToVectorList(query, userId, queryEmbeddingTimeout(effectiveBudget));
            if (queryVector == null) {
                logger.warn("向量生成失败，使用自适应纯文本搜索");
                return adaptiveTextOnlySearchWithPermission(query, userDbId, userEffectiveTags, effectiveBudget, effectiveScopePaperIds);
            }

            AdaptiveSearchResult semanticResult = adaptiveSemanticSearchWithPermission(
                    query,
                    userDbId,
                    userEffectiveTags,
                    effectiveBudget,
                    effectiveScopePaperIds,
                    queryVector
            );
            AdaptiveSearchResult keywordResult = adaptiveTextOnlySearchWithPermission(
                    query,
                    userDbId,
                    userEffectiveTags,
                    effectiveBudget,
                    effectiveScopePaperIds
            );
            return fuseAdaptiveResults(semanticResult, keywordResult);
        } catch (Exception e) {
            logger.error("自适应带权限搜索失败", e);
            try {
                return adaptiveTextOnlySearchWithPermission(
                        query,
                        getUserDbId(userId),
                        getUserEffectiveOrgTags(userId),
                        effectiveBudget,
                        effectiveScopePaperIds
                );
            } catch (Exception fallbackError) {
                logger.error("自适应后备搜索也失败", fallbackError);
                return adaptiveResult(Collections.emptyList(), 0, PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE);
            }
        }
    }

    public AdaptiveSearchResult searchPaperCandidatesWithPermission(String query,
                                                                    String userId,
                                                                    RetrievalBudget budget,
                                                                    List<String> scopePaperIds) {
        RetrievalBudget effectiveBudget = budget == null ? RetrievalBudget.forLibrarySearch() : budget;
        List<String> effectiveScopePaperIds = normalizeScopePaperIds(scopePaperIds);
        try {
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            String userDbId = getUserDbId(userId);
            SearchRequest request = SearchRequest.of(s -> s
                            .index(PaperSearchIndex.PAPER_INDEX_NAME)
                            .size(effectiveBudget.pageBatchSize())
                            .query(q -> q.bool(b -> b
                                    .must(m -> m.match(ma -> ma.field("searchText").query(query)))
                                    .filter(f -> permissionFilter(f, userDbId, userEffectiveTags))
                                    .filter(f -> paperScopeFilter(f, effectiveScopePaperIds))
                            ))
                            .minScore(effectiveBudget.minScore())
            );
            SearchResponse<PaperSearchDocument> response = esClient.search(request, PaperSearchDocument.class);
            List<SearchResult> candidates = response.hits().hits().stream()
                    .map(hit -> toPaperCandidateResult(hit.source(), hit.score()))
                    .filter(result -> result.getPaperId() != null && !result.getPaperId().isBlank())
                    .toList();
            PaperRetrievalService.StopReason stopReason = candidates.isEmpty()
                    ? PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE
                    : PaperRetrievalService.StopReason.EXHAUSTED;
            return adaptiveResult(candidates, response.hits().hits().size(), stopReason);
        } catch (Exception e) {
            logger.error("论文元数据候选搜索失败", e);
            return adaptiveResult(List.of(), 0, PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE);
        }
    }

    private AdaptiveSearchResult adaptiveTextOnlySearchWithPermission(String query,
                                                                      String userDbId,
                                                                      List<String> userEffectiveTags,
                                                                      RetrievalBudget budget,
                                                                      List<String> scopePaperIds) {
        try {
            List<SearchResult> accepted = new ArrayList<>();
            long startedAt = System.nanoTime();
            int offset = 0;
            int acceptedTokenEstimate = 0;
            int scannedCount = 0;
            PaperRetrievalService.StopReason stopReason = PaperRetrievalService.StopReason.EXHAUSTED;
            boolean contextBudgetReached = false;
            SearchBranchPlan branchPlan = SearchBranchPlan.forQuery(query);
            while (withinLatencyBudget(startedAt, budget)) {
                int pageOffset = offset;
                SearchResponse<PaperChunkDocument> response = esClient.search(s -> s
                                .index(PaperSearchIndex.INDEX_NAME)
                                .from(pageOffset)
                                .size(budget.pageBatchSize())
                                .query(q -> q.bool(b -> b
                                        .must(m -> m.match(ma -> ma.field(branchPlan.keywordMatchField()).query(query)))
                                        .filter(f -> permissionFilter(f, userDbId, userEffectiveTags))
                                        .filter(f -> paperScopeFilter(f, scopePaperIds))
                                ))
                                .minScore(budget.minScore()),
                        PaperChunkDocument.class
                );
                if (response.hits().hits().isEmpty()) {
                    stopReason = accepted.isEmpty()
                            ? PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE
                            : PaperRetrievalService.StopReason.EXHAUSTED;
                    break;
                }
                scannedCount += response.hits().hits().size();
                int acceptedBeforePage = accepted.size();
                for (var hit : response.hits().hits()) {
                    SearchResult result = toSearchResult(hit.source(), hit.score(), "TEXT_ONLY");
                    if (!EvidenceQuality.isUsable(result, budget.minScore())) {
                        continue;
                    }
                    int tokenEstimate = estimateTokens(EvidenceQuality.bestEvidenceText(result));
                    if (acceptedTokenEstimate + tokenEstimate > budget.contextTokenBudget()) {
                        contextBudgetReached = true;
                        stopReason = PaperRetrievalService.StopReason.CONTEXT_BUDGET;
                        break;
                    }
                    accepted.add(result);
                    acceptedTokenEstimate += tokenEstimate;
                }
                if (contextBudgetReached) {
                    break;
                }
                if (accepted.size() == acceptedBeforePage) {
                    stopReason = accepted.isEmpty()
                            ? PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE
                            : PaperRetrievalService.StopReason.PLATEAU;
                    break;
                }
                offset += response.hits().hits().size();
                Long total = response.hits().total() == null ? null : response.hits().total().value();
                if (total != null && offset >= total) {
                    stopReason = PaperRetrievalService.StopReason.EXHAUSTED;
                    break;
                }
            }
            if (!withinLatencyBudget(startedAt, budget)) {
                stopReason = PaperRetrievalService.StopReason.LATENCY_BUDGET;
            }
            if (accepted.isEmpty() && stopReason == PaperRetrievalService.StopReason.EXHAUSTED) {
                stopReason = PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE;
            }
            attachPaperTitles(accepted);
            attachTableEvidence(accepted);
            finalizeReferenceEvidenceReadiness(accepted);
            return adaptiveResult(accepted, scannedCount, stopReason);
        } catch (Exception e) {
            logger.error("自适应纯文本搜索失败", e);
            return adaptiveResult(new ArrayList<>(), 0, PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE);
        }
    }

    private AdaptiveSearchResult adaptiveSemanticSearchWithPermission(String query,
                                                                      String userDbId,
                                                                      List<String> userEffectiveTags,
                                                                      RetrievalBudget budget,
                                                                      List<String> scopePaperIds,
                                                                      List<Float> queryVector) {
        try {
            List<SearchResult> accepted = new ArrayList<>();
            long startedAt = System.nanoTime();
            int offset = 0;
            int acceptedTokenEstimate = 0;
            int scannedCount = 0;
            PaperRetrievalService.StopReason stopReason = PaperRetrievalService.StopReason.EXHAUSTED;
            boolean contextBudgetReached = false;
            while (withinLatencyBudget(startedAt, budget)) {
                int pageOffset = offset;
                int recallK = Math.max(budget.pageBatchSize() * 30, pageOffset + budget.pageBatchSize());
                SearchResponse<PaperChunkDocument> response = esClient.search(s -> {
                            s.index(PaperSearchIndex.INDEX_NAME);
                            s.from(pageOffset);
                            s.size(budget.pageBatchSize());
                            s.knn(kn -> kn
                                    .field("vector")
                                    .queryVector(queryVector)
                                    .k(recallK)
                                    .numCandidates(recallK)
                                    .filter(f -> permissionFilter(f, userDbId, userEffectiveTags))
                                    .filter(f -> paperScopeFilter(f, scopePaperIds))
                            );
                            s.query(q -> q.bool(b -> b
                                    .filter(f -> permissionFilter(f, userDbId, userEffectiveTags))
                                    .filter(f -> paperScopeFilter(f, scopePaperIds))
                            ));
                            return s;
                        }, PaperChunkDocument.class);

                if (response.hits().hits().isEmpty()) {
                    stopReason = accepted.isEmpty()
                            ? PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE
                            : PaperRetrievalService.StopReason.EXHAUSTED;
                    break;
                }
                scannedCount += response.hits().hits().size();
                int acceptedBeforePage = accepted.size();
                for (var hit : response.hits().hits()) {
                    SearchResult result = toSearchResult(hit.source(), hit.score(), "SEMANTIC");
                    if (!EvidenceQuality.isUsable(result, budget.minScore())) {
                        continue;
                    }
                    int tokenEstimate = estimateTokens(EvidenceQuality.bestEvidenceText(result));
                    if (acceptedTokenEstimate + tokenEstimate > budget.contextTokenBudget()) {
                        contextBudgetReached = true;
                        stopReason = PaperRetrievalService.StopReason.CONTEXT_BUDGET;
                        break;
                    }
                    accepted.add(result);
                    acceptedTokenEstimate += tokenEstimate;
                }
                if (contextBudgetReached) {
                    break;
                }
                if (accepted.size() == acceptedBeforePage) {
                    stopReason = accepted.isEmpty()
                            ? PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE
                            : PaperRetrievalService.StopReason.PLATEAU;
                    break;
                }
                offset += response.hits().hits().size();
                Long total = response.hits().total() == null ? null : response.hits().total().value();
                if (total != null && offset >= total) {
                    stopReason = PaperRetrievalService.StopReason.EXHAUSTED;
                    break;
                }
            }
            if (!withinLatencyBudget(startedAt, budget)) {
                stopReason = PaperRetrievalService.StopReason.LATENCY_BUDGET;
            }
            if (accepted.isEmpty() && stopReason == PaperRetrievalService.StopReason.EXHAUSTED) {
                stopReason = PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE;
            }
            attachPaperTitles(accepted);
            attachTableEvidence(accepted);
            finalizeReferenceEvidenceReadiness(accepted);
            return adaptiveResult(accepted, scannedCount, stopReason);
        } catch (Exception e) {
            logger.error("自适应语义搜索失败", e);
            return adaptiveResult(new ArrayList<>(), 0, PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE);
        }
    }

    private AdaptiveSearchResult fuseAdaptiveResults(AdaptiveSearchResult semanticResult,
                                                     AdaptiveSearchResult keywordResult) {
        Map<String, SearchResult> fused = new LinkedHashMap<>();
        int scannedCount = 0;
        PaperRetrievalService.StopReason stopReason = PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE;
        if (semanticResult != null) {
            scannedCount += semanticResult.scannedCount();
            stopReason = mergeStopReason(stopReason, semanticResult.stopReason());
            for (SearchResult result : semanticResult.results()) {
                fused.putIfAbsent(resultKey(result), result);
            }
        }
        if (keywordResult != null) {
            scannedCount += keywordResult.scannedCount();
            stopReason = mergeStopReason(stopReason, keywordResult.stopReason());
            for (SearchResult result : keywordResult.results()) {
                fused.putIfAbsent(resultKey(result), result);
            }
        }
        List<SearchResult> results = new ArrayList<>(fused.values());
        if (results.isEmpty()) {
            stopReason = PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE;
        }
        return adaptiveResult(results, scannedCount, stopReason);
    }

    private PaperRetrievalService.StopReason mergeStopReason(PaperRetrievalService.StopReason current,
                                                             PaperRetrievalService.StopReason next) {
        if (next == null) {
            return current == null ? PaperRetrievalService.StopReason.EXHAUSTED : current;
        }
        if (next == PaperRetrievalService.StopReason.LATENCY_BUDGET
                || current == PaperRetrievalService.StopReason.LATENCY_BUDGET) {
            return PaperRetrievalService.StopReason.LATENCY_BUDGET;
        }
        if (next == PaperRetrievalService.StopReason.CONTEXT_BUDGET
                || current == PaperRetrievalService.StopReason.CONTEXT_BUDGET) {
            return PaperRetrievalService.StopReason.CONTEXT_BUDGET;
        }
        if (next == PaperRetrievalService.StopReason.PLATEAU
                || current == PaperRetrievalService.StopReason.PLATEAU) {
            return PaperRetrievalService.StopReason.PLATEAU;
        }
        if (current == null || current == PaperRetrievalService.StopReason.NO_USABLE_EVIDENCE) {
            return next;
        }
        return current;
    }

    private String resultKey(SearchResult result) {
        if (result == null) {
            return "";
        }
        return result.getPaperId() + ":" + result.getChunkId();
    }

    private AdaptiveSearchResult adaptiveResult(List<SearchResult> results,
                                                int scannedCount,
                                                PaperRetrievalService.StopReason stopReason) {
        List<SearchResult> safeResults = results == null ? List.of() : results;
        return new AdaptiveSearchResult(
                safeResults,
                scannedCount,
                safeResults.size(),
                (int) safeResults.stream().map(SearchResult::getPaperId).filter(id -> id != null && !id.isBlank()).distinct().count(),
                stopReason == null ? PaperRetrievalService.StopReason.EXHAUSTED : stopReason
        );
    }

    /**
     * 生成查询向量，返回 List<Float>，失败时返回 null
     */
    private List<Float> embedToVectorList(String text, String requesterId, Duration timeout) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text), requesterId, EmbeddingClient.UsageType.QUERY, timeout);
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("生成的向量为空");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }

    static Duration queryEmbeddingTimeout(RetrievalBudget budget) {
        RetrievalBudget effectiveBudget = budget == null ? RetrievalBudget.forQa() : budget;
        Duration byBudget = effectiveBudget.latencyBudget().dividedBy(3);
        Duration min = Duration.ofMillis(500);
        Duration max = Duration.ofSeconds(2);
        if (byBudget.compareTo(min) < 0) {
            return min;
        }
        return byBudget.compareTo(max) > 0 ? max : byBudget;
    }

    private void applyExtendedEvidenceFields(SearchResult result, PaperChunkDocument source) {
        result.setFigureId(source.getFigureId());
        result.setFormulaId(source.getFormulaId());
        result.setEvidenceRole(source.getEvidenceRole());
    }

    private SearchResult toSearchResult(PaperChunkDocument source, Double score, String retrievalMode) {
        if (source == null) {
            return new SearchResult(null, null, "", score);
        }
        String text = source.getTextContent() == null ? "" : source.getTextContent();
        SearchResult result = new SearchResult(
                source.getPaperId(),
                source.getChunkId(),
                text,
                score,
                source.getUserId(),
                source.getOrgTag(),
                source.isPublic(),
                null,
                source.getPageNumber(),
                source.getAnchorText(),
                retrievalMode,
                text,
                source.getElementType(),
                source.getSectionTitle(),
                source.getSectionLevel(),
                source.getBboxJson(),
                source.getParserName(),
                source.getParserVersion(),
                source.getSourceKind(),
                source.getTableId(),
                null,
                null,
                false
        );
        applyExtendedEvidenceFields(result, source);
        return result;
    }

    private SearchResult toPaperCandidateResult(PaperSearchDocument source, Double score) {
        if (source == null) {
            return new SearchResult(null, null, "", score);
        }
        String text = paperCandidateText(source);
        SearchResult result = new SearchResult(
                source.getPaperId(),
                0,
                text,
                score,
                source.getUserId(),
                source.getOrgTag(),
                source.isPublic(),
                source.getPaperTitle(),
                source.getOriginalFilename(),
                null,
                null,
                "PAPER_METADATA",
                source.getSearchText(),
                "PAPER",
                "title/abstract",
                null,
                null,
                "paper_search",
                null,
                "TEXT",
                null,
                null,
                null,
                false
        );
        result.setEvidenceRole("PAPER_METADATA");
        return result;
    }

    private String paperCandidateText(PaperSearchDocument source) {
        List<String> parts = new ArrayList<>();
        if (source.getPaperTitle() != null && !source.getPaperTitle().isBlank()) {
            parts.add("Title: " + source.getPaperTitle().trim());
        }
        if (source.getAbstractText() != null && !source.getAbstractText().isBlank()) {
            parts.add("Abstract: " + source.getAbstractText().trim());
        }
        if (source.getAuthors() != null && !source.getAuthors().isBlank()) {
            parts.add("Authors: " + source.getAuthors().trim());
        }
        if (source.getVenue() != null && !source.getVenue().isBlank()) {
            parts.add("Venue: " + source.getVenue().trim());
        }
        if (source.getYear() != null) {
            parts.add("Year: " + source.getYear());
        }
        if (parts.isEmpty()) {
            return source.getSearchText() == null ? "" : source.getSearchText();
        }
        return String.join("\n", parts);
    }

    private ObjectBuilder<Query> permissionFilter(Query.Builder f, String userDbId, List<String> userEffectiveTags) {
        return f.bool(bf -> bf
                .should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
                .should(s2 -> s2.term(t -> t.field("public").value(true)))
                .should(s3 -> {
                    if (userEffectiveTags.isEmpty()) {
                        return s3.matchNone(mn -> mn);
                    } else if (userEffectiveTags.size() == 1) {
                        return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
                    }
                    return s3.bool(inner -> {
                        userEffectiveTags.forEach(tag ->
                                inner.should(sh2 -> sh2.term(t -> t.field("orgTag").value(tag))));
                        return inner;
                    });
                })
        );
    }

    private ObjectBuilder<Query> paperScopeFilter(Query.Builder f, List<String> paperIds) {
        List<String> effectivePaperIds = normalizeScopePaperIds(paperIds);
        if (effectivePaperIds.isEmpty()) {
            return f.matchAll(m -> m);
        }
        if (effectivePaperIds.size() == 1) {
            return f.term(t -> t.field("paperId").value(effectivePaperIds.get(0)));
        }
        return f.terms(t -> t
                .field("paperId")
                .terms(v -> v.value(effectivePaperIds.stream().map(FieldValue::of).toList()))
        );
    }

    private List<String> normalizeScopePaperIds(List<String> paperIds) {
        if (paperIds == null || paperIds.isEmpty()) {
            return List.of();
        }
        return paperIds.stream()
                .filter(paperId -> paperId != null && !paperId.isBlank())
                .distinct()
                .toList();
    }

    private boolean withinLatencyBudget(long startedAtNanos, RetrievalBudget budget) {
        return System.nanoTime() - startedAtNanos < budget.latencyBudget().toNanos();
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    public record AdaptiveSearchResult(
            List<SearchResult> results,
            int scannedCount,
            int acceptedEvidenceCount,
            int sourceCount,
            PaperRetrievalService.StopReason stopReason
    ) {
        public AdaptiveSearchResult {
            results = results == null ? List.of() : results;
            stopReason = stopReason == null ? PaperRetrievalService.StopReason.EXHAUSTED : stopReason;
        }
    }

    public record SearchBranchPlan(
            boolean semanticEnabled,
            boolean semanticRequiresTextMatch,
            boolean keywordEnabled,
            boolean keywordRequiresTextMatch,
            String keywordMatchField
    ) {
        public static SearchBranchPlan forQuery(String query) {
            boolean hasQuery = query != null && !query.isBlank();
            return new SearchBranchPlan(hasQuery, false, hasQuery, true, KEYWORD_MATCH_FIELD);
        }
    }

    /**
     * 获取用户的有效组织标签（包含层级关系）
     */
    private List<String> getUserEffectiveOrgTags(String userId) {
        logger.debug("获取用户有效组织标签，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}", user.getUsername());
            }

            // 通过orgTagCacheService获取用户的有效标签集合
            List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户 {} 的有效组织标签: {}", user.getUsername(), effectiveTags);
            return effectiveTags;
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList(); // 返回空列表作为默认值
        }
    }

    /**
     * 获取用户的数据库ID用于权限过滤
     */
    private String getUserDbId(String userId) {
        logger.debug("获取用户数据库ID，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
                return userIdLong.toString(); // 如果输入已经是数字ID，直接返回
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}, ID: {}", user.getUsername(), user.getId());
                return user.getId().toString(); // 返回用户的数据库ID
            }
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    private void attachPaperTitles(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            Set<String> paperIds = results.stream()
                    .map(SearchResult::getPaperId)
                    .collect(Collectors.toSet());
            List<Paper> uploads = paperRepository.findByPaperIdIn(new java.util.ArrayList<>(paperIds));
            Map<String, Paper> paperById = uploads.stream()
                    .collect(Collectors.toMap(Paper::getPaperId, paper -> paper, (existing, replacement) -> existing));
            results.forEach(result -> {
                Paper paper = paperById.get(result.getPaperId());
                if (paper != null) {
                    result.setPaperTitle(paper.getPaperTitle());
                    result.setOriginalFilename(paper.getOriginalFilename());
                    applySourceProvenance(result, paper);
                }
                applyVisualAssetAvailability(result);
            });
        } catch (Exception e) {
            logger.error("补充论文标题失败", e);
        }
    }

    private void attachTableEvidence(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        for (SearchResult result : results) {
            if (!"TABLE".equalsIgnoreCase(result.getSourceKind())
                    || result.getPaperId() == null
                    || result.getTableId() == null) {
                continue;
            }
            try {
                paperTableRepository.findFirstByPaperIdAndTableId(result.getPaperId(), result.getTableId())
                        .ifPresent(table -> applyTableEvidence(result, table));
                boolean screenshotAvailable = paperVisualAssetRepository
                        .findFirstByPaperIdAndAssetTypeAndTableId(
                                result.getPaperId(),
                                PaperVisualAsset.TYPE_TABLE_CROP,
                                result.getTableId()
                        )
                        .isPresent();
                result.setTableScreenshotAvailable(screenshotAvailable);
            } catch (Exception e) {
                logger.warn("补充表格 evidence 失败: paperId={}, tableId={}, error={}",
                        result.getPaperId(), result.getTableId(), e.getMessage());
            }
        }
    }

    private void applyTableEvidence(SearchResult result, PaperTable table) {
        result.setTableText(table.getTableText());
        result.setTableMarkdown(table.getTableMarkdown());
        if (table.getScreenshotObjectKey() != null && !table.getScreenshotObjectKey().isBlank()) {
            result.setTableScreenshotAvailable(true);
        }
    }

    private void applySourceProvenance(SearchResult result, Paper paper) {
        boolean evalImport = paper.isEval();
        boolean structuredImport = evalImport || hasText(paper.getSourceDataset()) || isJsonImport(paper);
        String sourceType = evalImport ? "EVAL_IMPORT" : structuredImport ? "STRUCTURED_IMPORT" : "PDF";
        result.setSourceType(sourceType);
        result.setStructuredImport(structuredImport);
        result.setEvalImport(evalImport);
    }

    private void applyVisualAssetAvailability(SearchResult result) {
        if (result.getPaperId() == null || result.getPaperId().isBlank()) {
            return;
        }
        if (result.getPageNumber() != null) {
            boolean pageAvailable = paperVisualAssetRepository
                    .findFirstByPaperIdAndAssetTypeAndPageNumber(
                            result.getPaperId(),
                            PaperVisualAsset.TYPE_PAGE_SCREENSHOT,
                            result.getPageNumber()
                    )
                    .isPresent();
            result.setPageScreenshotAvailable(pageAvailable);
        }
        if (result.getFigureId() != null && !result.getFigureId().isBlank()) {
            boolean figureAvailable = paperVisualAssetRepository
                    .findFirstByPaperIdAndAssetTypeAndFigureId(
                            result.getPaperId(),
                            PaperVisualAsset.TYPE_FIGURE_CROP,
                            result.getFigureId()
                    )
                    .isPresent()
                    || paperVisualAssetRepository
                    .findFirstByPaperIdAndAssetTypeAndFigureId(
                            result.getPaperId(),
                            PaperVisualAsset.TYPE_CHART_CROP,
                            result.getFigureId()
                    )
                    .isPresent();
            result.setFigureScreenshotAvailable(figureAvailable);
        }
    }

    private void finalizeReferenceEvidenceReadiness(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        for (SearchResult result : results) {
            boolean structuredImport = Boolean.TRUE.equals(result.getStructuredImport());
            boolean pageAvailable = Boolean.TRUE.equals(result.getPageScreenshotAvailable());
            boolean pdfEvidenceAvailable = "PDF".equals(result.getSourceType()) && pageAvailable;
            result.setPdfEvidenceAvailable(pdfEvidenceAvailable);
            result.setEvidenceAssetLevel(pdfEvidenceAvailable
                    ? "PDF_VISUAL"
                    : structuredImport ? "TEXT_ONLY" : "PDF_PENDING_ASSETS");
            result.setAssetWarnings(referenceAssetWarnings(result, structuredImport, pageAvailable));
        }
    }

    private List<String> referenceAssetWarnings(SearchResult result, boolean structuredImport, boolean pageAvailable) {
        List<String> warnings = new ArrayList<>();
        if (structuredImport) {
            warnings.add("structured_import_text_only");
            return warnings;
        }
        if (result.getPageNumber() != null && !pageAvailable) {
            warnings.add("page_screenshots_missing");
        }
        if ("TABLE".equalsIgnoreCase(result.getSourceKind())
                && result.getTableId() != null
                && !Boolean.TRUE.equals(result.getTableScreenshotAvailable())) {
            warnings.add("table_screenshot_missing");
        }
        if (("FIGURE".equalsIgnoreCase(result.getSourceKind()) || "CHART".equalsIgnoreCase(result.getSourceKind()))
                && result.getFigureId() != null
                && !Boolean.TRUE.equals(result.getFigureScreenshotAvailable())) {
            warnings.add("figure_screenshot_missing");
        }
        return warnings;
    }

    private boolean isJsonImport(Paper paper) {
        String originalFilename = paper.getOriginalFilename();
        return originalFilename != null && originalFilename.toLowerCase().endsWith(".json");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
