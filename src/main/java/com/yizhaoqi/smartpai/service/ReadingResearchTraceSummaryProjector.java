package com.yizhaoqi.smartpai.service;

import java.util.ArrayList;
import java.util.List;

class ReadingResearchTraceSummaryProjector {

    ReadingTurnArtifacts.ResearchTraceSummary summarize(ReadingResearchTrace trace) {
        if (trace == null || trace.isEmpty()) {
            return ReadingTurnArtifacts.ResearchTraceSummary.empty();
        }
        return new ReadingTurnArtifacts.ResearchTraceSummary(
                steps(trace),
                evidence(trace),
                claims(trace),
                verification(trace)
        );
    }

    private List<ReadingTurnArtifacts.TraceStep> steps(ReadingResearchTrace trace) {
        List<ReadingTurnArtifacts.TraceStep> steps = new ArrayList<>();
        if (!trace.intentFrame().answerType().isBlank() || !trace.intentFrame().ambiguityStatus().isBlank()) {
            steps.add(new ReadingTurnArtifacts.TraceStep(
                    "INTENT",
                    "Intent interpreted",
                    intentDetail(trace.intentFrame()),
                    trace.intentFrame().ambiguityStatus()
            ));
        }
        for (ReadingResearchTrace.StrategyStep step : trace.retrievalPlan().strategySteps()) {
            steps.add(new ReadingTurnArtifacts.TraceStep(
                    "RETRIEVAL",
                    retrievalLabel(step.retrievalStrategy()),
                    step.target(),
                    step.status()
            ));
        }
        if (!trace.evidenceLedger().items().isEmpty() || !trace.evidenceLedger().missingEvidence().isEmpty()) {
            steps.add(new ReadingTurnArtifacts.TraceStep(
                    "EVIDENCE",
                    "Evidence ledger",
                    evidenceDetail(trace.evidenceLedger()),
                    trace.evidenceLedger().missingEvidence().isEmpty() ? "satisfied" : "missing"
            ));
        }
        if (!trace.claimGraph().claims().isEmpty()) {
            steps.add(new ReadingTurnArtifacts.TraceStep(
                    "CLAIMS",
                    "Claim graph",
                    claimDetail(trace.claimGraph()),
                    trace.claimGraph().claims().stream().allMatch(claim -> "supported".equals(claim.status()))
                            ? "supported"
                            : "incomplete"
            ));
        }
        steps.add(new ReadingTurnArtifacts.TraceStep(
                "VERIFICATION",
                "Verification",
                trace.verificationPass().valid()
                        ? "The verification check passed."
                        : "More evidence is needed before this answer is fully verified.",
                trace.verificationPass().resultStatus()
        ));
        return List.copyOf(steps);
    }

    private ReadingTurnArtifacts.EvidenceSummary evidence(ReadingResearchTrace trace) {
        return new ReadingTurnArtifacts.EvidenceSummary(
                trace.evidenceLedger().items().size(),
                trace.evidenceLedger().rejectedItems().size(),
                trace.evidenceLedger().missingEvidence().size(),
                trace.evidenceLedger().missingEvidence()
        );
    }

    private ReadingTurnArtifacts.ClaimSummary claims(ReadingResearchTrace trace) {
        int supported = 0;
        int underdetermined = 0;
        int contradicted = 0;
        for (ReadingResearchTrace.ClaimNode claim : trace.claimGraph().claims()) {
            if ("supported".equals(claim.status())) {
                supported += 1;
            } else if ("contradicted".equals(claim.status())) {
                contradicted += 1;
            } else {
                underdetermined += 1;
            }
        }
        return new ReadingTurnArtifacts.ClaimSummary(
                trace.claimGraph().claims().size(),
                supported,
                underdetermined,
                contradicted
        );
    }

    private ReadingTurnArtifacts.VerificationSummary verification(ReadingResearchTrace trace) {
        return new ReadingTurnArtifacts.VerificationSummary(
                trace.verificationPass().valid(),
                trace.verificationPass().resultStatus(),
                trace.verificationPass().stopReason(),
                trace.verificationPass().requiredEvidenceStatus(),
                trace.verificationPass().missingRequiredEvidence().size(),
                trace.verificationPass().failedTraceObligationIds().size()
        );
    }

    private String intentDetail(ReadingResearchTrace.IntentFrameArtifact intentFrame) {
        List<String> parts = new ArrayList<>();
        if (!intentFrame.answerType().isBlank()) {
            parts.add("Answer shape: " + answerTypeLabel(intentFrame.answerType()));
        }
        if (!intentFrame.requiredCapabilities().isEmpty()) {
            parts.add("Capabilities: " + String.join(", ", intentFrame.requiredCapabilities().stream()
                    .map(this::capabilityLabel)
                    .filter(label -> !label.isBlank())
                    .toList()));
        }
        return String.join(". ", parts);
    }

    private String evidenceDetail(ReadingResearchTrace.EvidenceLedgerArtifact ledger) {
        List<String> parts = new ArrayList<>();
        parts.add(ledger.items().size() + " accepted");
        if (!ledger.rejectedItems().isEmpty()) {
            parts.add(ledger.rejectedItems().size() + " rejected");
        }
        if (!ledger.missingEvidence().isEmpty()) {
            parts.add(ledger.missingEvidence().size() + " missing");
        }
        return String.join(", ", parts);
    }

    private String claimDetail(ReadingResearchTrace.ClaimGraphArtifact claimGraph) {
        int supported = 0;
        int incomplete = 0;
        for (ReadingResearchTrace.ClaimNode claim : claimGraph.claims()) {
            if ("supported".equals(claim.status())) {
                supported += 1;
            } else {
                incomplete += 1;
            }
        }
        List<String> parts = new ArrayList<>();
        parts.add(supported + " supported");
        if (incomplete > 0) {
            parts.add(incomplete + " incomplete");
        }
        return String.join(", ", parts);
    }

    private String retrievalLabel(String strategy) {
        return switch (strategy == null ? "" : strategy) {
            case "paper_identity_resolution" -> "Paper identity resolution";
            case "semantic_search" -> "Semantic retrieval";
            case "lexical_search" -> "Lexical retrieval";
            case "metadata_filter" -> "Metadata filtering";
            case "citation_graph_traversal" -> "Citation graph traversal";
            case "table_search" -> "Table retrieval";
            case "figure_caption_search" -> "Figure-caption retrieval";
            case "formula_search" -> "Formula retrieval";
            case "algorithm_search" -> "Algorithm retrieval";
            case "appendix_search" -> "Appendix retrieval";
            case "author_institution_search" -> "Author or institution retrieval";
            case "negative_evidence_search" -> "Negative-evidence retrieval";
            case "version_comparison" -> "Version comparison";
            default -> "Retrieval step";
        };
    }

    private String answerTypeLabel(String answerType) {
        return switch (answerType == null ? "" : answerType) {
            case "exact_fact" -> "exact fact";
            case "definition_trace" -> "definition or explanation";
            case "comparison_matrix" -> "comparison";
            case "reproduction_protocol" -> "reproduction protocol";
            case "citation_genealogy" -> "citation genealogy";
            case "critical_assessment" -> "critical assessment";
            case "meta_review" -> "review";
            case "contradiction_resolution" -> "contradiction resolution";
            case "uncertainty_boundary" -> "uncertainty boundary";
            case "ambiguity_resolution" -> "clarification";
            case "constraint_filter" -> "filtered shortlist";
            case "figure_table_formula_interpretation" -> "figure, table, or formula interpretation";
            case "multi_hop_chain" -> "multi-step reading path";
            default -> "research answer";
        };
    }

    private String capabilityLabel(String capability) {
        return switch (capability == null ? "" : capability) {
            case "paper_discovery" -> "paper discovery";
            case "reading_location_retrieval_plan" -> "reading-location planning";
            case "source_quote_grounding" -> "quote grounding";
            case "claim_verification" -> "claim verification";
            case "metadata_navigation" -> "metadata navigation";
            case "reading_context_validation" -> "reading-context validation";
            default -> "";
        };
    }
}
