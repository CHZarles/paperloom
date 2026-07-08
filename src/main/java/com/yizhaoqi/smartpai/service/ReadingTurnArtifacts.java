package com.yizhaoqi.smartpai.service;

import java.util.List;

public record ReadingTurnArtifacts(
        String interpretedGoal,
        String scopeLabel,
        Integer readablePaperCount,
        boolean scopeLocked,
        List<PaperShortlistItem> paperShortlist,
        List<ReadingPlanStep> readingPlan,
        List<ClaimEvidenceRow> claimEvidenceRows,
        List<String> uncertaintyNotes
) {
    public ReadingTurnArtifacts {
        interpretedGoal = interpretedGoal == null ? "" : interpretedGoal.trim();
        scopeLabel = scopeLabel == null ? "" : scopeLabel.trim();
        paperShortlist = paperShortlist == null ? List.of() : List.copyOf(paperShortlist);
        readingPlan = readingPlan == null ? List.of() : List.copyOf(readingPlan);
        claimEvidenceRows = claimEvidenceRows == null ? List.of() : List.copyOf(claimEvidenceRows);
        uncertaintyNotes = uncertaintyNotes == null ? List.of() : List.copyOf(uncertaintyNotes);
    }

    public static ReadingTurnArtifacts empty(String interpretedGoal) {
        return new ReadingTurnArtifacts(
                interpretedGoal,
                "",
                null,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    public record PaperShortlistItem(
            String title,
            String originalFilename,
            List<String> authors,
            Integer year,
            String venue,
            String evidenceStatus
    ) {
        public PaperShortlistItem {
            title = title == null ? "" : title.trim();
            originalFilename = originalFilename == null ? "" : originalFilename.trim();
            authors = authors == null ? List.of() : List.copyOf(authors);
            venue = venue == null ? "" : venue.trim();
            evidenceStatus = evidenceStatus == null ? "" : evidenceStatus.trim();
        }
    }

    public record ReadingPlanStep(
            String paperTitle,
            String locationLabel,
            String preview,
            String evidenceStatus
    ) {
        public ReadingPlanStep {
            paperTitle = paperTitle == null ? "" : paperTitle.trim();
            locationLabel = locationLabel == null ? "" : locationLabel.trim();
            preview = preview == null ? "" : preview.trim();
            evidenceStatus = evidenceStatus == null ? "" : evidenceStatus.trim();
        }
    }

    public record ClaimEvidenceRow(
            String claim,
            String quote,
            String citationMarker,
            String paperTitle,
            String locationLabel
    ) {
        public ClaimEvidenceRow {
            claim = claim == null ? "" : claim.trim();
            quote = quote == null ? "" : quote.trim();
            citationMarker = citationMarker == null ? "" : citationMarker.trim();
            paperTitle = paperTitle == null ? "" : paperTitle.trim();
            locationLabel = locationLabel == null ? "" : locationLabel.trim();
        }
    }
}
