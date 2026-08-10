package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ResearchHarnessResultMapper {

    private final ObjectMapper objectMapper;

    public ResearchHarnessResultMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProductTurnResult toProductResult(ProductTurnRequest request, Map<String, Object> response) {
        Map<String, Object> answer = objectMap(response.get("answer"));
        String markdown = stringValue(answer.get("markdown"));
        String status = stringValue(response.get("status"));
        List<Map<String, Object>> references = references(response.get("citations"));
        ProductResultStatus resultStatus = switch (status) {
            case "FAILED_TECHNICAL" -> ProductResultStatus.FAILED;
            case "INCOMPLETE_PRECISE" -> ProductResultStatus.INCOMPLETE_PRECISE;
            case "LIMITED" -> ProductResultStatus.LIMITED;
            case "CANCELLED" -> ProductResultStatus.CANCELLED;
            default -> ProductResultStatus.COMPLETED;
        };
        AnswerType answerType = switch (status) {
            case "NEEDS_CLARIFICATION" -> AnswerType.CLARIFICATION_NEEDED;
            case "INCOMPLETE_PRECISE" -> AnswerType.INSUFFICIENT_EVIDENCE;
            default -> references.isEmpty() ? AnswerType.NON_EVIDENCE : AnswerType.EVIDENCE_ANSWER;
        };
        Map<String, Object> trace = objectMap(response.get("trace"));
        Map<String, Object> control = objectMap(response.get("control"));
        AnswerEnvelope envelope = new AnswerEnvelope(
                answerType,
                markdown,
                List.of(),
                List.of(),
                resultStatus == ProductResultStatus.INCOMPLETE_PRECISE
                        ? List.of("The available paper evidence did not fully support the request.")
                        : List.of(),
                List.of(),
                List.of(),
                stringValue(trace.get("finish_reason"))
        );
        return new ProductTurnResult(
                markdown,
                envelope,
                references,
                List.of(),
                paperChoices(trace.get("paper_candidates")),
                readingArtifacts(request.userMessage(), trace, references, resultStatus),
                readingStatePatch(references),
                ReadingResearchTrace.empty(),
                stopReason(resultStatus, stringValue(control.get("reason_code"))),
                resultStatus,
                diagnostics(control, response.get("usage"), response.get("run_id"))
        );
    }

    private ProductStopReason stopReason(ProductResultStatus status, String reasonCode) {
        if (status == ProductResultStatus.FAILED) {
            return ProductStopReason.TOOL_FAILED;
        }
        if (status == ProductResultStatus.CANCELLED) {
            return ProductStopReason.CANCELLED;
        }
        if (status != ProductResultStatus.LIMITED) {
            return ProductStopReason.COMPLETED;
        }
        return switch (reasonCode) {
            case "RUN_MODEL_CALL_LIMIT" -> ProductStopReason.MAX_MODEL_CALLS;
            case "RUN_CONTEXT_BUDGET_EXHAUSTED" -> ProductStopReason.CONTEXT_BUDGET_EXHAUSTED;
            case "RUN_DEADLINE_EXCEEDED" -> ProductStopReason.DEADLINE_EXCEEDED;
            default -> ProductStopReason.TOKEN_BUDGET_EXHAUSTED;
        };
    }

    private Map<String, Object> diagnostics(Map<String, Object> control, Object usage, Object runId) {
        String traceRunId = stringValue(runId);
        if (control.isEmpty() && !(usage instanceof Map<?, ?>) && traceRunId.isBlank()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reasonCode", stringValue(control.get("reason_code")));
        result.put("usage", objectMap(control.get("usage")).isEmpty() ? objectMap(usage) : objectMap(control.get("usage")));
        if (!traceRunId.isBlank()) {
            result.put("agentTraceRunId", traceRunId);
        }
        return result;
    }

    public Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return Map.of();
        }
        return objectMapper.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    public List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> mapped = objectMap(item);
            if (!mapped.isEmpty()) {
                result.add(mapped);
            }
        }
        return result;
    }

    private List<Map<String, Object>> references(Object rawCitations) {
        List<Map<String, Object>> result = new ArrayList<>();
        int fallbackNumber = 1;
        for (Map<String, Object> citation : mapList(rawCitations)) {
            int referenceNumber = intValue(citation.get("reference_number"), fallbackNumber++);
            String evidenceId = stringValue(citation.get("evidence_id"));
            String sourceQuoteRef = stringValue(citation.get("source_quote_ref"));
            String quote = stringValue(citation.get("span_text"));
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("referenceNumber", referenceNumber);
            reference.put("evidenceRef", firstNonBlank(evidenceId, sourceQuoteRef));
            reference.put("sourceQuoteRef", sourceQuoteRef);
            reference.put("paperId", citation.get("paper_id"));
            reference.put("paperTitle", citation.get("title"));
            reference.put("originalFilename", citation.get("original_filename"));
            reference.put("pageNumber", citation.get("page"));
            reference.put("sectionTitle", citation.get("section"));
            reference.put("locationRef", firstNonBlank(citation.get("location_ref"), citation.get("location")));
            reference.put("elementType", citation.get("element_type"));
            reference.put("sourceKind", citation.get("source_kind"));
            reference.put("bboxJson", firstNonBlank(citation.get("bbox_json"), citation.get("bbox_or_cell_ref")));
            reference.put("parserName", citation.get("parser_name"));
            reference.put("parserVersion", citation.get("parser_version"));
            reference.put("tableId", citation.get("table_id"));
            reference.put("figureId", citation.get("figure_id"));
            reference.put("formulaId", citation.get("formula_id"));
            reference.put("content", quote);
            reference.put("anchorText", quote);
            reference.put("matchedText", quote);
            reference.put("evidenceSnippet", quote);
            reference.put("retrievalMode", "PYTHON_RESEARCH_HARNESS");
            reference.put("retrievalLabel", "Python research harness evidence");
            reference.put("retrievalRoute", "PYTHON_RESEARCH_HARNESS");
            reference.put("citationRef", "[" + referenceNumber + "]");
            reference.put("score", citation.get("relevance_score"));
            reference.put("evidenceAssetLevel", "TEXT");
            reference.put("pdfEvidenceAvailable", booleanValue(citation.get("pdf_evidence_available")));
            reference.put("pageScreenshotAvailable", booleanValue(citation.get("page_screenshot_available")));
            reference.put("tableScreenshotAvailable", booleanValue(citation.get("table_screenshot_available")));
            reference.put("figureScreenshotAvailable", booleanValue(citation.get("figure_screenshot_available")));
            result.add(reference);
        }
        return List.copyOf(result);
    }

    private ReadingTurnArtifacts readingArtifacts(String question,
                                                  Map<String, Object> trace,
                                                  List<Map<String, Object>> references,
                                                  ProductResultStatus resultStatus) {
        List<ReadingTurnArtifacts.TraceStep> steps = mapList(trace.get("tool_calls")).stream()
                .map(call -> new ReadingTurnArtifacts.TraceStep(
                        stringValue(call.get("tool_name")),
                        stringValue(call.get("tool_name")),
                        "",
                        "completed"
                ))
                .toList();
        List<ReadingTurnArtifacts.ClaimEvidenceRow> rows = references.stream()
                .map(reference -> new ReadingTurnArtifacts.ClaimEvidenceRow(
                        "",
                        stringValue(reference.get("content")),
                        stringValue(reference.get("citationRef")),
                        "",
                        stringValue(reference.get("paperId")),
                        "",
                        stringValue(reference.get("paperTitle")),
                        stringValue(reference.get("locationRef")),
                        stringValue(reference.get("sectionTitle")),
                        stringValue(reference.get("elementType")),
                        List.of(),
                        List.of()
                ))
                .toList();
        ReadingTurnArtifacts.ResearchTraceSummary summary = new ReadingTurnArtifacts.ResearchTraceSummary(
                steps,
                new ReadingTurnArtifacts.EvidenceSummary(references.size(), 0, 0, List.of()),
                new ReadingTurnArtifacts.ClaimSummary(rows.size(), rows.size(), 0, 0),
                new ReadingTurnArtifacts.VerificationSummary(
                        resultStatus != ProductResultStatus.FAILED,
                        resultStatus.name(),
                        stringValue(trace.get("finish_reason")),
                        references.isEmpty() ? "NOT_REQUIRED_OR_UNAVAILABLE" : "VERIFIED",
                        0,
                        0
                )
        );
        return new ReadingTurnArtifacts(
                "reading-turn-artifacts/v1",
                new ReadingTurnArtifacts.GoalCard(question, "Authorized paper scope", null, true, List.of()),
                ReadingIntentFrame.empty(question),
                ReadingTurnArtifacts.PaperShortlist.empty(),
                ReadingTurnArtifacts.ReadingPlan.empty(),
                new ReadingTurnArtifacts.ClaimEvidencePanel(rows),
                ReadingTurnArtifacts.MissingEvidence.empty(),
                List.of(),
                List.of(),
                summary
        );
    }

    private ReadingStatePatch readingStatePatch(List<Map<String, Object>> references) {
        if (references.isEmpty()) {
            return ReadingStatePatch.empty();
        }
        Map<String, Object> first = references.get(0);
        return new ReadingStatePatch(
                new ReadingStatePatch.SelectedPaper(
                        stringValue(first.get("paperId")),
                        "",
                        stringValue(first.get("paperTitle")),
                        ""
                ),
                new ReadingStatePatch.SelectedLocation(
                        stringValue(first.get("paperId")),
                        "",
                        stringValue(first.get("locationRef")),
                        stringValue(first.get("sectionTitle"))
                ),
                null,
                List.of()
        );
    }

    private List<Map<String, Object>> paperChoices(Object rawCandidates) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> candidate : mapList(rawCandidates)) {
            String paperId = stringValue(candidate.get("paper_id"));
            if (paperId.isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "READING_PAPER_CHOICE");
            item.put("sourceTool", "search_paper_candidates");
            item.put("paperHandle", "paper_handle_" + paperId);
            item.put("title", candidate.get("title"));
            item.put("authors", candidate.get("authors"));
            item.put("year", candidate.get("year"));
            item.put("venue", candidate.get("venue"));
            result.add(item);
        }
        return List.copyOf(result);
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(stringValue(value)) || "1".equals(stringValue(value));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
