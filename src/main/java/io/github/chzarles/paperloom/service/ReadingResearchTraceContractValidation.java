package io.github.chzarles.paperloom.service;

import java.util.List;

public record ReadingResearchTraceContractValidation(
        boolean valid,
        String reason,
        List<String> missingFields
) {
    public ReadingResearchTraceContractValidation {
        reason = reason == null ? "" : reason.trim();
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
    }

    public static ReadingResearchTraceContractValidation ok() {
        return new ReadingResearchTraceContractValidation(true, "", List.of());
    }

    public static ReadingResearchTraceContractValidation invalid(List<String> missingFields) {
        List<String> safeMissing = missingFields == null ? List.of() : List.copyOf(missingFields);
        String reason = safeMissing.isEmpty()
                ? "The answer was not released because the research trace did not pass verification."
                : "The answer was not released because the research trace is missing: "
                + humanLabels(safeMissing) + ".";
        return new ReadingResearchTraceContractValidation(false, reason, safeMissing);
    }

    private static String humanLabels(List<String> fields) {
        return String.join(", ", fields.stream()
                .distinct()
                .map(ReadingResearchTraceContractValidation::humanLabel)
                .toList());
    }

    private static String humanLabel(String field) {
        return switch (field == null ? "" : field) {
            case "research_trace" -> "research trace";
            case "schema_version" -> "trace schema";
            case "question_id" -> "question identity";
            case "intent_frame" -> "interpreted intent";
            case "retrieval_plan" -> "retrieval plan";
            case "retrieval_step" -> "retrieval step";
            case "reasoning_artifact" -> "reasoning artifact";
            case "verification_pass" -> "pre-answer verification pass";
            case "research_answer" -> "research answer artifact";
            case "answer_summary" -> "user-safe answer text";
            case "evidence_ledger_item" -> "quoted evidence";
            case "evidence_identity" -> "evidence paper and location identity";
            case "claim_graph" -> "claim graph";
            case "claim_text" -> "atomic claim text";
            case "claim_support" -> "claim support";
            case "claim_edge" -> "claim-evidence edge";
            case "answer_claim_citation" -> "answer claim citation";
            case "answer_evidence_citation" -> "answer evidence citation";
            case "answer_reasoning_citation" -> "answer reasoning citation";
            case "verification_link" -> "answer verification link";
            case "visible_internal_identifier" -> "user-safe answer text";
            default -> "required research trace field";
        };
    }
}
