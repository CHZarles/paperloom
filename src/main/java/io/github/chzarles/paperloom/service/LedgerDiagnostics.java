package io.github.chzarles.paperloom.service;

public record LedgerDiagnostics(
        int scannedCount,
        int acceptedEvidenceCount,
        int sourceCount,
        String stopReason
) {
}
