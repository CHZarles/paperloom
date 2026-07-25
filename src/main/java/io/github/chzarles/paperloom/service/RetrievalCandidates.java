package io.github.chzarles.paperloom.service;

import java.util.List;
import java.util.Map;

public record RetrievalCandidates(List<RankedLocationCandidate> ranked,
                                   int matchedCount,
                                   String indexVersion) {
    public RetrievalCandidates {
        ranked = ranked == null ? List.of() : List.copyOf(ranked);
        indexVersion = indexVersion == null ? "" : indexVersion;
    }
}
