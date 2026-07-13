package io.github.chzarles.paperloom.service;

import java.util.List;

public record ReadingArtifactContractValidation(
        boolean valid,
        String reason,
        List<String> missingFields
) {
    public ReadingArtifactContractValidation {
        reason = reason == null ? "" : reason.trim();
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
    }

    public static ReadingArtifactContractValidation ok() {
        return new ReadingArtifactContractValidation(true, "", List.of());
    }

    public static ReadingArtifactContractValidation invalid(List<String> missingFields) {
        List<String> safeMissing = missingFields == null ? List.of() : List.copyOf(missingFields);
        String reason = safeMissing.isEmpty()
                ? "The reading answer is missing required verification artifacts."
                : "The reading answer is missing: " + humanLabels(safeMissing) + ".";
        return new ReadingArtifactContractValidation(false, reason, safeMissing);
    }

    private static String humanLabels(List<String> missingFields) {
        return String.join(", ", missingFields.stream()
                .distinct()
                .map(ReadingArtifactContractValidation::humanLabel)
                .toList());
    }

    private static String humanLabel(String field) {
        return switch (field == null ? "" : field) {
            case "citation_reference" -> "citation detail";
            case "claim_evidence_row" -> "claim evidence row";
            case "source_quote_ref" -> "cited quote anchor";
            case "paper_id" -> "paper identity";
            case "location_ref" -> "reading location";
            case "quote_text" -> "quote text";
            case "page_or_section" -> "page or section";
            case "citation_marker" -> "citation marker";
            case "source_quote_open_action" -> "open-citation action";
            case "visual_pdf_page_evidence_status" -> "visual page evidence status";
            case "interpreted_goal" -> "interpreted goal";
            case "next_action" -> "next action";
            case "verification_boundary" -> "verification boundary";
            case "checkable_target" -> "checkable target";
            case "quote_backed_claim" -> "quote-backed claim";
            case "beginner_shortlist_size" -> "3 to 5 beginner shortlist papers";
            case "beginner_paper_roles" -> "explicit beginner paper roles";
            case "beginner_role_taxonomy" -> "valid beginner role";
            case "beginner_role_target" -> "observed paper for each declared beginner role";
            case "beginner_role_from_model_state" -> "beginner paper roles from product metadata or quote-backed evidence, not model state claims";
            default -> "required reading contract field";
        };
    }
}
