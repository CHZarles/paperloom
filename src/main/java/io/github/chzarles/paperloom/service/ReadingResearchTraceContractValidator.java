package io.github.chzarles.paperloom.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ReadingResearchTraceContractValidator {

    private static final String SCHEMA_VERSION = "research-harness-artifacts/v1";
    private static final List<String> VISIBLE_INTERNAL_TOKENS = List.of(
            "paper_handle_",
            "page_ref_",
            "section_ref_",
            "location_ref_",
            "source_quote_",
            "paperHandle",
            "locationRef",
            "sourceQuoteRef",
            "parserQuality",
            "parserName",
            "parserVersion",
            "AUTO_SOURCE",
            "AUTO_LIBRARY",
            "SOURCE_SET_SNAPSHOT",
            "immutable=true",
            "Source Quote",
            "get_session_state",
            "list_papers",
            "search_paper_candidates",
            "find_papers_by_identity",
            "get_paper_outline",
            "list_paper_locations",
            "find_reading_locations",
            "read_locations",
            "trace_source_quotes"
    );

    public ReadingResearchTraceContractValidation validateForCompletion(ReadingResearchTrace trace,
                                                                        AnswerEnvelope envelope,
                                                                        ProductResultStatus resultStatus) {
        if (resultStatus != ProductResultStatus.COMPLETED) {
            return ReadingResearchTraceContractValidation.ok();
        }
        List<String> missing = new ArrayList<>();
        if (trace == null || trace.isEmpty()) {
            missing.add("research_trace");
            return ReadingResearchTraceContractValidation.invalid(missing);
        }
        validateSharedTrace(trace, missing);
        validateVerification(trace, missing);
        validateResearchAnswer(trace, envelope, missing);
        if (envelope != null && envelope.answerType() == AnswerType.EVIDENCE_ANSWER) {
            validateEvidenceAnswerTrace(trace, missing);
        }
        return missing.isEmpty()
                ? ReadingResearchTraceContractValidation.ok()
                : ReadingResearchTraceContractValidation.invalid(missing);
    }

    private void validateSharedTrace(ReadingResearchTrace trace, List<String> missing) {
        if (!SCHEMA_VERSION.equals(trace.schemaVersion())) {
            missing.add("schema_version");
        }
        String questionId = trace.intentFrame().questionId();
        if (questionId.isBlank()) {
            missing.add("question_id");
        }
        if (trace.intentFrame().rawQuestion().isBlank()
                || trace.intentFrame().answerType().isBlank()
                || trace.intentFrame().ambiguityStatus().isBlank()
                || trace.intentFrame().requiredCapabilities().isEmpty()) {
            missing.add("intent_frame");
        }
        if (trace.retrievalPlan().planId().isBlank()
                || !sameQuestion(questionId, trace.retrievalPlan().questionId())
                || trace.retrievalPlan().strategySteps().isEmpty()) {
            missing.add("retrieval_plan");
        }
        for (ReadingResearchTrace.StrategyStep step : trace.retrievalPlan().strategySteps()) {
            if (step.stepId().isBlank()
                    || step.retrievalStrategy().isBlank()
                    || step.target().isBlank()
                    || "missing".equals(step.status())) {
                missing.add("retrieval_step");
            }
        }
        if (trace.reasoningArtifacts().isEmpty()) {
            missing.add("reasoning_artifact");
        }
    }

    private void validateVerification(ReadingResearchTrace trace, List<String> missing) {
        ReadingResearchTrace.VerificationPassArtifact verification = trace.verificationPass();
        if (verification.verificationId().isBlank()
                || !verification.valid()
                || !"COMPLETED".equals(verification.resultStatus())
                || verification.unsupportedClaimCount() > 0
                || verification.contradictedClaimCount() > 0
                || !verification.missingRequiredEvidence().isEmpty()
                || verification.abstentionRequired()
                || !verification.failedTraceObligationIds().isEmpty()) {
            missing.add("verification_pass");
        }
    }

    private void validateResearchAnswer(ReadingResearchTrace trace,
                                        AnswerEnvelope envelope,
                                        List<String> missing) {
        ReadingResearchTrace.ResearchAnswerArtifact answer = trace.researchAnswer();
        String expectedStatus = envelope != null && envelope.answerType() == AnswerType.CLARIFICATION_NEEDED
                ? "needs_clarification"
                : "answered";
        if (answer.answerId().isBlank()
                || !expectedStatus.equals(answer.status())
                || answer.answerType().isBlank()
                || answer.summary().isBlank()) {
            missing.add("research_answer");
        }
        if (!trace.verificationPass().verificationId().equals(answer.verificationId())) {
            missing.add("verification_link");
        }
        if (containsVisibleInternalIdentifier(answer.summary())) {
            missing.add("visible_internal_identifier");
        }
        Set<String> reasoningIds = new LinkedHashSet<>();
        for (ReadingResearchTrace.ReasoningArtifact artifact : trace.reasoningArtifacts()) {
            reasoningIds.add(artifact.artifactId());
        }
        if (!reasoningIds.containsAll(answer.reasoningArtifactIds())) {
            missing.add("answer_reasoning_citation");
        }
    }

    private void validateEvidenceAnswerTrace(ReadingResearchTrace trace, List<String> missing) {
        List<ReadingResearchTrace.EvidenceItem> evidenceItems = trace.evidenceLedger().items();
        List<ReadingResearchTrace.ClaimNode> claims = trace.claimGraph().claims();
        if (evidenceItems.isEmpty()) {
            missing.add("evidence_ledger_item");
        }
        if (claims.isEmpty()) {
            missing.add("claim_graph");
        }

        Set<String> evidenceIds = new LinkedHashSet<>();
        for (ReadingResearchTrace.EvidenceItem item : evidenceItems) {
            evidenceIds.add(item.evidenceId());
            if (item.evidenceId().isBlank()
                    || item.paperId().isBlank()
                    || item.title().isBlank()
                    || item.paperVersion().isBlank()
                    || item.location().isBlank()
                    || item.spanText().isBlank()) {
                missing.add("evidence_identity");
            }
            if (item.supportsClaimIds().isEmpty() && item.refutesClaimIds().isEmpty()) {
                missing.add("claim_support");
            }
        }

        Set<String> claimIds = new LinkedHashSet<>();
        for (ReadingResearchTrace.ClaimNode claim : claims) {
            claimIds.add(claim.claimId());
            if (claim.claimId().isBlank() || claim.text().isBlank()) {
                missing.add("claim_text");
            }
            if (!"supported".equals(claim.status()) || claim.supportingEvidenceIds().isEmpty()) {
                missing.add("claim_support");
            }
            if (!evidenceIds.containsAll(claim.supportingEvidenceIds())
                    || !evidenceIds.containsAll(claim.refutingEvidenceIds())) {
                missing.add("claim_edge");
            }
        }
        for (ReadingResearchTrace.ClaimEvidenceEdge edge : trace.claimGraph().edges()) {
            if (!claimIds.contains(edge.claimId()) || !evidenceIds.contains(edge.evidenceId())) {
                missing.add("claim_edge");
            }
        }

        ReadingResearchTrace.ResearchAnswerArtifact answer = trace.researchAnswer();
        if (!new LinkedHashSet<>(answer.citedClaimIds()).containsAll(claimIds)) {
            missing.add("answer_claim_citation");
        }
        if (!new LinkedHashSet<>(answer.citedEvidenceIds()).containsAll(evidenceIds)) {
            missing.add("answer_evidence_citation");
        }
        validateReasoningArtifactSources(trace, claimIds, evidenceIds, missing);
    }

    private void validateReasoningArtifactSources(ReadingResearchTrace trace,
                                                  Set<String> claimIds,
                                                  Set<String> evidenceIds,
                                                  List<String> missing) {
        Set<String> answerReasoningIds = new LinkedHashSet<>(trace.researchAnswer().reasoningArtifactIds());
        for (ReadingResearchTrace.ReasoningArtifact artifact : trace.reasoningArtifacts()) {
            if (artifact.artifactId().isBlank()
                    || artifact.type().isBlank()
                    || artifact.title().isBlank()
                    || !answerReasoningIds.contains(artifact.artifactId())) {
                missing.add("reasoning_artifact");
            }
            if (!claimIds.containsAll(artifact.sourceClaimIds())) {
                missing.add("answer_claim_citation");
            }
            if (!evidenceIds.containsAll(artifact.sourceEvidenceIds())) {
                missing.add("answer_evidence_citation");
            }
        }
    }

    private boolean sameQuestion(String expected, String actual) {
        return !expected.isBlank() && expected.equals(actual);
    }

    private boolean containsVisibleInternalIdentifier(String value) {
        String text = value == null ? "" : value;
        for (String token : VISIBLE_INTERNAL_TOKENS) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
