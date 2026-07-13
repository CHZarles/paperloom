package io.github.chzarles.paperloom.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public record ReadingContractObligations(
        AnswerType answerType,
        List<String> required,
        List<String> missing
) {
    public ReadingContractObligations {
        answerType = answerType == null ? AnswerType.NON_EVIDENCE : answerType;
        required = dedupe(required);
        missing = dedupe(missing);
    }

    public static ReadingContractObligations evaluate(AnswerEnvelope envelope,
                                                      ReadingTurnProjection projection,
                                                      List<Map<String, Object>> references) {
        AnswerType answerType = envelope == null ? AnswerType.NON_EVIDENCE : envelope.answerType();
        ReadingTurnArtifacts artifacts = projection == null
                ? ReadingTurnArtifacts.empty("")
                : projection.artifacts();
        List<String> required = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        requireGoal(artifacts, required, missing);
        requireNextAction(artifacts, required, missing);
        requireVerificationBoundary(artifacts, required, missing);
        requireCheckableTarget(answerType, artifacts, required, missing);
        requireBeginnerDiscoveryShortlist(answerType, artifacts, required, missing);
        requireEvidence(answerType, artifacts, references, required, missing);

        return new ReadingContractObligations(answerType, required, missing);
    }

    public boolean satisfied() {
        return missing.isEmpty();
    }

    private static void requireGoal(ReadingTurnArtifacts artifacts, List<String> required, List<String> missing) {
        required.add("interpreted_goal");
        if (artifacts.goalCard().interpretedGoal().isBlank()) {
            missing.add("interpreted_goal");
        }
    }

    private static void requireNextAction(ReadingTurnArtifacts artifacts, List<String> required, List<String> missing) {
        required.add("next_action");
        if (artifacts.uiActions().isEmpty() && artifacts.goalCard().actions().isEmpty()) {
            missing.add("next_action");
        }
    }

    private static void requireVerificationBoundary(ReadingTurnArtifacts artifacts,
                                                    List<String> required,
                                                    List<String> missing) {
        required.add("verification_boundary");
        boolean hasMetadataOnlyShortlist = !artifacts.paperShortlist().items().isEmpty()
                && artifacts.missingEvidence().missing().contains("paper_content_quote");
        boolean hasNavigationOnlyPlan = !artifacts.readingPlan().steps().isEmpty()
                && artifacts.missingEvidence().missing().contains("read_location_quote");
        boolean hasQuoteBoundary = !artifacts.claimEvidencePanel().rows().isEmpty()
                && artifacts.missingEvidence().missing().contains("visual_pdf_page_evidence");
        boolean hasGeneralMissingEvidence = artifacts.paperShortlist().items().isEmpty()
                && artifacts.readingPlan().steps().isEmpty()
                && artifacts.claimEvidencePanel().rows().isEmpty()
                && !artifacts.missingEvidence().missing().isEmpty();
        if (!hasMetadataOnlyShortlist && !hasNavigationOnlyPlan && !hasQuoteBoundary && !hasGeneralMissingEvidence) {
            missing.add("verification_boundary");
        }
    }

    private static void requireCheckableTarget(AnswerType answerType,
                                               ReadingTurnArtifacts artifacts,
                                               List<String> required,
                                               List<String> missing) {
        required.add("checkable_target");
        if (answerType == AnswerType.NON_EVIDENCE) {
            return;
        }
        boolean hasTarget = artifacts.goalCard().readablePaperCount() != null
                || !artifacts.paperShortlist().items().isEmpty()
                || !artifacts.readingPlan().steps().isEmpty()
                || !artifacts.claimEvidencePanel().rows().isEmpty();
        if (!hasTarget) {
            missing.add("checkable_target");
        }
    }

    private static void requireEvidence(AnswerType answerType,
                                        ReadingTurnArtifacts artifacts,
                                        List<Map<String, Object>> references,
                                        List<String> required,
                                        List<String> missing) {
        if (answerType != AnswerType.EVIDENCE_ANSWER) {
            return;
        }
        required.add("quote_backed_claim");
        if (references == null || references.isEmpty()) {
            missing.add("citation_reference");
        }
        if (artifacts.claimEvidencePanel().rows().isEmpty()) {
            missing.add("claim_evidence_row");
        }
        if (!artifacts.missingEvidence().missing().contains("visual_pdf_page_evidence")) {
            missing.add("visual_pdf_page_evidence_status");
        }
    }

    private static void requireBeginnerDiscoveryShortlist(AnswerType answerType,
                                                          ReadingTurnArtifacts artifacts,
                                                          List<String> required,
                                                          List<String> missing) {
        if (answerType != AnswerType.PRODUCT_STATE || !isPaperDiscoveryOnlyAnswer(artifacts)) {
            return;
        }
        required.add("beginner_shortlist_size");
        int shortlistSize = artifacts.paperShortlist().items().size();
        if (shortlistSize < 3 || shortlistSize > 5) {
            missing.add("beginner_shortlist_size");
        }
        required.add("beginner_paper_roles");
        boolean everyPaperHasExplicitRole = artifacts.paperShortlist().items().stream()
                .allMatch(item -> !item.role().isBlank()
                        && !item.roleEvidenceSource().isBlank()
                        && !"missing_role_metadata".equals(item.roleEvidenceSource())
                        && !"unmapped_role_metadata".equals(item.roleEvidenceSource()));
        if (!everyPaperHasExplicitRole) {
            missing.add("beginner_paper_roles");
        }
    }

    private static boolean isPaperDiscoveryOnlyAnswer(ReadingTurnArtifacts artifacts) {
        return artifacts != null
                && artifacts.intentFrame() != null
                && !artifacts.intentFrame().paperQueryTexts().isEmpty()
                && !artifacts.paperShortlist().items().isEmpty()
                && artifacts.readingPlan().steps().isEmpty()
                && artifacts.claimEvidencePanel().rows().isEmpty();
    }

    private static List<String> dedupe(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                deduped.add(value.trim());
            }
        }
        return List.copyOf(deduped);
    }
}
