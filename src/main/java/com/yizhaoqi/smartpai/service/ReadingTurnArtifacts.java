package com.yizhaoqi.smartpai.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ReadingTurnArtifacts(
        String artifactVersion,
        GoalCard goalCard,
        ReadingIntentFrame intentFrame,
        PaperShortlist paperShortlist,
        ReadingPlan readingPlan,
        ClaimEvidencePanel claimEvidencePanel,
        MissingEvidence missingEvidence,
        List<UiAction> uiActions,
        List<String> uncertaintyNotes,
        ResearchTraceSummary traceSummary
) {
    public ReadingTurnArtifacts {
        artifactVersion = artifactVersion == null || artifactVersion.isBlank()
                ? "reading-turn-artifacts/v1"
                : artifactVersion.trim();
        goalCard = goalCard == null ? GoalCard.empty("") : goalCard;
        intentFrame = intentFrame == null ? ReadingIntentFrame.empty(goalCard.interpretedGoal()) : intentFrame;
        paperShortlist = paperShortlist == null ? PaperShortlist.empty() : paperShortlist;
        readingPlan = readingPlan == null ? ReadingPlan.empty() : readingPlan;
        claimEvidencePanel = claimEvidencePanel == null ? ClaimEvidencePanel.empty() : claimEvidencePanel;
        missingEvidence = missingEvidence == null ? MissingEvidence.empty() : missingEvidence;
        uiActions = uiActions == null ? List.of() : List.copyOf(uiActions);
        uncertaintyNotes = uncertaintyNotes == null ? List.of() : List.copyOf(uncertaintyNotes);
        traceSummary = traceSummary == null ? ResearchTraceSummary.empty() : traceSummary;
    }

    public static ReadingTurnArtifacts empty(String interpretedGoal) {
        return new ReadingTurnArtifacts(
                "reading-turn-artifacts/v1",
                GoalCard.empty(interpretedGoal),
                ReadingIntentFrame.empty(interpretedGoal),
                PaperShortlist.empty(),
                ReadingPlan.empty(),
                ClaimEvidencePanel.empty(),
                MissingEvidence.empty(),
                List.of(),
                List.of(),
                ResearchTraceSummary.empty()
        );
    }

    public record GoalCard(
            String interpretedGoal,
            String scopeLabel,
            Integer readablePaperCount,
            boolean scopeLocked,
            List<UiAction> actions
    ) {
        public GoalCard {
            interpretedGoal = interpretedGoal == null ? "" : interpretedGoal.trim();
            scopeLabel = scopeLabel == null ? "" : scopeLabel.trim();
            actions = actions == null ? List.of() : List.copyOf(actions);
        }

        static GoalCard empty(String interpretedGoal) {
            return new GoalCard(interpretedGoal, "", null, false, List.of());
        }
    }

    public record PaperShortlist(
            List<PaperShortlistItem> items
    ) {
        public PaperShortlist {
            items = items == null ? List.of() : List.copyOf(items);
        }

        static PaperShortlist empty() {
            return new PaperShortlist(List.of());
        }
    }

    public record PaperShortlistItem(
            String paperId,
            String paperHandle,
            String title,
            String originalFilename,
            List<String> authors,
            Integer year,
            String venue,
            String role,
            String roleEvidenceStatus,
            String roleEvidenceSource,
            String matchReason,
            String evidenceStatus,
            boolean ambiguous,
            List<UiAction> actions
    ) {
        public PaperShortlistItem {
            paperId = paperId == null ? "" : paperId.trim();
            paperHandle = paperHandle == null ? "" : paperHandle.trim();
            title = title == null ? "" : title.trim();
            originalFilename = originalFilename == null ? "" : originalFilename.trim();
            authors = authors == null ? List.of() : List.copyOf(authors);
            venue = venue == null ? "" : venue.trim();
            role = role == null ? "" : role.trim();
            roleEvidenceStatus = roleEvidenceStatus == null ? "" : roleEvidenceStatus.trim();
            roleEvidenceSource = roleEvidenceSource == null ? "" : roleEvidenceSource.trim();
            matchReason = matchReason == null ? "" : matchReason.trim();
            evidenceStatus = evidenceStatus == null ? "" : evidenceStatus.trim();
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    public record ReadingPlan(
            List<ReadingPlanStep> steps
    ) {
        public ReadingPlan {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        static ReadingPlan empty() {
            return new ReadingPlan(List.of());
        }
    }

    public record ReadingPlanStep(
            String paperId,
            String paperHandle,
            String locationRef,
            String paperTitle,
            String locationLabel,
            String preview,
            String evidenceStatus,
            List<UiAction> actions
    ) {
        public ReadingPlanStep {
            paperId = paperId == null ? "" : paperId.trim();
            paperHandle = paperHandle == null ? "" : paperHandle.trim();
            locationRef = locationRef == null ? "" : locationRef.trim();
            paperTitle = paperTitle == null ? "" : paperTitle.trim();
            locationLabel = locationLabel == null ? "" : locationLabel.trim();
            preview = preview == null ? "" : preview.trim();
            evidenceStatus = evidenceStatus == null ? "" : evidenceStatus.trim();
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    public record ClaimEvidencePanel(
            List<ClaimEvidenceRow> rows
    ) {
        public ClaimEvidencePanel {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }

        static ClaimEvidencePanel empty() {
            return new ClaimEvidencePanel(List.of());
        }
    }

    public record ClaimEvidenceRow(
            String claim,
            String quote,
            String citationMarker,
            String sourceQuoteRef,
            String paperId,
            String paperHandle,
            String paperTitle,
            String locationRef,
            String locationLabel,
            String contentKind,
            List<String> cannotProve,
            List<UiAction> actions
    ) {
        public ClaimEvidenceRow {
            claim = claim == null ? "" : claim.trim();
            quote = quote == null ? "" : quote.trim();
            citationMarker = citationMarker == null ? "" : citationMarker.trim();
            sourceQuoteRef = sourceQuoteRef == null ? "" : sourceQuoteRef.trim();
            paperId = paperId == null ? "" : paperId.trim();
            paperHandle = paperHandle == null ? "" : paperHandle.trim();
            paperTitle = paperTitle == null ? "" : paperTitle.trim();
            locationRef = locationRef == null ? "" : locationRef.trim();
            locationLabel = locationLabel == null ? "" : locationLabel.trim();
            contentKind = contentKind == null ? "" : contentKind.trim();
            cannotProve = cannotProve == null ? List.of() : List.copyOf(cannotProve);
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    public record MissingEvidence(
            List<String> missing,
            String explanation,
            List<UiAction> nextActions
    ) {
        public MissingEvidence {
            missing = missing == null ? List.of() : List.copyOf(missing);
            explanation = explanation == null ? "" : explanation.trim();
            nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
        }

        static MissingEvidence empty() {
            return new MissingEvidence(List.of(), "", List.of());
        }
    }

    public record UiAction(
            String action,
            String label,
            Map<String, Object> payload
    ) {
        public UiAction {
            action = action == null ? "" : action.trim();
            label = label == null ? "" : label.trim();
            payload = payload == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(payload));
        }
    }

    public record ResearchTraceSummary(
            List<TraceStep> steps,
            EvidenceSummary evidence,
            ClaimSummary claims,
            VerificationSummary verification
    ) {
        public ResearchTraceSummary {
            steps = steps == null ? List.of() : List.copyOf(steps);
            evidence = evidence == null ? EvidenceSummary.empty() : evidence;
            claims = claims == null ? ClaimSummary.empty() : claims;
            verification = verification == null ? VerificationSummary.empty() : verification;
        }

        static ResearchTraceSummary empty() {
            return new ResearchTraceSummary(
                    List.of(),
                    EvidenceSummary.empty(),
                    ClaimSummary.empty(),
                    VerificationSummary.empty()
            );
        }
    }

    public record TraceStep(
            String stage,
            String label,
            String detail,
            String status
    ) {
        public TraceStep {
            stage = stage == null ? "" : stage.trim();
            label = label == null ? "" : label.trim();
            detail = detail == null ? "" : detail.trim();
            status = status == null ? "" : status.trim();
        }
    }

    public record EvidenceSummary(
            int acceptedCount,
            int rejectedCount,
            int missingCount,
            List<String> missing
    ) {
        public EvidenceSummary {
            missing = missing == null ? List.of() : List.copyOf(missing);
        }

        static EvidenceSummary empty() {
            return new EvidenceSummary(0, 0, 0, List.of());
        }
    }

    public record ClaimSummary(
            int totalCount,
            int supportedCount,
            int underdeterminedCount,
            int contradictedCount
    ) {
        static ClaimSummary empty() {
            return new ClaimSummary(0, 0, 0, 0);
        }
    }

    public record VerificationSummary(
            boolean valid,
            String resultStatus,
            String stopReason,
            String requiredEvidenceStatus,
            int missingRequiredEvidenceCount,
            int failedObligationCount
    ) {
        public VerificationSummary {
            resultStatus = resultStatus == null ? "" : resultStatus.trim();
            stopReason = stopReason == null ? "" : stopReason.trim();
            requiredEvidenceStatus = requiredEvidenceStatus == null ? "" : requiredEvidenceStatus.trim();
        }

        static VerificationSummary empty() {
            return new VerificationSummary(false, "", "", "", 0, 0);
        }
    }
}
