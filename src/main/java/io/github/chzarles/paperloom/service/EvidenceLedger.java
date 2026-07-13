package io.github.chzarles.paperloom.service;

import java.util.List;

public record EvidenceLedger(
        List<PaperSource> sourceSet,
        List<EvidenceItem> evidence,
        LedgerDiagnostics diagnostics
) {
    public EvidenceLedger {
        sourceSet = sourceSet == null ? List.of() : sourceSet;
        evidence = evidence == null ? List.of() : evidence;
        diagnostics = diagnostics == null
                ? new LedgerDiagnostics(0, evidence.size(), sourceSet.size(), "NO_USABLE_EVIDENCE")
                : diagnostics;
    }

    public static EvidenceLedger empty() {
        return new EvidenceLedger(
                List.of(),
                List.of(),
                new LedgerDiagnostics(0, 0, 0, "NO_USABLE_EVIDENCE")
        );
    }
}
