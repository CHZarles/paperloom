package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class LlmTaskRouter implements TaskRouter {

    private static final double MIN_CONFIDENCE = 0.45d;
    private static final int MAX_COMPLETION_TOKENS = 500;

    private final LlmProviderRouter llmProviderRouter;
    private final ObjectMapper objectMapper;

    public LlmTaskRouter(LlmProviderRouter llmProviderRouter, ObjectMapper objectMapper) {
        this.llmProviderRouter = llmProviderRouter;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskRoutingResult route(TaskRoutingRequest request) {
        TaskRoutingRequest safeRequest = request == null
                ? new TaskRoutingRequest("", "", "", SourceScope.auto())
                : request;
        if (safeRequest.userMessage().isBlank()) {
            return failure(TaskRoutingFailure.ReasonCode.EMPTY_QUERY, "empty_query", Map.of());
        }
        try {
            LlmProviderRouter.ReActTurn turn = llmProviderRouter.completeReActTurn(
                    safeRequest.userId(),
                    messages(safeRequest),
                    List.of(),
                    MAX_COMPLETION_TOKENS
            );
            return parse(turn.content(), safeRequest.userMessage());
        } catch (Exception exception) {
            return failure(TaskRoutingFailure.ReasonCode.LLM_UNAVAILABLE, "router_llm_unavailable", Map.of(
                    "exception", exception.getClass().getSimpleName()
            ));
        }
    }

    private List<Map<String, Object>> messages(TaskRoutingRequest request) {
        String system = """
                You are PaperLoom's primary task router for a research-paper RAG workbench.
                Return exactly one JSON object and no prose.

                Valid taskType values:
                - LIBRARY_STATUS: product control-plane questions about how many papers are accessible, searchable, parsing, indexing, failed, or available.
                - PAPER_DISCOVERY: find, recommend, list, or discover papers by topic from the user's uploaded paper library.
                - PAPER_QA: answer by reading paper content, methods, experiments, limitations, claims, tables, figures, formulas, or conclusions.
                - REFERENCE_QA: explain a cited reference such as [1] or a selected citation.
                - FOLLOW_UP: low-information continuation that depends on prior resolved paper focus.
                - CLARIFY: needs a user choice or is unsupported in the paper chat surface.
                - SMALLTALK: greetings or short acknowledgements.

                Valid operation values:
                DIRECT_RESPONSE, ASK_CLARIFICATION, COUNT_SEARCHABLE_PAPERS, LIST_ACCESSIBLE_PAPERS,
                SEARCH_PAPER_METADATA, ANSWER_FROM_EVIDENCE, INSPECT_REFERENCE.

                Routing rules:
                - Classify by task semantics, not by fixed surface phrases.
                - Library status/count/searchability questions are LIBRARY_STATUS and must not read chunks.
                - Paper recommendation/discovery questions are PAPER_DISCOVERY and search paper metadata first.
                - Paper-content questions are PAPER_QA.
                - AUTO_SOURCE_QA is not a valid taskType.

                JSON fields: taskType, operation, query, confidence, reason.
                """;
        String user = "scopeMode=" + request.sourceScope().mode()
                + "\nsourceCount=" + request.sourceScope().paperIds().size()
                + "\nmessage=" + request.userMessage();
        return List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)
        );
    }

    private TaskRoutingResult parse(String raw, String originalQuery) {
        try {
            JsonNode node = objectMapper.readTree(extractJsonObject(raw));
            TaskType taskType = parseTaskType(firstText(node, "taskType", "route"));
            if (taskType == null) {
                return failure(TaskRoutingFailure.ReasonCode.INVALID_TASK, "invalid_task", Map.of("raw", safeRaw(raw)));
            }
            double confidence = clampConfidence(node.path("confidence").asDouble(0d));
            if (confidence < MIN_CONFIDENCE) {
                return failure(TaskRoutingFailure.ReasonCode.LOW_CONFIDENCE, "low_confidence", Map.of(
                        "taskType", taskType.name(),
                        "confidence", confidence
                ));
            }
            TaskOperation operation = parseOperation(node.path("operation").asText(""));
            String query = node.path("query").asText(originalQuery).trim();
            if (query.isBlank()) {
                query = "";
            }
            String reason = node.path("reason").asText("").trim();
            return TaskRoutingResult.routed(new TaskDecision(taskType, operation, query, confidence, reason));
        } catch (Exception exception) {
            return failure(TaskRoutingFailure.ReasonCode.INVALID_JSON, "invalid_json", Map.of("raw", safeRaw(raw)));
        }
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private TaskType parseTaskType(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.isBlank()) {
            return null;
        }
        if ("LIBRARY_SEARCH".equals(value) || "PAPER_RECOMMENDATION".equals(value)) {
            return TaskType.PAPER_DISCOVERY;
        }
        if ("MANUAL_SOURCE_QA".equals(value)) {
            return TaskType.PAPER_QA;
        }
        if ("AUTO_SOURCE_QA".equals(value)) {
            return null;
        }
        try {
            return TaskType.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private TaskOperation parseOperation(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.isBlank()) {
            return null;
        }
        try {
            return TaskOperation.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractJsonObject(String raw) {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private double clampConfidence(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0d;
        }
        return Math.max(0d, Math.min(1d, value));
    }

    private TaskRoutingResult failure(TaskRoutingFailure.ReasonCode reasonCode,
                                      String diagnostics,
                                      Map<String, Object> metadata) {
        return TaskRoutingResult.failed(new TaskRoutingFailure(
                reasonCode,
                "我没有可靠判断这个问题应该使用哪种论文能力，因此不会执行检索。请换一种更明确的问法。",
                diagnostics,
                metadata
        ));
    }

    private String safeRaw(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= 500 ? raw : raw.substring(0, 500);
    }
}
