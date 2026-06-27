package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EvidencePlanner {

    private static final Pattern USER_REFERENCE_PATTERN = Pattern.compile("\\[(\\d+)]");
    private final LlmProviderRouter llmProviderRouter;
    private final ObjectMapper objectMapper;

    public EvidencePlanner(LlmProviderRouter llmProviderRouter, ObjectMapper objectMapper) {
        this.llmProviderRouter = llmProviderRouter;
        this.objectMapper = objectMapper;
    }

    public PlannerAction plan(PlannerContext context) {
        PlannerContext safeContext = context == null
                ? new PlannerContext("", "", PaperAnswerService.Intent.AUTO_SOURCE_QA, SourceScope.auto(), EvidenceLedger.empty())
                : context;
        PlannerAction deterministic = deterministicAction(safeContext);
        if (deterministic != null) {
            return deterministic;
        }
        try {
            LlmProviderRouter.ReActTurn turn = llmProviderRouter.completeReActTurn(
                    safeContext.requesterId(),
                    plannerMessages(safeContext),
                    List.of(),
                    600
            );
            return parseAction(turn.content(), safeContext);
        } catch (Exception exception) {
            return PlannerAction.clarify("planner_failed");
        }
    }

    private PlannerAction deterministicAction(PlannerContext context) {
        if (context.route() == PaperAnswerService.Intent.REFERENCE_QA) {
            Integer referenceNumber = context.scope().referenceNumber();
            if (referenceNumber == null) {
                Matcher matcher = USER_REFERENCE_PATTERN.matcher(context.userQuery());
                if (matcher.find()) {
                    referenceNumber = Integer.parseInt(matcher.group(1));
                }
            }
            if (referenceNumber != null) {
                return new PlannerAction(
                        PlannerActionType.INSPECT_REFERENCE,
                        context.userQuery(),
                        "reference_scope",
                        context.scope().paperIds(),
                        referenceNumber
                );
            }
        }
        if (!context.ledger().evidence().isEmpty()) {
            return new PlannerAction(
                    PlannerActionType.ANSWER_WITH_LEDGER,
                    context.userQuery(),
                    "ledger_ready",
                    context.scope().paperIds(),
                    context.scope().referenceNumber()
            );
        }
        if (context.route() == PaperAnswerService.Intent.LIBRARY_SEARCH) {
            return new PlannerAction(
                    isPaperInventoryQuery(context.userQuery()) ? PlannerActionType.LIST_LIBRARY : PlannerActionType.DISCOVER_PAPERS,
                    librarySearchQuery(context.userQuery()),
                    "library_search",
                    context.scope().paperIds(),
                    context.scope().referenceNumber()
            );
        }
        if (context.route() == PaperAnswerService.Intent.AUTO_SOURCE_QA
                || context.route() == PaperAnswerService.Intent.MANUAL_SOURCE_QA
                || context.route() == PaperAnswerService.Intent.FOLLOW_UP) {
            return new PlannerAction(
                    PlannerActionType.SEARCH_EVIDENCE,
                    context.userQuery(),
                    "paper_qa",
                    context.scope().paperIds(),
                    context.scope().referenceNumber()
            );
        }
        if (context.route() == PaperAnswerService.Intent.CLARIFY) {
            return PlannerAction.clarify("route_requires_clarification");
        }
        return null;
    }

    private boolean isPaperInventoryQuery(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s!！?？。,.，;；:：、\"'“”‘’()（）\\[\\]{}<>《》]+", "");
        return normalized.contains("有什么论文")
                || normalized.contains("有哪些论文")
                || normalized.contains("论文列表")
                || normalized.contains("论文库有什么")
                || normalized.contains("当前论文");
    }

    private String librarySearchQuery(String query) {
        String text = query == null ? "" : query.trim();
        String cleaned = text
                .replaceAll("(?i)recommend papers|related papers", " ")
                .replace("推荐一下", " ")
                .replace("推荐一些", " ")
                .replace("推荐", " ")
                .replace("相关论文", " ")
                .replace("有哪些论文", " ")
                .replace("有什么论文", " ")
                .replace("论文", " ")
                .replace("和", " ")
                .replace("的", " ")
                .replace("相关", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isBlank() ? text : cleaned;
    }

    private List<Map<String, Object>> plannerMessages(PlannerContext context) {
        String system = """
                You are a constrained evidence planner for a research-paper RAG system.
                Return exactly one JSON object and no prose.
                Allowed action values: LIST_LIBRARY, DISCOVER_PAPERS, SEARCH_EVIDENCE, INSPECT_PAGE, INSPECT_REFERENCE, ASK_CLARIFICATION, ANSWER_WITH_LEDGER.
                Do not write final answers, citations, paper ids, chunk ids, or reference mappings.
                Use DISCOVER_PAPERS for recommendation/library discovery.
                Use SEARCH_EVIDENCE when a source set exists or the user asks a paper QA question.
                Use INSPECT_PAGE only after a paper id and page number are already known.
                Use ASK_CLARIFICATION only when the query cannot be answered without user choice.
                JSON fields: action, query, reason, optional pageNumber, optional windowRadius.
                """;
        String user = "route=" + context.route()
                + "\nscopeMode=" + context.scope().mode()
                + "\nsourceCount=" + context.ledger().sourceSet().size()
                + "\nevidenceCount=" + context.ledger().evidence().size()
                + "\nquery=" + context.userQuery();
        return List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)
        );
    }

    private PlannerAction parseAction(String rawJson, PlannerContext context) {
        if (rawJson == null || rawJson.isBlank()
                || rawJson.contains("[1]")
                || rawJson.contains("来源#")) {
            return PlannerAction.clarify("planner_invalid_output");
        }
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            String actionText = node.path("action").asText("");
            PlannerActionType type = PlannerActionType.valueOf(actionText);
            String query = node.path("query").asText(context.userQuery());
            String reason = node.path("reason").asText("");
            Integer referenceNumber = node.hasNonNull("referenceNumber") ? node.get("referenceNumber").asInt() : null;
            Integer pageNumber = node.hasNonNull("pageNumber") ? node.get("pageNumber").asInt() : null;
            Integer windowRadius = node.hasNonNull("windowRadius") ? node.get("windowRadius").asInt() : null;
            return new PlannerAction(type, query, reason, context.scope().paperIds(), referenceNumber, pageNumber, windowRadius);
        } catch (Exception exception) {
            return PlannerAction.clarify("planner_invalid_json");
        }
    }
}
