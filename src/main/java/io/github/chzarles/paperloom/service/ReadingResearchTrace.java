package io.github.chzarles.paperloom.service;

import java.util.List;
import java.util.Map;

public record ReadingResearchTrace(
        String schemaVersion,
        IntentFrameArtifact intentFrame,
        RetrievalPlanArtifact retrievalPlan,
        EvidenceLedgerArtifact evidenceLedger,
        ClaimGraphArtifact claimGraph,
        List<ReasoningArtifact> reasoningArtifacts,
        VerificationPassArtifact verificationPass,
        ResearchAnswerArtifact researchAnswer
) {
    public ReadingResearchTrace {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? "research-harness-artifacts/v1"
                : schemaVersion.trim();
        intentFrame = intentFrame == null ? IntentFrameArtifact.empty() : intentFrame;
        retrievalPlan = retrievalPlan == null ? RetrievalPlanArtifact.empty() : retrievalPlan;
        evidenceLedger = evidenceLedger == null ? EvidenceLedgerArtifact.empty() : evidenceLedger;
        claimGraph = claimGraph == null ? ClaimGraphArtifact.empty() : claimGraph;
        reasoningArtifacts = reasoningArtifacts == null ? List.of() : List.copyOf(reasoningArtifacts);
        verificationPass = verificationPass == null ? VerificationPassArtifact.empty() : verificationPass;
        researchAnswer = researchAnswer == null ? ResearchAnswerArtifact.empty() : researchAnswer;
    }

    public static ReadingResearchTrace empty() {
        return new ReadingResearchTrace(
                "research-harness-artifacts/v1",
                IntentFrameArtifact.empty(),
                RetrievalPlanArtifact.empty(),
                EvidenceLedgerArtifact.empty(),
                ClaimGraphArtifact.empty(),
                List.of(),
                VerificationPassArtifact.empty(),
                ResearchAnswerArtifact.empty()
        );
    }

    public boolean isEmpty() {
        return intentFrame.isEmpty()
                && retrievalPlan.isEmpty()
                && evidenceLedger.isEmpty()
                && claimGraph.isEmpty()
                && reasoningArtifacts.isEmpty()
                && verificationPass.isEmpty()
                && researchAnswer.isEmpty();
    }

    public record IntentFrameArtifact(
            String questionId,
            String rawQuestion,
            String normalizedQuestion,
            List<String> entities,
            List<String> paperMentions,
            List<String> methodMentions,
            List<String> datasetMentions,
            List<String> constraints,
            String answerType,
            String ambiguityStatus,
            List<String> requiredEvidenceTypes,
            List<String> requiredCapabilities
    ) {
        public IntentFrameArtifact {
            questionId = clean(questionId);
            rawQuestion = clean(rawQuestion);
            normalizedQuestion = clean(normalizedQuestion);
            entities = copy(entities);
            paperMentions = copy(paperMentions);
            methodMentions = copy(methodMentions);
            datasetMentions = copy(datasetMentions);
            constraints = copy(constraints);
            answerType = clean(answerType);
            ambiguityStatus = clean(ambiguityStatus);
            requiredEvidenceTypes = copy(requiredEvidenceTypes);
            requiredCapabilities = copy(requiredCapabilities);
        }

        static IntentFrameArtifact empty() {
            return new IntentFrameArtifact("", "", "", List.of(), List.of(), List.of(), List.of(),
                    List.of(), "", "", List.of(), List.of());
        }

        boolean isEmpty() {
            return questionId.isBlank()
                    && rawQuestion.isBlank()
                    && normalizedQuestion.isBlank()
                    && answerType.isBlank()
                    && ambiguityStatus.isBlank()
                    && entities.isEmpty()
                    && paperMentions.isEmpty()
                    && methodMentions.isEmpty()
                    && datasetMentions.isEmpty()
                    && constraints.isEmpty()
                    && requiredEvidenceTypes.isEmpty()
                    && requiredCapabilities.isEmpty();
        }
    }

    public record RetrievalPlanArtifact(
            String planId,
            String questionId,
            List<String> targetEntities,
            List<StrategyStep> strategySteps,
            List<String> expectedEvidenceTypes,
            List<String> requiredRecallTargets,
            String hardNegativePolicy,
            List<String> stopConditions
    ) {
        public RetrievalPlanArtifact {
            planId = clean(planId);
            questionId = clean(questionId);
            targetEntities = copy(targetEntities);
            strategySteps = strategySteps == null ? List.of() : List.copyOf(strategySteps);
            expectedEvidenceTypes = copy(expectedEvidenceTypes);
            requiredRecallTargets = copy(requiredRecallTargets);
            hardNegativePolicy = clean(hardNegativePolicy);
            stopConditions = copy(stopConditions);
        }

        static RetrievalPlanArtifact empty() {
            return new RetrievalPlanArtifact("", "", List.of(), List.of(), List.of(), List.of(), "", List.of());
        }

        boolean isEmpty() {
            return planId.isBlank()
                    && questionId.isBlank()
                    && targetEntities.isEmpty()
                    && strategySteps.isEmpty()
                    && expectedEvidenceTypes.isEmpty()
                    && requiredRecallTargets.isEmpty()
                    && hardNegativePolicy.isBlank()
                    && stopConditions.isEmpty();
        }
    }

    public record StrategyStep(
            String stepId,
            String retrievalStrategy,
            String target,
            List<String> expectedEvidenceTypes,
            String status
    ) {
        public StrategyStep {
            stepId = clean(stepId);
            retrievalStrategy = clean(retrievalStrategy);
            target = clean(target);
            expectedEvidenceTypes = copy(expectedEvidenceTypes);
            status = clean(status);
        }
    }

    public record EvidenceLedgerArtifact(
            String ledgerId,
            String questionId,
            List<EvidenceItem> items,
            List<EvidenceItem> rejectedItems,
            List<String> missingEvidence
    ) {
        public EvidenceLedgerArtifact {
            ledgerId = clean(ledgerId);
            questionId = clean(questionId);
            items = items == null ? List.of() : List.copyOf(items);
            rejectedItems = rejectedItems == null ? List.of() : List.copyOf(rejectedItems);
            missingEvidence = copy(missingEvidence);
        }

        static EvidenceLedgerArtifact empty() {
            return new EvidenceLedgerArtifact("", "", List.of(), List.of(), List.of());
        }

        boolean isEmpty() {
            return ledgerId.isBlank()
                    && questionId.isBlank()
                    && items.isEmpty()
                    && rejectedItems.isEmpty()
                    && missingEvidence.isEmpty();
        }
    }

    public record EvidenceItem(
            String evidenceId,
            String sourceQuoteRef,
            String paperId,
            String title,
            String paperVersion,
            String section,
            Integer page,
            String location,
            String elementType,
            String spanText,
            String bboxOrCellRef,
            String retrievalStrategy,
            Double relevanceScore,
            String evidenceQuality,
            List<String> supportsClaimIds,
            List<String> refutesClaimIds
    ) {
        public EvidenceItem {
            evidenceId = clean(evidenceId);
            sourceQuoteRef = clean(sourceQuoteRef);
            paperId = clean(paperId);
            title = clean(title);
            paperVersion = clean(paperVersion);
            section = clean(section);
            location = clean(location);
            elementType = clean(elementType);
            spanText = clean(spanText);
            bboxOrCellRef = clean(bboxOrCellRef);
            retrievalStrategy = clean(retrievalStrategy);
            evidenceQuality = clean(evidenceQuality);
            supportsClaimIds = copy(supportsClaimIds);
            refutesClaimIds = copy(refutesClaimIds);
        }
    }

    public record ClaimGraphArtifact(
            String graphId,
            String questionId,
            List<ClaimNode> claims,
            List<ClaimEvidenceEdge> edges
    ) {
        public ClaimGraphArtifact {
            graphId = clean(graphId);
            questionId = clean(questionId);
            claims = claims == null ? List.of() : List.copyOf(claims);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }

        static ClaimGraphArtifact empty() {
            return new ClaimGraphArtifact("", "", List.of(), List.of());
        }

        boolean isEmpty() {
            return graphId.isBlank() && questionId.isBlank() && claims.isEmpty() && edges.isEmpty();
        }
    }

    public record ClaimNode(
            String claimId,
            String text,
            String claimType,
            List<String> supportingEvidenceIds,
            List<String> refutingEvidenceIds,
            String status,
            String confidence,
            List<String> dependsOnClaimIds
    ) {
        public ClaimNode {
            claimId = clean(claimId);
            text = clean(text);
            claimType = clean(claimType);
            supportingEvidenceIds = copy(supportingEvidenceIds);
            refutingEvidenceIds = copy(refutingEvidenceIds);
            status = clean(status);
            confidence = clean(confidence);
            dependsOnClaimIds = copy(dependsOnClaimIds);
        }
    }

    public record ClaimEvidenceEdge(
            String claimId,
            String evidenceId,
            String relation
    ) {
        public ClaimEvidenceEdge {
            claimId = clean(claimId);
            evidenceId = clean(evidenceId);
            relation = clean(relation);
        }
    }

    public record ReasoningArtifact(
            String artifactId,
            String questionId,
            String type,
            String title,
            List<String> sourceClaimIds,
            List<String> sourceEvidenceIds,
            Map<String, Object> payload
    ) {
        public ReasoningArtifact {
            artifactId = clean(artifactId);
            questionId = clean(questionId);
            type = clean(type);
            title = clean(title);
            sourceClaimIds = copy(sourceClaimIds);
            sourceEvidenceIds = copy(sourceEvidenceIds);
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    public record VerificationPassArtifact(
            String verificationId,
            String questionId,
            boolean valid,
            List<String> requiredCapabilitiesAttempted,
            String requiredEvidenceStatus,
            int unsupportedClaimCount,
            int contradictedClaimCount,
            List<String> missingRequiredEvidence,
            String ambiguityResolution,
            List<String> constraintCheckResults,
            boolean abstentionRequired,
            List<String> satisfiedTraceObligationIds,
            List<String> failedTraceObligationIds,
            String resultStatus,
            String stopReason
    ) {
        public VerificationPassArtifact {
            verificationId = clean(verificationId);
            questionId = clean(questionId);
            requiredCapabilitiesAttempted = copy(requiredCapabilitiesAttempted);
            requiredEvidenceStatus = clean(requiredEvidenceStatus);
            missingRequiredEvidence = copy(missingRequiredEvidence);
            ambiguityResolution = clean(ambiguityResolution);
            constraintCheckResults = copy(constraintCheckResults);
            satisfiedTraceObligationIds = copy(satisfiedTraceObligationIds);
            failedTraceObligationIds = copy(failedTraceObligationIds);
            resultStatus = clean(resultStatus);
            stopReason = clean(stopReason);
        }

        static VerificationPassArtifact empty() {
            return new VerificationPassArtifact("", "", false, List.of(), "", 0, 0,
                    List.of(), "", List.of(), false, List.of(), List.of(), "", "");
        }

        boolean isEmpty() {
            return verificationId.isBlank()
                    && questionId.isBlank()
                    && !valid
                    && requiredCapabilitiesAttempted.isEmpty()
                    && requiredEvidenceStatus.isBlank()
                    && unsupportedClaimCount == 0
                    && contradictedClaimCount == 0
                    && missingRequiredEvidence.isEmpty()
                    && ambiguityResolution.isBlank()
                    && constraintCheckResults.isEmpty()
                    && !abstentionRequired
                    && satisfiedTraceObligationIds.isEmpty()
                    && failedTraceObligationIds.isEmpty()
                    && resultStatus.isBlank()
                    && stopReason.isBlank();
        }
    }

    public record ResearchAnswerArtifact(
            String answerId,
            String questionId,
            String status,
            String answerType,
            String summary,
            List<Map<String, Object>> sections,
            List<String> citedClaimIds,
            List<String> citedEvidenceIds,
            List<String> reasoningArtifactIds,
            String verificationId
    ) {
        public ResearchAnswerArtifact {
            answerId = clean(answerId);
            questionId = clean(questionId);
            status = clean(status);
            answerType = clean(answerType);
            summary = clean(summary);
            sections = sections == null ? List.of() : List.copyOf(sections);
            citedClaimIds = copy(citedClaimIds);
            citedEvidenceIds = copy(citedEvidenceIds);
            reasoningArtifactIds = copy(reasoningArtifactIds);
            verificationId = clean(verificationId);
        }

        static ResearchAnswerArtifact empty() {
            return new ResearchAnswerArtifact("", "", "", "", "", List.of(), List.of(), List.of(), List.of(), "");
        }

        boolean isEmpty() {
            return answerId.isBlank()
                    && questionId.isBlank()
                    && status.isBlank()
                    && answerType.isBlank()
                    && summary.isBlank()
                    && sections.isEmpty()
                    && citedClaimIds.isEmpty()
                    && citedEvidenceIds.isEmpty()
                    && reasoningArtifactIds.isEmpty()
                    && verificationId.isBlank();
        }
    }

    private static List<String> copy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(ReadingResearchTrace::clean)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
