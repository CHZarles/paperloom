package io.github.chzarles.paperloom.service.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.DiscoveryChatMessage;
import io.github.chzarles.paperloom.model.DiscoveryMemory;
import io.github.chzarles.paperloom.repository.DiscoveryChatMessageRepository;
import io.github.chzarles.paperloom.repository.DiscoveryMemoryRepository;
import io.github.chzarles.paperloom.utils.LogUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 智能探索聊天（PRD v3 §3）：每用户一个单例 thread 的编排层。
 *
 * <p>三层记忆全部落在产品侧：长期记忆 {@link DiscoveryMemory}（用户可编辑、/沉淀 写入）、短期窗口
 * （CUTOFF 行之后的最近消息，硬截断不摘要）、候选池（本 turn 检索实况 + 窗口卡片，入库门事实源）。
 * runner 为每个 turn 重建 in-memory pi 会话，Java 每轮注入历史与记忆块。
 */
@Service
public class DiscoveryChatService {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryChatService.class);
    private static final DateTimeFormatter HOUR_BUCKET = DateTimeFormatter.ofPattern("yyyyMMddHH");
    /** 短期窗口上限（PRD v3 §3.1）：最近 40 条且 ≤24k 字符，超窗整段不带。 */
    private static final int WINDOW_MAX_MESSAGES = 40;
    private static final int WINDOW_MAX_CHARS = 24000;
    private static final int HISTORY_CARDS_MAX = 60;
    /** 长期记忆块上限：超出丢最旧 INSIGHT。 */
    private static final int MEMORY_BLOCK_MAX_CHARS = 4000;
    private static final int MEMORY_CONTENT_MAX = 2000;
    private static final int MAX_TEXT_LENGTH = 4000;

    private final DiscoveryChatMessageRepository chatRepository;
    private final DiscoveryMemoryRepository memoryRepository;
    private final ArxivDiscoveryService arxivDiscoveryService;
    private final DiscoveryRunnerClient runnerClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${paper.discovery.agent.enabled:true}")
    private boolean enabled;

    @Value("${paper.discovery.agent.turns-per-hour:30}")
    private int turnsPerHour;

    /** turnId -> 连续轮询失败计数（runner_lost 判据）。 */
    private final Map<String, Integer> activeTurns = new ConcurrentHashMap<>();
    /** turnId -> 本 turn 内部检索真实见过的候选 id（入库门的实况侧）。 */
    private final Map<String, Set<String>> turnCandidates = new ConcurrentHashMap<>();
    private final Map<String, CorpusDisclosure> turnCorpus = new ConcurrentHashMap<>();

    private record CorpusDisclosure(Set<String> paperIds, Set<String> locationRefs) {
        private CorpusDisclosure() {
            this(ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet());
        }
    }

    private ScheduledExecutorService poller;

    public DiscoveryChatService(DiscoveryChatMessageRepository chatRepository,
                                 DiscoveryMemoryRepository memoryRepository,
                                 ArxivDiscoveryService arxivDiscoveryService,
                                 DiscoveryRunnerClient runnerClient,
                                 RedisTemplate<String, Object> redisTemplate,
                                 ObjectMapper objectMapper) {
        this.chatRepository = chatRepository;
        this.memoryRepository = memoryRepository;
        this.arxivDiscoveryService = arxivDiscoveryService;
        this.runnerClient = runnerClient;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // --------------------------------------------------------- 用户消息入口

    public Map<String, Object> postMessage(String userId, String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) {
            throw new CustomException("消息不能为空", HttpStatus.BAD_REQUEST);
        }
        if (!enabled) {
            throw new CustomException("智能探索功能已关闭", HttpStatus.FORBIDDEN);
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH);
        }
        if (findRunningTurn(userId).isPresent()) {
            throw new CustomException("上一条消息还在处理中，稍等或点取消", HttpStatus.CONFLICT);
        }

        if (text.startsWith("/")) {
            String command = text.split("\\s+", 2)[0];
            String rest = text.substring(command.length()).trim();
            switch (command) {
                case "/clear" -> {
                    DiscoveryChatMessage cutoff = new DiscoveryChatMessage();
                    cutoff.setUserId(userId);
                    cutoff.setRole(DiscoveryChatMessage.ROLE_CUTOFF);
                    chatRepository.save(cutoff);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("type", "CLEAR");
                    LogUtils.logBusiness("DISCOVERY_AGENT_CLEAR", userId, "清除临时上下文");
                    return data;
                }
                case "/沉淀", "/distill" -> {
                    return startModelTurn(userId, "/沉淀 " + rest, "DISTILL", rest, true);
                }
                case "/状态", "/status" -> {
                    String content = buildStatusText(userId);
                    DiscoveryChatMessage row = insertRow(userId, DiscoveryChatMessage.ROLE_SYSTEM, content);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("type", "STATUS");
                    data.put("messageId", row.getId());
                    return data;
                }
                case "/记忆", "/memory" -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("type", "MEMORY");
                    return data;
                }
                default -> {
                    // 未知指令按普通消息处理
                }
            }
        }
        return startModelTurn(userId, text, "CHAT", text, false);
    }

    /** 建一个模型 turn：写 USER 行 + RUNNING 的 ASSISTANT 行，注入窗口后交给 runner。 */
    private Map<String, Object> startModelTurn(String userId, String displayText, String kind,
                                               String lastMessage, boolean budgeted) {
        enforceTurnBudget(userId);
        String turnId = UUID.randomUUID().toString();

        insertRow(userId, DiscoveryChatMessage.ROLE_USER, displayText);
        DiscoveryChatMessage assistantRow = new DiscoveryChatMessage();
        assistantRow.setUserId(userId);
        assistantRow.setRole(DiscoveryChatMessage.ROLE_ASSISTANT);
        assistantRow.setKind(kind);
        assistantRow.setTurnId(turnId);
        assistantRow.setTurnStatus(DiscoveryChatMessage.TURN_STATUS_RUNNING);
        assistantRow.setCards("[]");
        chatRepository.save(assistantRow);

        try {
            Window window = assembleWindow(userId);
            List<DiscoveryRunnerClient.MemoryItem> memory = assembleMemoryBlock(userId);
            runnerClient.startTurn(runnerClient.buildTurnPayload(
                    turnId, userId, kind, lastMessage, window.history(), memory, window.cards()));
        } catch (Exception e) {
            finalizeRow(assistantRow, DiscoveryChatMessage.TURN_STATUS_FAILED, "runner_unavailable");
            throw new CustomException("智能探索服务暂时不可用，请稍后再试", HttpStatus.BAD_GATEWAY);
        }
        recordLaunch(userId);
        activeTurns.put(turnId, 0);
        LogUtils.logBusiness("DISCOVERY_AGENT_TURN", userId,
                "聊天 turn 启动: turnId=%s, kind=%s", turnId, kind);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "TURN");
        data.put("turnId", turnId);
        data.put("kind", kind);
        return data;
    }

    // --------------------------------------------------------- 线程与快照

    public void clearConversation(String userId) {
        // 活跃 turn 一并取消回收
        findRunningTurn(userId).ifPresent(active -> {
            active.setTurnStatus(DiscoveryChatMessage.TURN_STATUS_FAILED);
            active.setReasonCode("user_cleared");
            chatRepository.save(active);
            activeTurns.remove(active.getTurnId());
            discardTurnState(active.getTurnId());
            runnerClient.dispose(active.getTurnId());
        });
        chatRepository.deleteAllByUserId(userId);
        LogUtils.logBusiness("DISCOVERY_AGENT_CLEAR", userId, "清除全部对话与候选");
    }

    public Map<String, Object> getThread(String userId, Long afterId) {
        long after = afterId == null ? 0L : afterId;
        List<DiscoveryChatMessage> rows = chatRepository.findTop500ByUserIdOrderByIdDesc(userId);
        List<DiscoveryChatMessage> ordered = rows.stream()
                .filter(r -> r.getId() > after)
                // RUNNING 的 ASSISTANT 行不进消息列表，避免前端游标跳过它导致完成态永远拉不回
                .filter(r -> !DiscoveryChatMessage.TURN_STATUS_RUNNING.equals(r.getTurnStatus()))
                .sorted(Comparator.comparing(DiscoveryChatMessage::getId))
                .toList();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (DiscoveryChatMessage row : ordered) {
            messages.add(messageDto(row));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messages", messages);

        Map<String, Object> activeTurn = null;
        Optional<DiscoveryChatMessage> running = findRunningTurn(userId);
        if (running.isPresent()) {
            activeTurn = liveSnapshot(running.get());
        }
        data.put("activeTurn", activeTurn);

        Window window = assembleWindow(userId);
        Map<String, Object> windowStats = new LinkedHashMap<>();
        windowStats.put("count", window.history().size());
        windowStats.put("chars", window.chars());
        windowStats.put("capChars", WINDOW_MAX_CHARS);
        data.put("window", windowStats);
        data.put("memoryCount", memoryRepository.findByUserIdOrderByUpdatedAtAsc(userId).stream()
                .filter(DiscoveryMemory::isActive).count());
        data.put("enabled", enabled);
        return data;
    }

    private Map<String, Object> liveSnapshot(DiscoveryChatMessage row) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("turnId", row.getTurnId());
        dto.put("kind", row.getKind());
        dto.put("status", row.getTurnStatus());
        dto.put("messageId", row.getId());
        JsonNode snapshot = null;
        if (activeTurns.containsKey(row.getTurnId())) {
            try {
                snapshot = runnerClient.snapshot(row.getTurnId());
            } catch (Exception e) {
                logger.warn("拉取 Runner 快照失败 => turnId: {}, error: {}", row.getTurnId(), e.getMessage());
            }
        }
        if (snapshot != null) {
            dto.put("status", "RUNNING");
            dto.put("assistantText", snapshot.path("assistantText").asText(""));
            dto.put("cards", jsonToList(snapshot.path("cards")));
            dto.put("importResults", snapshot.path("importResults").isNull() ? null
                    : jsonToList(snapshot.path("importResults")));
            dto.put("savedMemory", snapshot.path("savedMemory").isNull() ? null
                    : jsonToList(snapshot.path("savedMemory")));
            dto.put("steps", jsonToList(snapshot.path("steps")));
            dto.put("searchCount", snapshot.path("searchCount").asInt(0));
            dto.put("observedCount", snapshot.path("observedCount").asInt(0));
            dto.put("contextPercent", snapshot.path("contextPercent").isNull() ? null
                    : snapshot.path("contextPercent").asDouble());
            dto.put("elapsedMs", snapshot.path("elapsedMs").asLong(0));
            dto.put("usage", usageOf(snapshot.path("usage")));
        }
        return dto;
    }

    public Map<String, Object> cancel(String userId, String turnId) {
        DiscoveryChatMessage row = chatRepository.findByTurnId(turnId == null ? "" : turnId)
                .orElseThrow(() -> new CustomException("消息不存在", HttpStatus.NOT_FOUND));
        if (!row.getUserId().equals(userId) || !DiscoveryChatMessage.ROLE_ASSISTANT.equals(row.getRole())) {
            throw new CustomException("消息不存在", HttpStatus.NOT_FOUND);
        }
        if (DiscoveryChatMessage.TURN_STATUS_RUNNING.equals(row.getTurnStatus())) {
            row.setTurnStatus(DiscoveryChatMessage.TURN_STATUS_CANCELLED);
            chatRepository.save(row);
            activeTurns.remove(row.getTurnId());
            discardTurnState(row.getTurnId());
            runnerClient.cancelAsync(row.getTurnId());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("turnId", row.getTurnId());
        data.put("status", row.getTurnStatus());
        return data;
    }

    // --------------------------------------------------------- 长期记忆 CRUD

    public List<Map<String, Object>> listMemory(String userId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (DiscoveryMemory memory : memoryRepository.findByUserIdOrderByUpdatedAtAsc(userId)) {
            items.add(memoryDto(memory));
        }
        return items;
    }

    public Map<String, Object> createMemory(String userId, String kind, String content) {
        DiscoveryMemory memory = new DiscoveryMemory();
        memory.setUserId(userId);
        memory.setKind(validateKind(kind, true));
        memory.setContent(validateMemoryContent(content));
        memory.setSource(DiscoveryMemory.SOURCE_USER);
        memory.setActive(true);
        memoryRepository.save(memory);
        LogUtils.logBusiness("DISCOVERY_AGENT_MEMORY", userId, "新增长期记忆: kind=%s", memory.getKind());
        return memoryDto(memory);
    }

    public Map<String, Object> updateMemory(String userId, Long id, String content, Boolean active) {
        DiscoveryMemory memory = ownedMemory(userId, id);
        if (content != null) {
            memory.setContent(validateMemoryContent(content));
        }
        if (active != null) {
            memory.setActive(active);
        }
        memoryRepository.save(memory);
        return memoryDto(memory);
    }

    public void deleteMemory(String userId, Long id) {
        DiscoveryMemory memory = ownedMemory(userId, id);
        memoryRepository.delete(memory);
        LogUtils.logBusiness("DISCOVERY_AGENT_MEMORY", userId, "删除长期记忆: id=%d", id);
    }

    // --------------------------------------------------------- 内部端点（runner 调用）

    public Map<String, Object> searchLibraryPapers(String turnId, Long userId, List<String> scope,
                                                   java.util.function.Supplier<Map<String, Object>> search) {
        CorpusDisclosure disclosure = requireCorpusTurn(turnId, userId, scope);
        Map<String, Object> result = search.get();
        if ("resolved".equals(result.get("status"))) {
            rememberCorpusIds(result.get("matches"), "paper_id", disclosure.paperIds());
        } else if (!result.containsKey("status")) {
            rememberCorpusIds(result.get("candidates"), "paper_id", disclosure.paperIds());
        }
        return result;
    }

    public Map<String, Object> findLibraryLocations(String turnId, Long userId, List<String> scope,
                                                     List<String> paperIds,
                                                     java.util.function.Supplier<Map<String, Object>> search) {
        CorpusDisclosure disclosure = requireCorpusTurn(turnId, userId, scope);
        requireDisclosed(paperIds, disclosure.paperIds());
        Map<String, Object> result = search.get();
        rememberCorpusIds(result.get("locations"), "location_ref", disclosure.locationRefs());
        return result;
    }

    public Map<String, Object> readLibraryLocations(String turnId, Long userId, List<String> scope,
                                                     List<String> locationRefs,
                                                     java.util.function.Supplier<Map<String, Object>> read) {
        CorpusDisclosure disclosure = requireCorpusTurn(turnId, userId, scope);
        requireDisclosed(locationRefs, disclosure.locationRefs());
        return read.get();
    }

    private CorpusDisclosure requireCorpusTurn(String turnId, Long userId, List<String> scope) {
        if (userId == null || userId <= 0 || (scope != null && !scope.isEmpty())) {
            throw new IllegalArgumentException("user_id 必填，discovery scope 必须为当前授权库");
        }
        DiscoveryChatMessage row = requireRunningChatTurn(turnId, userId.toString());
        if (!"CHAT".equals(row.getKind())) {
            throw new CustomException("仅 CHAT turn 可读取授权库", HttpStatus.CONFLICT);
        }
        return turnCorpus.computeIfAbsent(turnId, key -> new CorpusDisclosure());
    }

    private void requireDisclosed(List<String> requested, Set<String> disclosed) {
        if (requested == null || requested.isEmpty() || !disclosed.containsAll(requested)) {
            throw new CustomException("请求的论文或位置尚未在当前 turn 中发现", HttpStatus.CONFLICT);
        }
    }

    private void rememberCorpusIds(Object items, String field, Set<String> target) {
        if (items instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && map.get(field) instanceof String id) {
                    target.add(id);
                }
            }
        }
    }

    public Map<String, Object> delegatedSearch(String turnId, String userId, String query, Integer start,
                                                Integer max, String sort, Integer yearFrom, Integer yearTo) {
        DiscoveryChatMessage row = requireRunningChatTurn(turnId, userId);
        ArxivSearchPage page = arxivDiscoveryService.search(query, start, max, sort, userId);
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> candidates = turnCandidates.computeIfAbsent(turnId, k -> ConcurrentHashMap.newKeySet());
        for (ArxivSearchHit hit : page.items()) {
            Integer year = hit.paper().publishedYear();
            if ((yearFrom != null && (year == null || year < yearFrom))
                    || (yearTo != null && (year == null || year > yearTo))) {
                continue;
            }
            candidates.add(hit.paper().arxivId());
            items.add(hitToItem(hit));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", page.total());
        data.put("hasNext", page.hasNext());
        data.put("items", items);
        logger.debug("runner 检索 => turnId: {}, query: {}, hits: {}", row.getTurnId(), query, items.size());
        return data;
    }

    /** 入库门：ids 必须 ⊆ 当前窗口候选池（turn 实况 ∪ 窗口卡片），逐篇走现有 importPaper。 */
    public List<Map<String, Object>> delegatedImport(String turnId, String userId, List<String> arxivIds) {
        requireRunningChatTurn(turnId, userId);
        Set<String> allowed = new LinkedHashSet<>(turnCandidates.getOrDefault(turnId, Set.of()));
        allowed.addAll(assembleWindow(userId).cardIds());

        List<Map<String, Object>> results = new ArrayList<>();
        for (String rawId : arxivIds) {
            String id = rawId == null ? "" : rawId.trim();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("arxivId", id);
            String normalized = ArxivIdentifier.normalize(id).orElse(id);
            if (!allowed.contains(normalized)) {
                result.put("status", "FAILED");
                result.put("message", "不在当前候选池里");
                results.add(result);
                continue;
            }
            try {
                ArxivImportResult importResult = arxivDiscoveryService.importPaper(normalized, userId);
                result.put("status", importResult.status());
                result.put("paperId", importResult.paperId());
                result.put("message", importResult.message() == null ? "" : importResult.message());
            } catch (Exception e) {
                result.put("status", "FAILED");
                result.put("message", e.getMessage() == null ? "导入失败" : e.getMessage());
            }
            results.add(result);
        }
        // 即时落行：runner 崩了也不丢入库事实（库与预算已真实发生）
        chatRepository.findByTurnId(turnId).ifPresent(row -> {
            try {
                row.setImportResults(objectMapper.writeValueAsString(results));
                chatRepository.save(row);
            } catch (Exception e) {
                logger.warn("入库结果落行失败 => turnId: {}, error: {}", turnId, e.getMessage());
            }
        });
        LogUtils.logBusiness("DISCOVERY_AGENT_IMPORT", userId,
                "聊天入库: turnId=%s, 请求=%d", turnId, arxivIds == null ? 0 : arxivIds.size());
        return results;
    }

    /** /沉淀 轮唯一的长期记忆写入通道（kind 限 INSIGHT/PREF，source=DISTILLED）。 */
    public List<Map<String, Object>> distilledMemory(String userId, List<Map<String, String>> items) {
        List<Map<String, Object>> saved = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            throw new CustomException("记忆条目不能为空", HttpStatus.BAD_REQUEST);
        }
        if (items.size() > 20) {
            throw new CustomException("一次最多沉淀 20 条", HttpStatus.BAD_REQUEST);
        }
        for (Map<String, String> item : items) {
            String kind = item.get("kind");
            String content = item.get("content");
            if (!DiscoveryMemory.KIND_INSIGHT.equals(kind) && !DiscoveryMemory.KIND_PREF.equals(kind)) {
                throw new CustomException("kind 只能是 INSIGHT 或 PREF", HttpStatus.BAD_REQUEST);
            }
            if (content == null || content.isBlank() || content.length() > 300) {
                throw new CustomException("记忆内容为空或超过 300 字", HttpStatus.BAD_REQUEST);
            }
            DiscoveryMemory memory = new DiscoveryMemory();
            memory.setUserId(userId);
            memory.setKind(kind);
            memory.setContent(content.trim());
            memory.setSource(DiscoveryMemory.SOURCE_DISTILLED);
            memory.setActive(true);
            memoryRepository.save(memory);
            saved.add(memoryDto(memory));
        }
        LogUtils.logBusiness("DISCOVERY_AGENT_MEMORY", userId, "沉淀长期记忆: %d 条", saved.size());
        return saved;
    }

    /**
     * 用户在候选栏直接入库（PRD v3 UX：入不入库交给用户自决，可一键或单篇）。
     * 走模型之外的直通路径：门 = 当前窗口候选池 ∪ 活跃 turn 的检索实况；逐篇现有 importPaper。
     * 结果写 SYSTEM（kind=USER_IMPORT）行，聊天里留下记录、候选栏拿到最新状态。
     */
    public List<Map<String, Object>> userImport(String userId, List<String> arxivIds) {
        if (arxivIds == null || arxivIds.isEmpty()) {
            throw new CustomException("请先勾选要入库的论文", HttpStatus.BAD_REQUEST);
        }
        Set<String> pool = new java.util.HashSet<>(assembleWindow(userId).cardIds());
        findRunningTurn(userId).ifPresent(active ->
                pool.addAll(turnCandidates.getOrDefault(active.getTurnId(), Set.of())));

        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0;
        int failed = 0;
        for (String rawId : arxivIds) {
            String id = rawId == null ? "" : rawId.trim();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("arxivId", id);
            String normalized = ArxivIdentifier.normalize(id).orElse(id);
            if (!pool.contains(normalized)) {
                result.put("status", "FAILED");
                result.put("message", "不在当前候选池里");
                results.add(result);
                failed += 1;
                continue;
            }
            try {
                ArxivImportResult importResult = arxivDiscoveryService.importPaper(normalized, userId);
                result.put("status", importResult.status());
                result.put("paperId", importResult.paperId());
                result.put("message", importResult.message() == null ? "" : importResult.message());
                ok += 1;
            } catch (Exception e) {
                result.put("status", "FAILED");
                result.put("message", e.getMessage() == null ? "导入失败" : e.getMessage());
                failed += 1;
            }
            results.add(result);
        }
        try {
            DiscoveryChatMessage row = insertRow(userId, DiscoveryChatMessage.ROLE_SYSTEM, null);
            row.setKind("USER_IMPORT");
            int okFinal = ok;
            int failedFinal = failed;
            row.setContent(failedFinal > 0
                    ? String.format("已入库 %d 篇（%d 篇失败，详情见候选栏）。", okFinal, failedFinal)
                    : String.format("已入库 %d 篇，正在后台解析。", okFinal));
            row.setImportResults(objectMapper.writeValueAsString(results));
            chatRepository.save(row);
        } catch (Exception e) {
            logger.warn("用户入库记录落行失败 => userId: {}, error: {}", userId, e.getMessage());
        }
        LogUtils.logBusiness("DISCOVERY_AGENT_IMPORT", userId, "候选栏入库: 请求=%d, 完成=%d",
                arxivIds.size(), ok);
        return results;
    }

    /**
     * 从候选栏移除一篇候选：把该 arXiv 从所有消息行的 cards 里摘除（含 CUTOFF 前的行，彻底删干净）。
     * 只动候选展示，不影响库内已入库的论文与 importResults 记录。
     */
    public Map<String, Object> removeCandidateCard(String userId, String arxivId) {
        String id = ArxivIdentifier.normalize(arxivId).orElse(arxivId == null ? "" : arxivId.trim());
        if (id.isEmpty()) {
            throw new CustomException("缺少 arXiv 编号", HttpStatus.BAD_REQUEST);
        }
        int touched = stripCardsFromRows(userId, Set.of(id));
        LogUtils.logBusiness("DISCOVERY_AGENT_CARD_REMOVE", userId,
                "移除候选: arxivId=%s, 落行数=%d", id, touched);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("removedRows", touched);
        return data;
    }

    /**
     * Runner 侧移除候选：与候选栏 UI 的移除同源，把 ids 从所有消息行的 cards 里摘除。
     *
     * <p>服务的边界与入库一致：ids 必须落在当前 turn 的候选池或窗口历史卡片里，否则记入 rejected；
     * 只动候选展示，不影响库内已入库的论文与 importResults。
     */
    public Map<String, Object> removeCandidateCards(String turnId, String userId, List<String> arxivIds) {
        requireRunningChatTurn(turnId, userId);
        Set<String> allowed = new LinkedHashSet<>(turnCandidates.getOrDefault(turnId, Set.of()));
        allowed.addAll(assembleWindow(userId).cardIds());
        Set<String> ids = new LinkedHashSet<>();
        List<String> rejected = new ArrayList<>();
        for (String rawId : arxivIds == null ? List.<String>of() : arxivIds) {
            String raw = rawId == null ? "" : rawId.trim();
            String normalized = ArxivIdentifier.normalize(raw).orElse(raw);
            if (normalized.isEmpty()) {
                continue;
            }
            if (allowed.contains(normalized)) {
                ids.add(normalized);
            } else {
                rejected.add(normalized);
            }
        }
        int touched = ids.isEmpty() ? 0 : stripCardsFromRows(userId, ids);
        LogUtils.logBusiness("DISCOVERY_AGENT_CARD_REMOVE", userId,
                "runner 移除候选: ids=%s, 落行数=%d, 拒绝数=%d", ids, touched, rejected.size());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("removedRows", touched);
        data.put("removed", new ArrayList<>(ids));
        data.put("rejected", rejected);
        return data;
    }

    /** 把 ids 从该用户所有消息行的 cards 里摘除，返回被改写的行数。UI 与 Runner 共用。 */
    private int stripCardsFromRows(String userId, Set<String> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        List<DiscoveryChatMessage> rows = chatRepository.findTop500ByUserIdOrderByIdDesc(userId);
        int touched = 0;
        for (DiscoveryChatMessage row : rows) {
            List<Map<String, Object>> cards = cardsList(row.getCards());
            if (cards.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> kept = cards.stream()
                    .filter(c -> !ids.contains(String.valueOf(c.getOrDefault("arxivId", ""))))
                    .toList();
            if (kept.size() == cards.size()) {
                continue;
            }
            try {
                row.setCards(objectMapper.writeValueAsString(kept));
                chatRepository.save(row);
                touched += 1;
            } catch (Exception e) {
                logger.warn("候选移除落行失败 => rowId: {}, error: {}", row.getId(), e.getMessage());
            }
        }
        return touched;
    }

    // --------------------------------------------------------- 轮询与终态

    @PostConstruct
    public void start() {
        for (DiscoveryChatMessage row : chatRepository.findByTurnStatus(DiscoveryChatMessage.TURN_STATUS_RUNNING)) {
            row.setTurnStatus(DiscoveryChatMessage.TURN_STATUS_FAILED);
            row.setReasonCode("java_restart");
            chatRepository.save(row);
            runnerClient.dispose(row.getTurnId());
        }
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "discovery-chat-poller");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::pollActiveTurns, 3, 3, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (poller != null) {
            poller.shutdownNow();
        }
    }

    private void pollActiveTurns() {
        for (String turnId : List.copyOf(activeTurns.keySet())) {
            try {
                pollTurn(turnId);
            } catch (Exception e) {
                logger.warn("轮询聊天 turn 失败 => turnId: {}, error: {}", turnId, e.getMessage());
            }
        }
    }

    private void pollTurn(String turnId) {
        DiscoveryChatMessage row = chatRepository.findByTurnId(turnId).orElse(null);
        if (row == null || !DiscoveryChatMessage.TURN_STATUS_RUNNING.equals(row.getTurnStatus())) {
            activeTurns.remove(turnId);
            discardTurnState(turnId);
            runnerClient.dispose(turnId);
            return;
        }
        JsonNode snapshot;
        try {
            snapshot = runnerClient.snapshot(turnId);
        } catch (Exception e) {
            int failures = activeTurns.merge(turnId, 1, Integer::sum);
            if (failures >= 2) {
                finalizePoll(row, null, DiscoveryChatMessage.TURN_STATUS_FAILED, "runner_lost");
            }
            return;
        }
        if (snapshot == null) {
            finalizePoll(row, null, DiscoveryChatMessage.TURN_STATUS_FAILED, "runner_lost");
            return;
        }
        activeTurns.put(turnId, 0);
        String status = snapshot.path("status").asText("RUNNING");
        if (!"RUNNING".equals(status) && !"STARTING".equals(status)) {
            finalizePoll(row, snapshot, switch (status) {
                case "COMPLETED" -> DiscoveryChatMessage.TURN_STATUS_COMPLETED;
                case "CANCELLED" -> DiscoveryChatMessage.TURN_STATUS_CANCELLED;
                default -> DiscoveryChatMessage.TURN_STATUS_FAILED;
            }, snapshot.path("reasonCode").asText(null));
        } else {
            applyLiveFields(row, snapshot);
        }
    }

    private void finalizePoll(DiscoveryChatMessage row, JsonNode snapshot, String status, String reasonCode) {
        if (snapshot != null) {
            applyLiveFields(row, snapshot);
        }
        if (!DiscoveryChatMessage.TURN_STATUS_RUNNING.equals(row.getTurnStatus())) {
            // 用户已先行取消/终态，尊重第一个终态
        } else {
            row.setTurnStatus(status);
            row.setReasonCode(reasonCode);
        }
        chatRepository.save(row);
        activeTurns.remove(row.getTurnId());
        discardTurnState(row.getTurnId());
        runnerClient.dispose(row.getTurnId());
        LogUtils.logBusiness("DISCOVERY_AGENT_TURN", row.getUserId(),
                "聊天 turn 终态: turnId=%s, status=%s, reason=%s",
                row.getTurnId(), row.getTurnStatus(), row.getReasonCode());
    }

    private void finalizeRow(DiscoveryChatMessage row, String status, String reasonCode) {
        row.setTurnStatus(status);
        row.setReasonCode(reasonCode);
        chatRepository.save(row);
        activeTurns.remove(row.getTurnId());
        discardTurnState(row.getTurnId());
    }

    private void applyLiveFields(DiscoveryChatMessage row, JsonNode snapshot) {
        try {
            row.setContent(snapshot.path("assistantText").asText(""));
            if (snapshot.has("cards")) {
                row.setCards(objectMapper.writeValueAsString(jsonToList(snapshot.path("cards"))));
            }
            if (snapshot.hasNonNull("importResults")) {
                row.setImportResults(objectMapper.writeValueAsString(jsonToList(snapshot.path("importResults"))));
            }
            if (snapshot.has("queries")) {
                row.setSearchLog(objectMapper.writeValueAsString(jsonToList(snapshot.path("queries"))));
            }
            if (snapshot.has("usage")) {
                row.setTokenUsage(objectMapper.writeValueAsString(usageOf(snapshot.path("usage"))));
            }
            if (snapshot.has("model")) {
                row.setModel(snapshot.path("model").asText(""));
            }
            if (!snapshot.path("reasonCode").asText("").isEmpty()) {
                row.setReasonCode(snapshot.path("reasonCode").asText());
            }
            chatRepository.save(row);
        } catch (Exception e) {
            logger.warn("应用聊天快照失败 => turnId: {}, error: {}", row.getTurnId(), e.getMessage());
        }
    }

    // --------------------------------------------------------- 窗口与记忆组装

    private record Window(List<DiscoveryRunnerClient.HistoryMessage> history,
                          List<String> cardIds, List<Map<String, Object>> cards, int chars) {
    }

    /** CUTOFF 之后最近 40 条且 ≤24k 字符；助手文本后追加候选卡片摘要，保证下一轮模型看得到。 */
    private Window assembleWindow(String userId) {
        List<DiscoveryChatMessage> rows = chatRepository.findTop500ByUserIdOrderByIdDesc(userId);
        long cutoffId = 0;
        for (DiscoveryChatMessage row : rows) {
            if (DiscoveryChatMessage.ROLE_CUTOFF.equals(row.getRole())) {
                cutoffId = row.getId();
                break;
            }
        }
        List<DiscoveryRunnerClient.HistoryMessage> newestFirst = new ArrayList<>();
        Set<String> cardIds = new LinkedHashSet<>();
        int chars = 0;
        for (DiscoveryChatMessage row : rows) {
            if (row.getId() <= cutoffId || newestFirst.size() >= WINDOW_MAX_MESSAGES || chars >= WINDOW_MAX_CHARS) {
                break;
            }
            if (DiscoveryChatMessage.ROLE_USER.equals(row.getRole())) {
                String text = String.valueOf(row.getContent() == null ? "" : row.getContent());
                newestFirst.add(new DiscoveryRunnerClient.HistoryMessage("USER", text));
                chars += text.length();
            } else if (DiscoveryChatMessage.ROLE_ASSISTANT.equals(row.getRole())
                    && DiscoveryChatMessage.TURN_STATUS_COMPLETED.equals(row.getTurnStatus())) {
                String text = String.valueOf(row.getContent() == null ? "" : row.getContent());
                String action = actionDigest(row);
                if (!action.isEmpty()) {
                    text += "\n[本轮动作：" + action + "]";
                }
                String digest = cardsDigest(row);
                if (!digest.isEmpty()) {
                    text += "\n[本轮候选：" + digest + "]";
                }
                newestFirst.add(new DiscoveryRunnerClient.HistoryMessage("ASSISTANT", text));
                chars += text.length();
            }
        }
        // 老到新排列传入 runner
        List<DiscoveryRunnerClient.HistoryMessage> history = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(history);
        // 窗口内全部卡片的 id（含被字符预算裁掉的轮次之前的部分）
        Map<String, Map<String, Object>> cardsById = new LinkedHashMap<>();
        for (DiscoveryChatMessage row : rows) {
            if (DiscoveryChatMessage.ROLE_ASSISTANT.equals(row.getRole()) && row.getId() > cutoffId) {
                for (Map<String, Object> card : cardsList(row.getCards())) {
                    String id = String.valueOf(card.getOrDefault("arxivId", ""));
                    if (!id.isEmpty() && cardsById.size() < HISTORY_CARDS_MAX) {
                        cardsById.putIfAbsent(id, card);
                    }
                }
            }
        }
        return new Window(history, new ArrayList<>(cardsById.keySet()), new ArrayList<>(cardsById.values()), chars);
    }

    /** 当轮检索动作摘要，随历史回注，续轮模型据此知悉自己已用过的检索词。 */
    private String actionDigest(DiscoveryChatMessage row) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> q : cardsList(row.getSearchLog())) {
            String term = String.valueOf(q.getOrDefault("q", ""));
            if (term.isEmpty()) {
                continue;
            }
            if (sb.length() >= 500) {
                sb.append("…");
                break;
            }
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("「").append(term).append("」").append(q.getOrDefault("hits", "")).append(" 条");
        }
        return sb.toString();
    }

    private String cardsDigest(DiscoveryChatMessage row) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> card : cardsList(row.getCards())) {
            if (sb.length() >= 400) {
                break;
            }
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(card.getOrDefault("arxivId", "")).append("《")
                    .append(String.valueOf(card.getOrDefault("title", ""))) .append("》")
                    .append(card.getOrDefault("publishedYear", "")).append("（").append(card.getOrDefault("libraryStatus", "")).append("）");
        }
        return sb.toString();
    }

    /** ROLE → PREF → INSIGHT（新到旧），块上限内丢最旧 INSIGHT。 */
    private List<DiscoveryRunnerClient.MemoryItem> assembleMemoryBlock(String userId) {
        List<DiscoveryMemory> all = memoryRepository.findByUserIdOrderByUpdatedAtAsc(userId).stream()
                .filter(DiscoveryMemory::isActive)
                .sorted(Comparator
                        .comparing((DiscoveryMemory m) -> kindRank(m.getKind()))
                        .thenComparing(Comparator.comparing(DiscoveryMemory::getUpdatedAt).reversed()))
                .toList();
        List<DiscoveryMemory> kept = new ArrayList<>();
        int chars = 0;
        for (DiscoveryMemory memory : all) {
            int length = memory.getContent().length();
            if (chars + length > MEMORY_BLOCK_MAX_CHARS
                    && DiscoveryMemory.KIND_INSIGHT.equals(memory.getKind())
                    && !kept.isEmpty()) {
                continue; // 超预算时丢后续（更旧的）INSIGHT
            }
            kept.add(memory);
            chars += length;
        }
        List<DiscoveryRunnerClient.MemoryItem> result = new ArrayList<>();
        for (DiscoveryMemory memory : kept) {
            result.add(new DiscoveryRunnerClient.MemoryItem(memory.getKind(), memory.getContent()));
        }
        return result;
    }

    private static int kindRank(String kind) {
        return switch (kind) {
            case DiscoveryMemory.KIND_ROLE -> 0;
            case DiscoveryMemory.KIND_PREF -> 1;
            default -> 2;
        };
    }

    // --------------------------------------------------------- 工具方法

    private void discardTurnState(String turnId) {
        turnCandidates.remove(turnId);
        turnCorpus.remove(turnId);
    }

    private Optional<DiscoveryChatMessage> findRunningTurn(String userId) {
        return chatRepository.findFirstByUserIdAndRoleOrderByIdDesc(userId, DiscoveryChatMessage.ROLE_ASSISTANT)
                .filter(r -> DiscoveryChatMessage.TURN_STATUS_RUNNING.equals(r.getTurnStatus()));
    }

    private DiscoveryChatMessage requireRunningChatTurn(String turnId, String userId) {
        DiscoveryChatMessage row = chatRepository.findByTurnId(turnId == null ? "" : turnId)
                .orElseThrow(() -> new CustomException("turn 不存在", HttpStatus.CONFLICT));
        if (!row.getUserId().equals(userId) || !DiscoveryChatMessage.ROLE_ASSISTANT.equals(row.getRole())
                || !DiscoveryChatMessage.TURN_STATUS_RUNNING.equals(row.getTurnStatus())) {
            throw new CustomException("turn 不属于该用户或已终态", HttpStatus.CONFLICT);
        }
        return row;
    }

    private DiscoveryChatMessage insertRow(String userId, String role, String content) {
        DiscoveryChatMessage row = new DiscoveryChatMessage();
        row.setUserId(userId);
        row.setRole(role);
        row.setContent(content);
        chatRepository.save(row);
        return row;
    }

    private String buildStatusText(String userId) {
        Window window = assembleWindow(userId);
        long memoryCount = memoryRepository.findByUserIdOrderByUpdatedAtAsc(userId).stream()
                .filter(DiscoveryMemory::isActive).count();
        long todayTurns = 0;
        long todayTokens = 0;
        LocalDate today = LocalDate.now();
        for (DiscoveryChatMessage row : chatRepository.findTop500ByUserIdOrderByIdDesc(userId)) {
            if (row.getCreatedAt() == null || !row.getCreatedAt().toLocalDate().isEqual(today)) {
                continue;
            }
            if (DiscoveryChatMessage.ROLE_ASSISTANT.equals(row.getRole()) && row.getTokenUsage() != null) {
                todayTurns += 1;
                Map<String, Long> usage = usageOf(row.getTokenUsage());
                todayTokens += usage.getOrDefault("inputTokens", 0L) + usage.getOrDefault("outputTokens", 0L);
            }
        }
        return String.format(
                "上下文窗口：%d 条 / 约 %d 字符（上限 %d，%.0f%%）。长期记忆：%d 条。今日 turn：%d，累计 %d tokens。",
                window.history().size(), window.chars(), WINDOW_MAX_CHARS,
                100.0 * window.chars() / WINDOW_MAX_CHARS, memoryCount, todayTurns, todayTokens);
    }

    private String validateKind(String kind, boolean allowRole) {
        String trimmed = kind == null ? "" : kind.trim();
        if (DiscoveryMemory.KIND_ROLE.equals(trimmed) && !allowRole) {
            throw new CustomException("kind 不支持", HttpStatus.BAD_REQUEST);
        }
        if (!DiscoveryMemory.KIND_ROLE.equals(trimmed)
                && !DiscoveryMemory.KIND_PREF.equals(trimmed)
                && !DiscoveryMemory.KIND_INSIGHT.equals(trimmed)) {
            throw new CustomException("kind 只能是 ROLE/PREF/INSIGHT", HttpStatus.BAD_REQUEST);
        }
        return trimmed;
    }

    private String validateMemoryContent(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            throw new CustomException("记忆内容不能为空", HttpStatus.BAD_REQUEST);
        }
        if (trimmed.length() > MEMORY_CONTENT_MAX) {
            throw new CustomException("记忆内容超过 " + MEMORY_CONTENT_MAX + " 字", HttpStatus.BAD_REQUEST);
        }
        return trimmed;
    }

    private DiscoveryMemory ownedMemory(String userId, Long id) {
        return memoryRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new CustomException("记忆条目不存在", HttpStatus.NOT_FOUND));
    }

    private Map<String, Object> memoryDto(DiscoveryMemory memory) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", memory.getId());
        dto.put("kind", memory.getKind());
        dto.put("content", memory.getContent());
        dto.put("source", memory.getSource());
        dto.put("active", memory.isActive());
        dto.put("updatedAt", memory.getUpdatedAt() == null ? null : memory.getUpdatedAt().toString());
        return dto;
    }

    private Map<String, Object> messageDto(DiscoveryChatMessage row) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", row.getId());
        dto.put("role", row.getRole());
        dto.put("kind", row.getKind());
        dto.put("turnId", row.getTurnId());
        dto.put("turnStatus", row.getTurnStatus());
        dto.put("reasonCode", row.getReasonCode());
        dto.put("content", row.getContent());
        dto.put("cards", cardsList(row.getCards()));
        dto.put("importResults", row.getImportResults() == null ? null : importResultsList(row.getImportResults()));
        dto.put("model", row.getModel());
        dto.put("createdAt", row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
        return dto;
    }

    private Map<String, Object> hitToItem(ArxivSearchHit hit) {
        ArxivPaper paper = hit.paper();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("arxivId", paper.arxivId());
        item.put("title", paper.title());
        item.put("summary", paper.summary());
        item.put("authors", paper.authors());
        item.put("categories", paper.categories());
        item.put("publishedYear", paper.publishedYear());
        item.put("absUrl", paper.absUrl());
        item.put("pdfUrl", paper.pdfUrl());
        item.put("libraryStatus", hit.libraryStatus());
        return item;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cardsList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> importResultsList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<Map<String, Object>> jsonToList(JsonNode node) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> {
                if (n.isObject()) {
                    list.add(objectMapper.convertValue(n, Map.class));
                } else {
                    Map<String, Object> single = new LinkedHashMap<>();
                    single.put("text", n.asText());
                    list.add(single);
                }
            });
        }
        return list;
    }

    private Map<String, Long> usageOf(JsonNode usage) {
        Map<String, Long> data = new LinkedHashMap<>();
        data.put("inputTokens", usage.path("inputTokens").asLong(0));
        data.put("outputTokens", usage.path("outputTokens").asLong(0));
        return data;
    }

    private Map<String, Long> usageOf(String json) {
        try {
            return objectMapper.readValue(json == null ? "{\"inputTokens\":0,\"outputTokens\":0}" : json,
                    new TypeReference<Map<String, Long>>() {
                    });
        } catch (Exception e) {
            Map<String, Long> data = new LinkedHashMap<>();
            data.put("inputTokens", 0L);
            data.put("outputTokens", 0L);
            return data;
        }
    }

    // --------------------------------------------------------- turn 预算（Redis 模式同 import 预算）

    private void enforceTurnBudget(String userId) {
        if (turnsPerHour <= 0) {
            return;
        }
        try {
            Object current = redisTemplate.opsForValue().get(budgetKey(userId));
            int used = current == null ? 0 : Integer.parseInt(String.valueOf(current));
            if (used >= turnsPerHour) {
                throw new CustomException("每小时最多 " + turnsPerHour + " 条智能探索消息，请稍后再试",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("智能探索 turn 配额检查失败，放行 => userId: {}, error: {}", userId, e.getMessage());
        }
    }

    private void recordLaunch(String userId) {
        if (turnsPerHour <= 0) {
            return;
        }
        try {
            String key = budgetKey(userId);
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofHours(2));
            }
        } catch (Exception e) {
            logger.warn("智能探索 turn 配额计数失败 => userId: {}, error: {}", userId, e.getMessage());
        }
    }

    private String budgetKey(String userId) {
        return "paperloom:discovery:agent:turn:" + userId + ":" + LocalDateTime.now().format(HOUR_BUCKET);
    }
}
