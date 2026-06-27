package com.yizhaoqi.smartpai.service;

public record LedgerDiagnostics(
        int scannedCount,
        int acceptedEvidenceCount,
        int sourceCount,
        String stopReason
) {
}
