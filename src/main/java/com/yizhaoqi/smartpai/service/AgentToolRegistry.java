package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.DocStats;
import co.elastic.clients.elasticsearch._types.StoreStats;
import co.elastic.clients.elasticsearch.indices.IndicesStatsResponse;
import co.elastic.clients.elasticsearch.indices.stats.IndicesStats;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.PaperSearchIndex;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Service
public class AgentToolRegistry {

    private static final int DEFAULT_PAGE_BATCH_SIZE = 5;
    private static final int MAX_PAGE_BATCH_SIZE = 20;
    private static final int DEFAULT_SUMMARY_BATCH_SIZE = 5;
    private static final int MAX_SUMMARY_BATCH_SIZE = 20;

    private final PaperRetrievalService paperRetrievalService;
    private final DeepSeekClient deepSeekClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ElasticsearchClient elasticsearchClient;
    private final PaperRepository paperRepository;
    private final List<AgentTool> tools;
    private final Map<String, ToolHandler> handlers;

    public AgentToolRegistry(PaperRetrievalService paperRetrievalService,
                             DeepSeekClient deepSeekClient,
                             StringRedisTemplate stringRedisTemplate,
                             ElasticsearchClient elasticsearchClient,
                             PaperRepository paperRepository) {
        this.paperRetrievalService = paperRetrievalService;
        this.deepSeekClient = deepSeekClient;
        this.stringRedisTemplate = stringRedisTemplate;
        this.elasticsearchClient = elasticsearchClient;
        this.paperRepository = paperRepository;
        this.tools = List.of(
                searchPapersTool(),
                generateSummaryTool(),
                submitFeedbackTool(),
                paperStatsTool()
        );
        this.handlers = Map.of(
                "search_papers", this::executeSearchPapers,
                "generate_summary", this::executeGenerateSummary,
                "submit_feedback", this::executeSubmitFeedback,
                "paper_stats", this::executePaperStats
        );
    }

    public List<AgentTool> getTools() {
        return tools;
    }

    public Optional<AgentTool> getTool(String name) {
        return tools.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst();
    }

    public ToolExecutionResult executeTool(String name, Map<String, Object> arguments, String userId) {
        return executeTool(name, arguments, userId, null);
    }

    public ToolExecutionResult executeTool(String name,
                                           Map<String, Object> arguments,
                                           String userId,
                                           Consumer<String> onChunk) {
        ToolHandler handler = handlers.get(name);
        if (handler == null) {
            throw new IllegalArgumentException("未注册的工具: " + name);
        }
        return handler.execute(arguments == null ? Collections.emptyMap() : arguments, userId, onChunk);
    }

    private ToolExecutionResult executeSearchPapers(Map<String, Object> arguments,
                                                    String userId,
                                                    Consumer<String> onChunk) {
        requireUserId(userId);
        String query = getRequiredString(arguments, "query");
        int pageBatchSize = getInt(arguments, "pageBatchSize", DEFAULT_PAGE_BATCH_SIZE, 1, MAX_PAGE_BATCH_SIZE);

        PaperRetrievalService.RetrievalResult retrievalResult =
                paperRetrievalService.retrieve(query, userId, RetrievalBudget.forPageBatch(pageBatchSize));
        List<SearchResult> results = retrievalResult.results();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("pageBatchSize", pageBatchSize);
        data.put("intent", retrievalResult.plan().intent().name());
        data.put("attemptedQueries", retrievalResult.attemptedQueries());
        data.put("retrievalRoutes", List.copyOf(retrievalResult.routeHitCounts().keySet()));
        data.put("routeHitCounts", retrievalResult.routeHitCounts());
        data.put("finalHitCount", retrievalResult.finalHitCount());
        data.put("diagnostics", retrievalResult.diagnostics());
        data.put("queryExpansionUsed", retrievalResult.attemptedQueries().size() > 1);
        data.put("results", results);
        return new ToolExecutionResult("search_papers", true, formatSearchResults(results, retrievalResult), data);
    }

    private ToolExecutionResult executeGenerateSummary(Map<String, Object> arguments,
                                                       String userId,
                                                       Consumer<String> onChunk) {
        requireUserId(userId);
        String topic = getRequiredString(arguments, "topic");
        int pageBatchSize = getInt(arguments, "pageBatchSize", DEFAULT_SUMMARY_BATCH_SIZE, 1, MAX_SUMMARY_BATCH_SIZE);

        List<SearchResult> results = paperRetrievalService.retrieve(topic, userId, RetrievalBudget.forPageBatch(pageBatchSize)).results();
        String summary = deepSeekClient.summarize(userId, topic, results, onChunk);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", topic);
        data.put("pageBatchSize", pageBatchSize);
        data.put("sourceCount", results.size());
        data.put("sources", results);

        String content = "主题：" + topic + "\n"
                + "检索片段数：" + results.size() + "\n\n"
                + summary;
        return new ToolExecutionResult("generate_summary", true, content, data, onChunk != null);
    }

    private ToolExecutionResult executeSubmitFeedback(Map<String, Object> arguments,
                                                      String userId,
                                                      Consumer<String> onChunk) {
        requireUserId(userId);
        String rating = getRequiredString(arguments, "rating").toLowerCase(Locale.ROOT);
        if (!"good".equals(rating) && !"bad".equals(rating)) {
            throw new IllegalArgumentException("rating 只允许 good 或 bad");
        }
        String reason = getOptionalString(arguments, "reason");
        String key = "feedback:" + userId;
        String field = String.valueOf(System.currentTimeMillis());
        String value = reason == null || reason.isBlank()
                ? "rating=" + rating
                : "rating=" + rating + "; reason=" + reason;
        stringRedisTemplate.opsForHash().put(key, field, value);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        data.put("field", field);
        data.put("rating", rating);
        data.put("reason", reason);
        return new ToolExecutionResult("submit_feedback", true, "已记录用户反馈: " + value, data);
    }

    private ToolExecutionResult executePaperStats(Map<String, Object> arguments,
                                                  String userId,
                                                  Consumer<String> onChunk) {
        try {
            IndicesStatsResponse statsResponse = elasticsearchClient.indices().stats(s -> s.index(PaperSearchIndex.INDEX_NAME));
            IndicesStats indexStats = statsResponse.indices().get(PaperSearchIndex.INDEX_NAME);
            DocStats docStats = indexStats != null && indexStats.total() != null ? indexStats.total().docs() : null;
            StoreStats storeStats = indexStats != null && indexStats.total() != null ? indexStats.total().store() : null;

            long paperCount = paperRepository.count();
            long fragmentCount = docStats != null ? docStats.count() : 0L;
            Long deletedFragmentCount = docStats != null ? docStats.deleted() : null;
            Long storeSizeInBytes = storeStats != null ? storeStats.sizeInBytes() : null;
            LocalDateTime latestUpdatedAt = paperRepository.findFirstByOrderByMergedAtDesc()
                    .map(this::resolveLatestUpdatedAt)
                    .orElse(null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("index", PaperSearchIndex.INDEX_NAME);
            data.put("paperCount", paperCount);
            data.put("fragmentCount", fragmentCount);
            data.put("deletedFragmentCount", deletedFragmentCount);
            data.put("storeSizeInBytes", storeSizeInBytes);
            data.put("latestUpdatedAt", latestUpdatedAt);

            return new ToolExecutionResult("paper_stats", true, formatPaperStats(data), data);
        } catch (Exception e) {
            throw new RuntimeException("获取论文库统计信息失败", e);
        }
    }

    private AgentTool searchPapersTool() {
        return new AgentTool(
                "search_papers",
                "在论文库中搜索与用户问题相关的论文片段。当问题需要论文证据支持时应调用；普通问候、闲聊、纯创作、翻译、通用代码/常识问题，或用户明确要求跳过论文检索时不要调用。",
                objectSchema(Map.of(
                        "query", stringSchema("用于论文检索的查询语句。应保留用户原话中的核心术语、方法名、数据集、指标和限定词，可包含必要的等价改写。"),
                        "pageBatchSize", integerSchema("ES 技术分页批次，默认 5。不是答案候选数量上限。"),
                        "intent", stringSchema("可选结构化检索意图。后端不会用固定短语表猜测用户语义。"),
                        "expand", booleanSchema("是否允许后端进行多查询扩展，默认 true。"),
                        "preferredSourceKinds", arrayStringSchema("可选偏好的证据类型，例如 TEXT、TABLE、FIGURE、CHART、FORMULA。")
                ), List.of("query"))
        );
    }

    private AgentTool generateSummaryTool() {
        return new AgentTool(
                "generate_summary",
                "对指定主题的论文片段生成结构化摘要。适合用户要求整理、总结、归纳、提炼论文内容时调用；本工具内部会二次调用大模型完成摘要，外层 ReAct 循环只应接收结果，不要把内部摘要过程当作新的工具计划。",
                objectSchema(Map.of(
                        "topic", stringSchema("需要从论文库中整理和总结的主题。"),
                        "pageBatchSize", integerSchema("ES 技术分页批次，默认 5。不是摘要来源数量上限。")
                ), List.of("topic"))
        );
    }

    private AgentTool submitFeedbackTool() {
        Map<String, Object> ratingSchema = stringSchema("用户对当前回答的评价，只能是 good 或 bad。");
        ratingSchema.put("enum", List.of("good", "bad"));
        return new AgentTool(
                "submit_feedback",
                "当用户明确表达对回答满意、不满意、点赞、点踩、纠错或要求记录反馈时调用，用于记录反馈以优化后续回答质量；不要在没有明确评价意图时推断调用。",
                objectSchema(Map.of(
                        "rating", ratingSchema,
                        "reason", stringSchema("用户给出的满意或不满意原因，可为空。")
                ), List.of("rating"))
        );
    }

    private AgentTool paperStatsTool() {
        return new AgentTool(
                "paper_stats",
                "返回当前论文库的统计信息，包括 MySQL 论文总数、Elasticsearch chunk 总数、索引存储量和最近更新时间。仅当用户询问论文库规模、论文数量、chunk 数量、更新时间或索引状态时调用。",
                objectSchema(Collections.emptyMap(), Collections.emptyList())
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

    private String formatSearchResults(List<SearchResult> results, PaperRetrievalService.RetrievalResult retrievalResult) {
        if (results == null || results.isEmpty()) {
            return "我没有找到足够可靠的论文证据来回答这个问题。"
                    + "\n已尝试查询：" + String.join(" | ", retrievalResult.attemptedQueries())
                    + "\n各路由命中数：" + retrievalResult.routeHitCounts();
        }

        StringBuilder output = new StringBuilder("检索到 ").append(results.size()).append(" 个论文片段。")
                .append("\n意图：").append(retrievalResult.plan().intent())
                .append("\n已尝试查询：").append(String.join(" | ", retrievalResult.attemptedQueries()))
                .append("\n")
                .append("请先判断问题类型（summary/method/experiment/limitation/comparison/factual lookup），在上下文预算内选择足够回答问题的证据，并合并重复证据。")
                .append("最终回答使用 **结论** / **依据** / **限制**，不要按检索片段顺序机械罗列。")
                .append("如果用户是在推荐或查找相关论文，必须列出论文标题和推荐理由，每个推荐项末尾放 [n]。")
                .append("如果证据不足，请在限制中说明不确定性；不得声称论文库暂无相关信息。")
                .append("引用标记只输出 [1]、[2] 这类紧凑锚点；不要把论文标题、页码、paperId、chunk、score 或 References 列表塞进引用标记，也不要在结尾堆叠所有引用。正文可以写论文标题、方法名、数据集和推荐理由。");
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            output.append("\n\n[").append(i + 1).append("] ");
            if (result.getPaperTitle() != null && !result.getPaperTitle().isBlank()) {
                output.append(result.getPaperTitle()).append(" ");
            }
            output.append("(paperId=").append(result.getPaperId())
                    .append(", chunkId=").append(result.getChunkId());
            if (result.getSourceKind() != null) {
                output.append(", sourceKind=").append(result.getSourceKind());
            }
            if (result.getRetrievalQuery() != null) {
                output.append(", retrievalQuery=").append(result.getRetrievalQuery());
            }
            if (result.getRetrievalRoute() != null) {
                output.append(", retrievalRoute=").append(result.getRetrievalRoute());
            }
            if (result.getPageNumber() != null) {
                output.append(", page=").append(result.getPageNumber());
            }
            if (result.getScore() != null) {
                output.append(", score=").append(String.format(Locale.ROOT, "%.4f", result.getScore()));
            }
            output.append(")\n")
                    .append(limitText(result.getMatchedChunkText() != null ? result.getMatchedChunkText() : result.getTextContent(), 1200));
        }
        return output.toString();
    }

    private String formatPaperStats(Map<String, Object> data) {
        return "论文库统计："
                + "\n- MySQL 论文总数：" + data.get("paperCount")
                + "\n- Elasticsearch chunk 总数：" + data.get("fragmentCount")
                + "\n- ES 已删除片段数：" + nullToDash(data.get("deletedFragmentCount"))
                + "\n- ES 存储大小(bytes)：" + nullToDash(data.get("storeSizeInBytes"))
                + "\n- 最近更新时间：" + nullToDash(data.get("latestUpdatedAt"));
    }

    private LocalDateTime resolveLatestUpdatedAt(Paper paper) {
        if (paper.getMergedAt() != null) {
            return paper.getMergedAt();
        }
        return paper.getCreatedAt();
    }

    private String getRequiredString(Map<String, Object> arguments, String name) {
        String value = getOptionalString(arguments, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    private String getOptionalString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    private int getInt(Map<String, Object> arguments, String name, int defaultValue, int min, int max) {
        Object raw = arguments.get(name);
        if (raw == null || String.valueOf(raw).isBlank()) {
            return defaultValue;
        }

        int value;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else {
            value = Integer.parseInt(String.valueOf(raw));
        }
        return Math.max(min, Math.min(max, value));
    }

    private String limitText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private String nullToDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("工具调用缺少 userId，无法执行带权限的论文检索或反馈操作");
        }
    }

    @FunctionalInterface
    private interface ToolHandler {
        ToolExecutionResult execute(Map<String, Object> arguments, String userId, Consumer<String> onChunk);
    }

    public record AgentTool(
            String name,
            String description,
            Map<String, Object> parameters
    ) {
    }

    public record ToolExecutionResult(
            String toolName,
            boolean success,
            String content,
            Map<String, Object> data,
            boolean streamedToUser
    ) {
        public ToolExecutionResult(String toolName,
                                   boolean success,
                                   String content,
                                   Map<String, Object> data) {
            this(toolName, success, content, data, false);
        }
    }
}
