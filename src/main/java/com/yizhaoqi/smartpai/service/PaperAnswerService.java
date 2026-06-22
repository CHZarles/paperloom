package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.Paper;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    static final int RECOMMENDATION_CHUNK_RECALL = 120;
    static final int RECOMMENDATION_MAX_PAPERS = 8;
    static final int RECOMMENDATION_EVIDENCE_PER_PAPER = 2;
    static final int QA_CHUNK_RECALL = 40;
    static final int QA_MAX_EVIDENCE = 6;
    static final int QA_MAX_EVIDENCE_PER_PAPER = 4;

    private static final Duration FOCUS_TTL = Duration.ofDays(7);
    private static final Pattern EVIDENCE_TOKEN_PATTERN = Pattern.compile("\\{\\{E(\\d+)}}");
    private static final Pattern BRACKET_CITATION_PATTERN = Pattern.compile("\\[\\d+]");
    private static final Pattern LEGACY_SOURCE_PATTERN = Pattern.compile("(?:来源|source)\\s*#\\s*\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_TITLE_PATTERN = Pattern.compile("《([^》]+)》");

    private final PaperRetrievalService paperRetrievalService;
    private final PaperService paperService;
    private final LlmProviderRouter llmProviderRouter;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public PaperAnswerService(PaperRetrievalService paperRetrievalService,
                              PaperService paperService,
                              LlmProviderRouter llmProviderRouter,
                              RedisTemplate<String, String> redisTemplate,
                              ObjectMapper objectMapper) {
        this.paperRetrievalService = paperRetrievalService;
        this.paperService = paperService;
        this.llmProviderRouter = llmProviderRouter;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public AnswerResult answer(String userId, String conversationId, String userMessage) {
        Intent intent = classifyIntent(userMessage);
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

        FocusState focus = readFocus(conversationId);
        if (intent == Intent.CLARIFY) {
            if (focus == null || focus.lastPaperIds().isEmpty()) {
                return clarify();
            }
            if (focus.lastPaperIds().size() > 1) {
                return new AnswerResult("你想讲第几篇？", Map.of(), Intent.CLARIFY, 0, focus.lastPaperIds().size(), false);
            }
            return answerQa(userId, conversationId, userMessage, focus);
        }
        if (intent == Intent.PAPER_RECOMMENDATION) {
            return answerRecommendation(userId, conversationId, userMessage);
        }
        return answerQa(userId, conversationId, userMessage, focus);
    }

    private AnswerResult answerRecommendation(String userId, String conversationId, String userMessage) {
        if (isPaperInventoryQuery(userMessage)) {
            return answerPaperInventory(userId, conversationId);
        }
        String retrievalQuery = recommendationQuery(userMessage);
        List<SearchResult> results = paperRetrievalService
                .retrieve(retrievalQuery, userId, RECOMMENDATION_CHUNK_RECALL)
                .results();
        List<PaperCandidate> candidates = groupPaperCandidates(results);
        if (candidates.isEmpty()) {
            writeFocus(conversationId, new FocusState(Intent.PAPER_RECOMMENDATION.name(), List.of(), List.of()));
            return new AnswerResult(
                    "**结论**\n当前可访问论文库中，我没有找到与「" + userMessage + "」足够相关的论文。\n\n"
                            + "**限制**\n推荐只基于当前已上传且你有权限访问的论文库。",
                    Map.of(),
                    Intent.PAPER_RECOMMENDATION,
                    0,
                    0,
                    false
            );
        }

        Map<Integer, ChatHandler.ReferenceInfo> mappings = new LinkedHashMap<>();
        StringBuilder markdown = new StringBuilder();
        markdown.append("**结论**\n");
        if (candidates.size() == 1) {
            markdown.append("当前可访问论文库中，我只找到 1 篇与「").append(userMessage).append("」相关的论文。\n\n");
        } else {
            markdown.append("当前可访问论文库中，我找到 ").append(candidates.size())
                    .append(" 篇与「").append(userMessage).append("」相关的论文。\n\n");
        }
        markdown.append("**推荐**\n");
        int referenceNumber = 1;
        for (PaperCandidate candidate : candidates) {
            EvidenceItem evidence = candidate.evidence().get(0);
            markdown.append(referenceNumber)
                    .append(". 《").append(candidate.paperTitle()).append("》\n")
                    .append("   这篇论文与该问题相关，主要证据是：")
                    .append(shortText(evidence.matchedText(), 96))
                    .append("。[")
                    .append(referenceNumber)
                    .append("]\n");
            mappings.put(referenceNumber, toReferenceInfo(evidence.result(), evidence.matchedText()));
            referenceNumber++;
        }
        markdown.append("\n**限制**\n推荐只基于当前已上传且你有权限访问的论文库，不是全网论文推荐。");
        writeFocus(conversationId, new FocusState(
                Intent.PAPER_RECOMMENDATION.name(),
                candidates.stream().map(PaperCandidate::paperId).toList(),
                candidates.stream().map(PaperCandidate::paperTitle).toList()
        ));
        return new AnswerResult(markdown.toString(), mappings, Intent.PAPER_RECOMMENDATION,
                candidates.stream().mapToInt(candidate -> candidate.evidence().size()).sum(), candidates.size(), false);
    }

    private AnswerResult answerPaperInventory(String userId, String conversationId) {
        List<Paper> papers = paperService.getAccessiblePapers(userId, null);
        if (papers == null || papers.isEmpty()) {
            writeFocus(conversationId, new FocusState(Intent.PAPER_RECOMMENDATION.name(), List.of(), List.of()));
            return new AnswerResult(
                    "**结论**\n当前可访问论文库中还没有论文。\n\n**限制**\n上传并完成解析后，论文才会出现在这里。",
                    Map.of(),
                    Intent.PAPER_RECOMMENDATION,
                    0,
                    0,
                    false
            );
        }

        List<Paper> shown = papers.stream().limit(RECOMMENDATION_MAX_PAPERS).toList();
        StringBuilder markdown = new StringBuilder()
                .append("**结论**\n当前可访问论文库中有 ")
                .append(papers.size())
                .append(" 篇论文。");
        if (papers.size() > shown.size()) {
            markdown.append("先显示前 ").append(shown.size()).append(" 篇。");
        }
        markdown.append("\n\n**论文**\n");
        for (int i = 0; i < shown.size(); i++) {
            markdown.append(i + 1).append(". 《").append(displayTitle(shown.get(i))).append("》\n");
        }
        markdown.append("\n**限制**\n这是当前可访问论文库列表，不是全网论文推荐。");

        writeFocus(conversationId, new FocusState(
                Intent.PAPER_RECOMMENDATION.name(),
                shown.stream().map(Paper::getPaperId).toList(),
                shown.stream().map(this::displayTitle).toList()
        ));
        return new AnswerResult(markdown.toString(), Map.of(), Intent.PAPER_RECOMMENDATION,
                0, papers.size(), false);
    }

    private AnswerResult answerQa(String userId, String conversationId, String userMessage, FocusState focus) {
        String query = scopedQuery(userMessage, focus);
        List<SearchResult> results = paperRetrievalService.retrieve(query, userId, QA_CHUNK_RECALL).results();
        if (focus != null && focus.lastPaperIds().size() == 1) {
            String paperId = focus.lastPaperIds().get(0);
            results = results.stream()
                    .filter(result -> paperId.equals(result.getPaperId()))
                    .toList();
        }
        List<EvidenceItem> ledger = buildQaLedger(results);
        if (ledger.isEmpty()) {
            return new AnswerResult(
                    "我没有找到足够可靠的论文证据来回答这个问题。",
                    Map.of(),
                    Intent.PAPER_QA,
                    0,
                    0,
                    false
            );
        }

        try {
            LlmProviderRouter.ReActTurn turn = llmProviderRouter.completeReActTurn(
                    userId,
                    buildEvidenceMessages(userMessage, ledger),
                    List.of(),
                    1600
            );
            RenderedAnswer rendered = renderVerifiedAnswer(turn.content(), ledger);
            if (rendered != null) {
                writeFocus(conversationId, new FocusState(
                        Intent.PAPER_QA.name(),
                        ledger.stream().map(EvidenceItem::paperId).distinct().toList(),
                        ledger.stream().map(EvidenceItem::paperTitle).distinct().toList()
                ));
                return new AnswerResult(rendered.markdown(), rendered.references(), Intent.PAPER_QA,
                        ledger.size(), distinctPaperCount(ledger), false);
            }
        } catch (Exception ignored) {
            // ponytail: LLM failure falls through to deterministic evidence fallback.
        }
        return qaFallback(ledger);
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
            if (!byId.containsKey(evidenceId)) {
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

    private AnswerResult qaFallback(List<EvidenceItem> ledger) {
        EvidenceItem item = ledger.get(0);
        Map<Integer, ChatHandler.ReferenceInfo> references = Map.of(1, toReferenceInfo(item.result(), item.matchedText()));
        String markdown = "**结论**\n我找到了相关证据，但无法生成足够可靠的自然语言回答。\n\n"
                + "**依据**\n- " + shortText(item.matchedText(), 140) + "。[1]\n\n"
                + "**限制**\n建议点击 citation 查看 Source Evidence。";
        return new AnswerResult(markdown, references, Intent.PAPER_QA, ledger.size(), distinctPaperCount(ledger), true);
    }

    private AnswerResult clarify() {
        return new AnswerResult("你想讲哪一篇？可以点上一条推荐里的编号，或者直接说论文标题。",
                Map.of(), Intent.CLARIFY, 0, 0, false);
    }

    private List<PaperCandidate> groupPaperCandidates(List<SearchResult> results) {
        Map<String, List<SearchResult>> byPaper = new LinkedHashMap<>();
        for (SearchResult result : results == null ? List.<SearchResult>of() : results) {
            if (result.getPaperId() == null || displayTitle(result).isBlank()) {
                continue;
            }
            byPaper.computeIfAbsent(result.getPaperId(), ignored -> new ArrayList<>()).add(result);
        }
        return byPaper.values().stream()
                .map(this::toPaperCandidate)
                .sorted(Comparator.comparingDouble(PaperCandidate::aggregateScore).reversed())
                .limit(RECOMMENDATION_MAX_PAPERS)
                .toList();
    }

    private PaperCandidate toPaperCandidate(List<SearchResult> results) {
        List<SearchResult> sorted = results.stream()
                .sorted(Comparator.comparingDouble((SearchResult result) -> score(result)).reversed())
                .toList();
        List<EvidenceItem> evidence = new ArrayList<>();
        for (int i = 0; i < Math.min(RECOMMENDATION_EVIDENCE_PER_PAPER, sorted.size()); i++) {
            evidence.add(toEvidenceItem("E" + (i + 1), sorted.get(i)));
        }
        double aggregate = sorted.stream().limit(3).mapToDouble(this::score).sum();
        SearchResult first = sorted.get(0);
        return new PaperCandidate(first.getPaperId(), displayTitle(first), aggregate, evidence);
    }

    private List<EvidenceItem> buildQaLedger(List<SearchResult> results) {
        List<EvidenceItem> ledger = new ArrayList<>();
        Map<String, Integer> perPaperCount = new HashMap<>();
        for (SearchResult result : results == null ? List.<SearchResult>of() : results) {
            if (result.getPaperId() == null || result.getChunkId() == null) {
                continue;
            }
            int usedForPaper = perPaperCount.getOrDefault(result.getPaperId(), 0);
            if (usedForPaper >= QA_MAX_EVIDENCE_PER_PAPER) {
                continue;
            }
            ledger.add(toEvidenceItem("E" + (ledger.size() + 1), result));
            perPaperCount.put(result.getPaperId(), usedForPaper + 1);
            if (ledger.size() >= QA_MAX_EVIDENCE) {
                break;
            }
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
                result.getTableScreenshotAvailable()
        );
    }

    private String retrievalLabel(String retrievalMode) {
        return "HYBRID".equalsIgnoreCase(retrievalMode) || "EXPANDED_HYBRID".equalsIgnoreCase(retrievalMode)
                ? "混合召回（语义相关 + 关键词命中）"
                : retrievalMode;
    }

    private Intent classifyIntent(String userMessage) {
        String normalized = normalize(userMessage);
        if (Set.of("hi", "hello", "hey", "你好", "您好", "谢谢", "thanks", "ok", "好的", "在吗").contains(normalized)) {
            return Intent.SMALLTALK;
        }
        String lower = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        if (isPaperInventoryQuery(userMessage)
                || normalized.startsWith("推荐")
                || (lower.contains("推荐") && lower.contains("论文"))
                || lower.contains("相关论文")
                || lower.contains("有哪些论文")
                || lower.contains("related papers")
                || lower.contains("recommend papers")) {
            return Intent.PAPER_RECOMMENDATION;
        }
        if (lower.contains("详细讲解") || lower.contains("展开") || lower.contains("讲第一个") || lower.contains("这篇呢")) {
            return Intent.CLARIFY;
        }
        return Intent.PAPER_QA;
    }

    private boolean isPaperInventoryQuery(String userMessage) {
        String normalized = normalize(userMessage);
        return normalized.contains("有什么论文")
                || normalized.contains("有哪些论文")
                || normalized.contains("论文列表")
                || normalized.contains("论文库有什么")
                || normalized.contains("当前论文");
    }

    private String recommendationQuery(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "";
        }
        String cleaned = userMessage.trim()
                .replaceFirst("^(请|帮我|麻烦)?\\s*(推荐一下|推荐一些|推荐几个|推荐下|推荐)\\s*", "")
                .replaceFirst("^和\\s*", "")
                .replaceFirst("(相关的?论文|相关论文|论文)$", "")
                .trim();
        return cleaned.isBlank() ? userMessage.trim() : cleaned;
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

    private int distinctPaperCount(List<EvidenceItem> ledger) {
        return (int) ledger.stream().map(EvidenceItem::paperId).distinct().count();
    }

    private double score(SearchResult result) {
        return result.getScore() == null ? 0.0d : result.getScore();
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

    private String displayTitle(Paper paper) {
        return paper == null ? "" : paper.getPaperTitle();
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s!！?？。,.，;；:：、\"'“”‘’()（）\\[\\]{}<>《》]+", "");
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

    public enum Intent {
        SMALLTALK,
        PAPER_RECOMMENDATION,
        PAPER_QA,
        CLARIFY
    }

    public record AnswerResult(
            String markdown,
            Map<Integer, ChatHandler.ReferenceInfo> referenceMappings,
            Intent intent,
            int evidenceCount,
            int uniquePaperCount,
            boolean fallbackUsed
    ) {
        public AnswerResult {
            referenceMappings = referenceMappings == null ? Map.of() : referenceMappings;
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

    private record PaperCandidate(
            String paperId,
            String paperTitle,
            double aggregateScore,
            List<EvidenceItem> evidence
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
