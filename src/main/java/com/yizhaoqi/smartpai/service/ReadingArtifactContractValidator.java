package com.yizhaoqi.smartpai.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ReadingArtifactContractValidator {

    private static final Set<String> BEGINNER_PAPER_ROLES = Set.of(
            "survey",
            "benchmark",
            "method",
            "critique",
            "background",
            "example"
    );

    public ReadingArtifactContractValidation validate(AnswerEnvelope envelope,
                                                      ReadingTurnProjection projection,
                                                      List<Map<String, Object>> references) {
        ReadingContractObligations obligations = ReadingContractObligations.evaluate(envelope, projection, references);
        ReadingTurnArtifacts artifacts = projection == null ? null : projection.artifacts();
        List<String> missing = new ArrayList<>(obligations.missing());
        validateHiddenBeginnerRoleClaims(envelope, artifacts, missing);
        if (envelope == null || envelope.answerType() != AnswerType.EVIDENCE_ANSWER) {
            return missing.isEmpty()
                    ? ReadingArtifactContractValidation.ok()
                    : ReadingArtifactContractValidation.invalid(missing);
        }

        validateReferences(references, missing);
        validateRows(artifacts, missing);
        validateVisualGapStatus(artifacts, missing);
        return missing.isEmpty()
                ? ReadingArtifactContractValidation.ok()
                : ReadingArtifactContractValidation.invalid(missing);
    }

    private void validateReferences(List<Map<String, Object>> references, List<String> missing) {
        for (Map<String, Object> reference : references == null ? List.<Map<String, Object>>of() : references) {
            if (stringValue(reference.get("sourceQuoteRef")).isBlank()) {
                missing.add("source_quote_ref");
            }
            if (stringValue(reference.get("paperId")).isBlank()) {
                missing.add("paper_id");
            }
            if (stringValue(reference.get("paperVersion")).isBlank()) {
                missing.add("paper_version");
            }
            if (stringValue(reference.get("locationRef")).isBlank()) {
                missing.add("location_ref");
            }
            if (stringValue(reference.get("content")).isBlank()) {
                missing.add("quote_text");
            }
            if (reference.get("pageNumber") == null && stringValue(reference.get("sectionTitle")).isBlank()) {
                missing.add("page_or_section");
            }
        }
    }

    private void validateRows(ReadingTurnArtifacts artifacts, List<String> missing) {
        if (artifacts == null) {
            return;
        }
        for (ReadingTurnArtifacts.ClaimEvidenceRow row : artifacts.claimEvidencePanel().rows()) {
            if (row.citationMarker().isBlank()) {
                missing.add("citation_marker");
            }
            if (row.sourceQuoteRef().isBlank()) {
                missing.add("source_quote_ref");
            }
            if (row.paperId().isBlank()) {
                missing.add("paper_id");
            }
            if (row.locationRef().isBlank()) {
                missing.add("location_ref");
            }
            if (row.quote().isBlank()) {
                missing.add("quote_text");
            }
            if (!hasSourceQuoteOpenAction(row)) {
                missing.add("source_quote_open_action");
            }
        }
    }

    private void validateVisualGapStatus(ReadingTurnArtifacts artifacts, List<String> missing) {
        if (artifacts == null || artifacts.claimEvidencePanel().rows().isEmpty()) {
            return;
        }
        if (!artifacts.missingEvidence().missing().contains("visual_pdf_page_evidence")) {
            missing.add("visual_pdf_page_evidence_status");
        }
    }

    private boolean hasSourceQuoteOpenAction(ReadingTurnArtifacts.ClaimEvidenceRow row) {
        for (ReadingTurnArtifacts.UiAction action : row.actions()) {
            if (!"OPEN_SOURCE_QUOTE".equals(action.action())) {
                continue;
            }
            Object ref = action.payload().get("sourceQuoteRef");
            if (row.sourceQuoteRef().equals(stringValue(ref))) {
                return true;
            }
        }
        return false;
    }

    private void validateHiddenBeginnerRoleClaims(AnswerEnvelope envelope,
                                                  ReadingTurnArtifacts artifacts,
                                                  List<String> missing) {
        if (envelope == null || envelope.stateClaims().isEmpty()) {
            return;
        }
        Set<String> observedPaperHandles = observedPaperHandles(artifacts);
        for (Map<String, Object> claim : envelope.stateClaims()) {
            String role = normalizedBeginnerRole(firstNonBlank(
                    stringValue(claim.get("beginnerRole")),
                    stringValue(claim.get("paperRole")),
                    stringValue(claim.get("role"))
            ));
            boolean hasRoleClaim = claim.containsKey("beginnerRole")
                    || claim.containsKey("paperRole")
                    || claim.containsKey("role");
            if (!hasRoleClaim) {
                continue;
            }
            missing.add("beginner_role_from_model_state");
            if (role.isBlank()) {
                missing.add("beginner_role_taxonomy");
            }
            String paperHandle = stringValue(claim.get("paperHandle"));
            if (paperHandle.isBlank() || !observedPaperHandles.contains(paperHandle)) {
                missing.add("beginner_role_target");
            }
        }
    }

    private Set<String> observedPaperHandles(ReadingTurnArtifacts artifacts) {
        if (artifacts == null || artifacts.paperShortlist().items().isEmpty()) {
            return Set.of();
        }
        Set<String> handles = new LinkedHashSet<>();
        for (ReadingTurnArtifacts.PaperShortlistItem item : artifacts.paperShortlist().items()) {
            if (!item.paperHandle().isBlank()) {
                handles.add(item.paperHandle());
            }
        }
        return Set.copyOf(handles);
    }

    private String normalizedBeginnerRole(String value) {
        String normalized = stringValue(value)
                .toLowerCase()
                .replace('_', '-')
                .replace(' ', '-');
        return BEGINNER_PAPER_ROLES.contains(normalized) ? normalized : "";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = stringValue(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
