package com.yizhaoqi.smartpai.service;

import java.util.List;

public record ReadingStatePatch(
        SelectedPaper selectedPaper,
        SelectedLocation selectedLocation,
        SelectedSourceQuote selectedSourceQuote,
        List<SelectedPaper> latestShortlist
) {
    public ReadingStatePatch {
        latestShortlist = latestShortlist == null ? List.of() : List.copyOf(latestShortlist);
    }

    public static ReadingStatePatch empty() {
        return new ReadingStatePatch(null, null, null, List.of());
    }

    public boolean isEmpty() {
        return selectedPaper == null
                && selectedLocation == null
                && selectedSourceQuote == null
                && latestShortlist.isEmpty();
    }

    public record SelectedPaper(
            String paperId,
            String paperHandle,
            String title,
            String originalFilename
    ) {
        public SelectedPaper {
            paperId = paperId == null ? "" : paperId.trim();
            paperHandle = paperHandle == null ? "" : paperHandle.trim();
            title = title == null ? "" : title.trim();
            originalFilename = originalFilename == null ? "" : originalFilename.trim();
        }

        public boolean hasIdentity() {
            return !paperId.isBlank() || !paperHandle.isBlank();
        }
    }

    public record SelectedLocation(
            String paperId,
            String paperHandle,
            String locationRef,
            String label
    ) {
        public SelectedLocation {
            paperId = paperId == null ? "" : paperId.trim();
            paperHandle = paperHandle == null ? "" : paperHandle.trim();
            locationRef = locationRef == null ? "" : locationRef.trim();
            label = label == null ? "" : label.trim();
        }

        public boolean hasIdentity() {
            return !locationRef.isBlank();
        }
    }

    public record SelectedSourceQuote(
            String sourceQuoteRef,
            String paperId,
            String paperHandle,
            String locationRef,
            String citationMarker
    ) {
        public SelectedSourceQuote {
            sourceQuoteRef = sourceQuoteRef == null ? "" : sourceQuoteRef.trim();
            paperId = paperId == null ? "" : paperId.trim();
            paperHandle = paperHandle == null ? "" : paperHandle.trim();
            locationRef = locationRef == null ? "" : locationRef.trim();
            citationMarker = citationMarker == null ? "" : citationMarker.trim();
        }

        public boolean hasIdentity() {
            return !sourceQuoteRef.isBlank();
        }
    }
}
